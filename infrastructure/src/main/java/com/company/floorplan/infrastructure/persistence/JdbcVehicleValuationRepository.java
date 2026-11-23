package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.PrudentValueRule;
import com.company.floorplan.domain.model.ValuationInput;
import com.company.floorplan.domain.model.ValuationPurpose;
import com.company.floorplan.domain.model.ValuationSource;
import com.company.floorplan.domain.model.ValuationStatus;
import com.company.floorplan.domain.model.VehicleValuation;
import com.company.floorplan.domain.repository.VehicleValuationRepository;
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
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 车辆估值的 JDBC 实现（USED R3，V55 两张表）。
 *
 * <p>沿用本仓库做法：原生 {@code JdbcTemplate} + 手写 SQL，插入走
 * {@code INSERT ... RETURNING id}，日期显式转 {@link Date}/{@link Timestamp}。
 * 本类引用的每个列名都同步登记在 {@code SqlStructureSelfCheck} 白名单里，
 * 目的是把「列名写错」从运行期 500 挪到启动期自检。</p>
 *
 * <p><b>聚合读两次查询是有意的</b>：主表一行 + 明细多行。没有用 join 平铺，
 * 因为平铺后「零明细」和「主表零行」在映射层是同一个结果，
 * 而这两者在闸门上是两种处置（前者是数据断裂，后者是车没估值）。</p>
 */
@Repository
public class JdbcVehicleValuationRepository implements VehicleValuationRepository {

    private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    /** 主表读取列（与 V55 列清单一一对应；24 写列 + 4 库内默认/自增）。 */
    private static final String SELECT_HEADER =
            "SELECT id, valuation_key, vin, purpose, auction_won_price, appraised_value, market_price, "
                    + "manual_value, prudent_value, prudent_rule, primary_source, auction_lot_id, "
                    + "market_snapshot_id, appraisal_provider, appraisal_no, report_path, report_digest, "
                    + "valuation_date, valid_until, status, overridden_by, co_signed_by, source_message_id, "
                    + "created_at, updated_at, created_by, updated_by, version FROM vehicle_valuations WHERE ";

    /**
     * 明细读取：排序键含 {@code id}，因为 (source, provider, as_of) 之外的行没有天然次序。
     * 不加 id 时同一源的多次报价在复算日志里会乱序，读者无法判断哪条是后写的。
     */
    private static final String SELECT_INPUTS =
            "SELECT source, value, ref, provider, as_of, weight, rank FROM valuation_inputs "
                    + "WHERE valuation_id = ? ORDER BY source, as_of, id";

    private static final String INSERT_HEADER =
            "INSERT INTO vehicle_valuations (valuation_key, vin, purpose, auction_won_price, appraised_value, "
                    + "market_price, manual_value, prudent_value, prudent_rule, primary_source, auction_lot_id, "
                    + "market_snapshot_id, appraisal_provider, appraisal_no, report_path, report_digest, "
                    + "valuation_date, valid_until, status, overridden_by, co_signed_by, source_message_id, "
                    + "created_by, updated_by) "
                    // 24 列 ⟹ 必须正好 24 个 ?。多一个少一个都由 PG 在运行期报"未设定参数值 N"，
                    // SqlStructureSelfCheck 比的是列名不是占位符数，查不出来（V52 那次同理）。
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id";

    /**
     * 明细插入。{@code ON CONFLICT DO NOTHING} 的冲突目标是 V55 的 {@code uk_val_input}
     * （{@code valuation_id, source, provider, as_of}，<b>普通</b>唯一索引，所以不用带谓词）。
     *
     * <p>为什么允许「静默不插」：同一笔估值重放时明细必须不翻倍，否则复算会把两条同价输入
     * 当成两个源。而「同键不同价」是数据污染，必须能被审计发现——它同样被 DO NOTHING 挡掉，
     * 挡掉的痕迹由调用方的 {@code audit_events} 承担（覆盖动作本来就必审计）。</p>
     */
    private static final String INSERT_INPUT =
            "INSERT INTO valuation_inputs (valuation_id, source, value, ref, provider, as_of, weight, rank) "
                    + "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (valuation_id, source, provider, as_of) DO NOTHING";

    /** 结论列更新：三个事实列不在 SET 清单里（V55 头注「绝不改写历史行」）。 */
    private static final String UPDATE_CONCLUSION =
            "UPDATE vehicle_valuations SET prudent_value = ?, prudent_rule = ?, primary_source = ?, "
                    + "manual_value = ?, overridden_by = ?, co_signed_by = ?, status = ?, valid_until = ?, "
                    + "updated_by = ?, updated_at = now(), version = version + 1 "
                    + "WHERE id = ? AND version = ?";

