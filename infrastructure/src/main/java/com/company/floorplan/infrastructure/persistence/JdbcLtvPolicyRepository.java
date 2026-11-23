package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.LtvBand;
import com.company.floorplan.domain.model.LtvPolicy;
import com.company.floorplan.domain.model.LtvPolicyStatus;
import com.company.floorplan.domain.repository.LtvPolicyRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 分档 LTV 政策的 JDBC 实现（USED R3.2，V57 两张表）。
 *
 * <p>带集合的读取按「一次头 + 一次带」两查完成，不用 N+1：政策列表会被放款侧频繁读，
 * 一条政策一次查询在 9〜20 条带的规模下就是每次请求多打十几个往返。</p>
 */
@Repository
public class JdbcLtvPolicyRepository implements LtvPolicyRepository {

    private static final String SELECT_POLICY =
            "SELECT id, policy_code, version, policy_name, status, effective_from, effective_to, "
                    + "default_ratio, created_by, approved_by, approved_at, created_at, updated_at "
                    + "FROM ltv_policies WHERE ";

    /** 分组键 {@code policy_id} 必须在投影里：{@link #attachBands} 靠它把带分发回各自的政策。 */
    private static final String SELECT_BANDS_BY_IDS =
            "SELECT id, policy_id, priority, age_band_min_months, age_band_max_months, mileage_band_min_km, "
                    + "mileage_band_max_km, repair_history_in, grade_min, grade_max, "
                    + "inspection_remaining_min, model_heat, dealer_grade_in, max_ratio, rate_addon_bps, "
                    + "breach_threshold, note FROM ltv_policy_bands WHERE policy_id IN (";

    private static final String INSERT_POLICY =
            "INSERT INTO ltv_policies (policy_code, version, policy_name, status, effective_from, effective_to, "
                    + "default_ratio, created_by) VALUES (?,?,?,?,?,?,?,?) RETURNING id";

    /**
     * 带集合插入。16 个业务列 ⟹ 必须正好 16 个 {@code ?}
     * （多一个少一个 PG 只会报「未设定参数值 N」，列名自检查不出来，见 V52 同注释）。
     */
    private static final String INSERT_BAND =
            "INSERT INTO ltv_policy_bands (policy_id, priority, age_band_min_months, age_band_max_months, "
                    + "mileage_band_min_km, mileage_band_max_km, repair_history_in, grade_min, grade_max, "
                    + "inspection_remaining_min, model_heat, dealer_grade_in, max_ratio, rate_addon_bps, "
                    + "breach_threshold, note) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private static final String DELETE_BANDS = "DELETE FROM ltv_policy_bands WHERE policy_id = ?";

    private static final String NEXT_VERSION =
            "SELECT coalesce(max(version), 0) + 1 FROM ltv_policies WHERE policy_code = ?";

    /**
     * 条件式状态迁移。
     *
     * <p>{@code approved_by / approved_at} 用 {@code coalesce(?, 原值)} 而不是直接赋值：
     * 批准时要写入生效痕迹，退休时<b>必须保留</b>当初是谁让它生效的——
     * 清掉就成了「这条政策曾经 ACTIVE 但没人批过」，V57 的
     * {@code ck_ltv_policy_approved} 只对 ACTIVE 判空，救不回被抹掉的历史。</p>
     */
    private static final String UPDATE_STATUS =
            "UPDATE ltv_policies SET status = ?, approved_by = coalesce(?, approved_by), "
                    + "approved_at = coalesce(?, approved_at), updated_at = now() "
                    + "WHERE id = ? AND status = ?";

    private final JdbcTemplate jdbc;

    public JdbcLtvPolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<LtvPolicy> findActive(String policyCode) {
        return jdbc.query(SELECT_POLICY + "policy_code = ? AND status = 'ACTIVE'",
                POLICY_MAPPER, policyCode).stream().findFirst().map(this::withBands);
    }

    @Override
    public Optional<LtvPolicy> findById(long id) {
        return jdbc.query(SELECT_POLICY + "id = ?", POLICY_MAPPER, id).stream().findFirst()
                .map(this::withBands);
    }

    @Override
    public List<LtvPolicy> find(String status) {
        boolean filtered = status != null && !status.isBlank();
        // status 进 WHERE 前已由枚举校验（用例层 LtvPolicyStatus.from），这里只拼固定 SQL 片段，
        // 参数仍走占位符——不做字符串拼接的原因见 SqlStructureSelfCheck 头注。
        List<LtvPolicy> policies = filtered
                ? jdbc.query(SELECT_POLICY + "status = ? ORDER BY policy_code, version DESC",
                        POLICY_MAPPER, status)
                : jdbc.query(SELECT_POLICY + "1 = 1 ORDER BY policy_code, version DESC", POLICY_MAPPER);
        attachBands(policies);
        return policies;
    }

    @Override
    public int nextVersion(String policyCode) {
        Integer v = jdbc.queryForObject(NEXT_VERSION, Integer.class, policyCode);
        return v == null ? 1 : v;
    }

    @Override
    public LtvPolicy insertDraft(LtvPolicy policy) {
        Long id = jdbc.queryForObject(INSERT_POLICY, Long.class,
                policy.getPolicyCode(), policy.getVersion(), policy.getPolicyName(),
                // 不接受调用方给的状态：生效只能走 approve（L5 二人 + ck_ltv_policy_approved）。
                LtvPolicyStatus.DRAFT.name(),
                date(policy.getEffectiveFrom()), date(policy.getEffectiveTo()),
                policy.getDefaultRatio(), policy.getCreatedBy());
        policy.setId(id);
        policy.setStatus(LtvPolicyStatus.DRAFT);
        policy.setApprovedBy(null);
        policy.setApprovedAt(null);
        return policy;
    }

    @Override
    public int replaceBands(long policyId, List<LtvBand> bands) {
        jdbc.update(DELETE_BANDS, policyId);
        if (bands == null || bands.isEmpty()) {
            return 0;
        }
        List<Object[]> args = new ArrayList<>(bands.size());
        for (LtvBand b : bands) {
            args.add(new Object[]{policyId, b.priority(), b.ageBandMinMonths(), b.ageBandMaxMonths(),
                    b.mileageBandMinKm(), b.mileageBandMaxKm(), b.repairHistoryIn(), b.gradeMin(), b.gradeMax(),
                    b.inspectionRemainingMin(), b.modelHeat(), b.dealerGradeIn(), b.maxRatio(),
                    b.rateAddonBps(), b.breachThreshold(), b.note()});
        }
        int[] rows = jdbc.batchUpdate(INSERT_BAND, args);
        return rows.length;
    }

    @Override
    public int updateStatus(long policyId, LtvPolicyStatus from, LtvPolicyStatus to,
                            String approvedBy, OffsetDateTime approvedAt) {
        return jdbc.update(UPDATE_STATUS, to.name(), approvedBy,
                approvedAt == null ? null : Timestamp.from(approvedAt.toInstant()), policyId, from.name());
    }

    /** 单条政策的带集合。 */
    private LtvPolicy withBands(LtvPolicy policy) {
        attachBands(List.of(policy));
        return policy;
    }

    /**
     * 批量装带：一次 {@code IN (...)} 查完，再按 {@code policy_id} 分发。
     *
     * <p>占位符按 id 个数动态生成（都是 long，无注入面）。空列表直接返回——
     * {@code IN ()} 在 PG 里是语法错误，这里必须显式挡掉而不是靠驱动宽容。</p>
     */
    private void attachBands(List<LtvPolicy> policies) {
        if (policies == null || policies.isEmpty()) {
            return;
        }
        List<Long> ids = new ArrayList<>(policies.size());
        for (LtvPolicy p : policies) {
            if (p.getId() != null) {
                ids.add(p.getId());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        String placeholders = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        List<Map<String, Object>> rows = jdbc.queryForList(
                SELECT_BANDS_BY_IDS + placeholders + ") ORDER BY policy_id, priority", ids.toArray());
        Map<Long, List<LtvBand>> grouped = new HashMap<>();
        for (Map<String, Object> row : rows) {
            LtvBand band = bandOf(row);
            grouped.computeIfAbsent(((Number) row.get("policy_id")).longValue(), k -> new ArrayList<>()).add(band);
        }
        for (LtvPolicy p : policies) {
            List<LtvBand> bands = grouped.get(p.getId());
            if (bands != null) {
                for (LtvBand band : bands) {
                    p.addBand(band);
                }
            }
        }
    }

    /**
     * {@code queryForList} 的一行转成 {@link LtvBand}。
     *
     * <p>不用 {@code RowMapper} 是因为批量查询走的是 Map 形态（要同时取 {@code policy_id} 分组）。
     * 数值列全部走 {@code asLong/asInt} 而不是直接强转：PG 对 {@code INT} 返回 Integer、
     * 对 {@code BIGINT} 返回 Long，直接 {@code (Integer) map.get(...)} 会在某一次 PG 驱动升级后
     * 抛 ClassCastException——那种错在冒烟里表现为 500，排查方向却是「政策读不出来」。</p>
     */
    private static LtvBand bandOf(Map<String, Object> row) {
        return new LtvBand(
                asLong(row.get("id")),
                intOf(row.get("priority")),
                intOf(row.get("age_band_min_months")),
                intOf(row.get("age_band_max_months")),
                intOf(row.get("mileage_band_min_km")),
                intOf(row.get("mileage_band_max_km")),
                strOf(row.get("repair_history_in")),
                decimalOf(row.get("grade_min")),
                decimalOf(row.get("grade_max")),
                intOf(row.get("inspection_remaining_min")),
                strOf(row.get("model_heat")),
                strOf(row.get("dealer_grade_in")),
                decimalOf(row.get("max_ratio")),
                intOf(row.get("rate_addon_bps")),
                decimalOf(row.get("breach_threshold")),
                strOf(row.get("note")));
    }

    private static Long asLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static Integer intOf(Object v) {
        return v == null ? null : ((Number) v).intValue();
    }

    private static java.math.BigDecimal decimalOf(Object v) {
        return v == null ? null : (java.math.BigDecimal) v;
    }

    private static String strOf(Object v) {
        return v == null ? null : v.toString();
    }

    private static final RowMapper<LtvPolicy> POLICY_MAPPER = (rs, i) -> {
        LtvPolicy p = new LtvPolicy();
        p.setId(rs.getLong("id"));
        p.setPolicyCode(rs.getString("policy_code"));
        p.setVersion(rs.getInt("version"));
        p.setPolicyName(rs.getString("policy_name"));
        p.setStatus(LtvPolicyStatus.from(rs.getString("status")));
        p.setEffectiveFrom(localDate(rs, "effective_from"));
        p.setEffectiveTo(localDate(rs, "effective_to"));
        p.setDefaultRatio(rs.getBigDecimal("default_ratio"));
        p.setCreatedBy(rs.getString("created_by"));
        p.setApprovedBy(rs.getString("approved_by"));
        p.setApprovedAt(offset(rs, "approved_at"));
        p.setCreatedAt(offset(rs, "created_at"));
        p.setUpdatedAt(offset(rs, "updated_at"));
        return p;
    };

    private static Date date(LocalDate v) {
        return v == null ? null : Date.valueOf(v);
    }

    private static LocalDate localDate(ResultSet rs, String col) throws SQLException {
        Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    private static OffsetDateTime offset(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }
}