    private static final String SUPERSEDE_ACTIVE =
            "UPDATE vehicle_valuations SET status = 'SUPERSEDED', updated_by = ?, updated_at = now(), "
                    + "version = version + 1 WHERE vin = ? AND status = 'ACTIVE'";

    /** {@code valuation_key} 前缀计数（D00 §13.1 的 seq）。LIKE 的元字符由 {@link #likePrefix} 转义。 */
    private static final String COUNT_KEYS_WITH_PREFIX =
            "SELECT count(*) FROM vehicle_valuations WHERE valuation_key LIKE ? ESCAPE '\\'";

    private final JdbcTemplate jdbc;

    public JdbcVehicleValuationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String nextValuationKey(String vin, LocalDate valuationDate) {
        String prefix = "VAL-" + vin + "-" + (valuationDate == null ? LocalDate.now(ZoneOffset.UTC)
                .format(KEY_DATE) : valuationDate.format(KEY_DATE)) + "-";
        Integer n = jdbc.queryForObject(COUNT_KEYS_WITH_PREFIX, Integer.class, likePrefix(prefix));
        // count 与 INSERT 之间有并发窗口，兜底是 valuation_key 的 UNIQUE（不是这里）。
        return prefix + ((n == null ? 0 : n) + 1);
    }

    @Override
    public VehicleValuation insert(VehicleValuation v) {
        Long id = jdbc.queryForObject(INSERT_HEADER, Long.class, headerArgs(v));
        v.setId(id);
        for (ValuationInput input : v.getInputs()) {
            insertInput(id, input);
        }
        return v;
    }

    @Override
    public void appendInput(long valuationId, ValuationInput input) {
        insertInput(valuationId, input);
    }

    private void insertInput(long valuationId, ValuationInput input) {
        ValuationInput normalized = input.normalizedProvider();
        jdbc.update(INSERT_INPUT, valuationId, normalized.source().name(), normalized.value(),
                normalized.ref(), normalized.provider(), date(normalized.asOf()),
                normalized.weight(), normalized.rank());
    }

    @Override
    public int updateConclusion(VehicleValuation v) {
        int rows = jdbc.update(UPDATE_CONCLUSION,
                v.getPrudentValue(),
                v.getPrudentRule() == null ? null : v.getPrudentRule().name(),
                v.getPrimarySource() == null ? null : v.getPrimarySource().name(),
                v.getManualValue(), v.getOverriddenBy(), v.getCoSignedBy(),
                v.getStatus() == null ? null : v.getStatus().name(),
                date(v.getValidUntil()), v.getUpdatedBy(),
                v.getId(), v.getVersion());
        if (rows > 0) {
            v.setVersion(v.getVersion() + 1);
        }
        return rows;
    }

    @Override
    public int supersedeActive(String vin, String actor) {
        return jdbc.update(SUPERSEDE_ACTIVE, actor, vin);
    }

    @Override
    public Optional<VehicleValuation> findAdvanceBase(String vin) {
        // purpose 与 status 一起进 WHERE：uk_val_active 只保证「一 VIN 一 ACTIVE」，
        // 不保证那一条就是放款基准（它可能是 PERIODIC_RECHECK），后者必须由闸门看见并拒绝。
        List<VehicleValuation> rows = jdbc.query(
                SELECT_HEADER + "vin = ? AND status = 'ACTIVE' AND purpose = 'ADVANCE_BASE' ORDER BY id DESC",
                HEADER_MAPPER, vin);
        return rows.stream().findFirst().map(this::withInputs);
    }

    @Override
    public Optional<VehicleValuation> findById(long id) {
        return jdbc.query(SELECT_HEADER + "id = ?", HEADER_MAPPER, id).stream().findFirst()
                .map(this::withInputs);
    }

    @Override
    public Optional<VehicleValuation> findByKey(String valuationKey) {
        if (valuationKey == null || valuationKey.isBlank()) {
            return Optional.empty();
        }
        // 不加 status 条件：幂等重放要能认出「那一次已经发生过」，即使它今天已被后来的估值替代。
        return jdbc.query(SELECT_HEADER + "valuation_key = ?", HEADER_MAPPER, valuationKey.trim())
                .stream().findFirst().map(this::withInputs);
    }

    /** 明细装载：一条主表行配它的 N 条输入。 */
    private VehicleValuation withInputs(VehicleValuation v) {
        List<ValuationInput> inputs = jdbc.query(SELECT_INPUTS, INPUT_MAPPER, v.getId());
        for (ValuationInput input : inputs) {
            v.attachInputFromStorage(input);
        }
        return v;
    }

    /** {@link #INSERT_HEADER} 的绑定值，顺序即列顺序（改列必同步改这里）。 */
    private static Object[] headerArgs(VehicleValuation v) {
        return new Object[]{
                v.getValuationKey(), v.getVin(),
                v.getPurpose() == null ? null : v.getPurpose().name(),
                v.getAuctionWonPrice(), v.getAppraisedValue(), v.getMarketPrice(), v.getManualValue(),
                v.getPrudentValue(),
                v.getPrudentRule() == null ? null : v.getPrudentRule().name(),
                v.getPrimarySource() == null ? null : v.getPrimarySource().name(),
                v.getAuctionLotId(), v.getMarketSnapshotId(), v.getAppraisalProvider(), v.getAppraisalNo(),
                v.getReportPath(), v.getReportDigest(),
                date(v.getValuationDate()), date(v.getValidUntil()),
                v.getStatus() == null ? ValuationStatus.DRAFT.name() : v.getStatus().name(),
                v.getOverriddenBy(), v.getCoSignedBy(), v.getSourceMessageId(),
                v.getCreatedBy(), v.getUpdatedBy()};
    }

    /**
     * 把业务键前缀转成 LIKE 模式。
     *
     * <p>VIN 的字符集受 V1 约束（字母数字），本来不含 {@code % _}；仍然转义是因为
     * 这里的输入是「VIN + 日期」拼出来的<b>用户可控片段</b>，一个带下划线的键会让计数偏大，
     * 从而生成一个已被占用的 seq。转义字符与 {@link #COUNT_KEYS_WITH_PREFIX} 的 {@code ESCAPE '\'} 成对。</p>
     */
    private static String likePrefix(String prefix) {
        StringBuilder sb = new StringBuilder(prefix.length() + 8);
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (c == '\\' || c == '%' || c == '_') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('%');
        return sb.toString();
    }

    // ── RowMapper ────────────────────────────────────────────────────────

    /**
     * 主表映射。
     *
     * <p>{@code auction_won_price} 等四个来源列走 {@link #nullableLong}：{@code rs.getLong} 把 NULL
     * 读成 0，而 V55 头注把 NULL（这个源没报）与 0（源报了零）定为<b>两种事实</b>。
     * 读成 0 会让 {@code MIN_OF_SOURCES} 把未接入的源当成「市场认为这台车一文不值」，
     * 审慎值直接归零 ⟹ 全量拒付，且库里看不出任何异常。</p>
     */
    private static final RowMapper<VehicleValuation> HEADER_MAPPER = (rs, i) -> {
        VehicleValuation v = new VehicleValuation();
        v.setId(rs.getLong("id"));
        v.setValuationKey(rs.getString("valuation_key"));
        v.setVin(rs.getString("vin"));
        v.setPurpose(ValuationPurpose.from(rs.getString("purpose")));
        v.setAuctionWonPrice(nullableLong(rs, "auction_won_price"));
        v.setAppraisedValue(nullableLong(rs, "appraised_value"));
        v.setMarketPrice(nullableLong(rs, "market_price"));
        v.setManualValue(nullableLong(rs, "manual_value"));
        v.setPrudentValue(rs.getLong("prudent_value"));
        v.setPrudentRule(PrudentValueRule.from(rs.getString("prudent_rule")));
        v.setPrimarySource(ValuationSource.from(rs.getString("primary_source")));
        v.setAuctionLotId(nullableLong(rs, "auction_lot_id"));
        v.setMarketSnapshotId(nullableLong(rs, "market_snapshot_id"));
        v.setAppraisalProvider(rs.getString("appraisal_provider"));
        v.setAppraisalNo(rs.getString("appraisal_no"));
        v.setReportPath(rs.getString("report_path"));
        v.setReportDigest(rs.getString("report_digest"));
        v.setValuationDate(localDate(rs, "valuation_date"));
        v.setValidUntil(localDate(rs, "valid_until"));
        v.setStatus(ValuationStatus.from(rs.getString("status")));
        v.setOverriddenBy(rs.getString("overridden_by"));
        v.setCoSignedBy(rs.getString("co_signed_by"));
        v.setSourceMessageId(nullableLong(rs, "source_message_id"));
        v.setCreatedAt(offset(rs, "created_at"));
        v.setUpdatedAt(offset(rs, "updated_at"));
        v.setCreatedBy(rs.getString("created_by"));
        v.setUpdatedBy(rs.getString("updated_by"));
        v.setVersion(rs.getInt("version"));
        return v;
    };

    private static final RowMapper<ValuationInput> INPUT_MAPPER = (rs, i) -> new ValuationInput(
            ValuationSource.from(rs.getString("source")),
            rs.getLong("value"),
            rs.getString("ref"),
            rs.getString("provider"),
            localDate(rs, "as_of"),
            rs.getBigDecimal("weight"),
            nullableInt(rs, "rank"));

    // ── 类型转换小工具（NULL 必须显式处理，理由见 HEADER_MAPPER）────────────

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

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Integer nullableInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }
}
