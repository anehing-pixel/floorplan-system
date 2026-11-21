package com.company.floorplan.recon.repository;

import com.company.floorplan.recon.model.Comparison;
import com.company.floorplan.recon.model.ReconDiff;
import com.company.floorplan.recon.model.ReconRun;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对账清算仓储：账本只读 + 自己的差异表可写。
 *
 * <p><b>只读承诺（硬性边界）</b>：本类所有账本访问都是 SELECT，
 * 只写 recon_runs / recon_diffs 两张自有表。 INSERT/UPDATE 语句里
 * 绝不出现 advances / vehicle_units / credit_lines / repayments /
 * payment_transactions——对拍服务不改账务，这是它能被第一个放行的原因。</p>
 *
 * <p><b>为什么用 NamedParameterJdbcTemplate</b>：四条规则的 SQL 都要传
 * "状态集合"这类列表参数（{@code IN (:active)}）。用 {@code ?} 占位符
 * 就得手工拼逗号和重复参数，既是 SQL 注入面也是漏参数的来源；
 * 命名参数让"状态集合"在 SQL 里只出现一次、由框架安全展开。</p>
 *
 * <p><b>取数与判定分离</b>：{@link #loadComparisons(String)} 只负责把账本读成
 * {@link Comparison}（subject / 期望侧 / 实际侧），是否构成差异由 rule 包的纯函数判定。
 * 这样 SQL 正确性靠库验证、判定正确性靠参数化单测验证，互不为前提。</p>
 */
@Repository
public class JdbcReconRepository {

    /** 占用"一车一笔垫款"名额的状态，与 AdvanceStatus.occupiesVehicle() / uk_adv_vin_active 一致。 */
    private static final List<String> ACTIVE_ADVANCE = List.of("PENDING", "PROCESSING", "PAID");
    /** 债权应当存续的车辆状态：已放款未入库、在库、已售待还、逾期、处置中。 */
    private static final List<String> DEBT_BEARING_VEHICLE =
            List.of("FINANCED", "IN_STOCK", "SOLD_PENDING", "OVERDUE", "DISPOSING");

    /** R1：垫款金额 vs（未偿本金 + 累计已核销本金），按 VIN。 */
    private static final String R1_SQL = """
            SELECT a.vin AS subject,
                   a.amount AS expected,
                   v.principal_balance + COALESCE(r.repaid, 0) AS actual,
                   '垫款金额 vs 未偿本金+累计核销本金' AS note
              FROM advances a
              JOIN vehicle_units v ON v.vin = a.vin
              LEFT JOIN (SELECT vin, SUM(principal) AS repaid FROM repayments GROUP BY vin) r
                     ON r.vin = a.vin
             WHERE a.status IN (:active)
            """;

    /** R2：车辆状态是否应当存在未结清垫款，按 VIN；布尔关系编码为 1/0。 */
    private static final String R2_SQL = """
            SELECT v.vin AS subject,
                   CASE WHEN v.status IN (:debtBearing) THEN 1 ELSE 0 END AS expected,
                   CASE WHEN EXISTS (SELECT 1 FROM advances a
                                      WHERE a.vin = v.vin AND a.status IN (:active))
                        THEN 1 ELSE 0 END AS actual,
                   '车辆债权存续状态(1=应有垫款) vs 未结清垫款是否存在(1=有)' AS note
              FROM vehicle_units v
             WHERE v.status IN (:observed)
            """;

    /** R3：额度已占用 vs 在途/存续垫款合计，按额度。 */
    private static final String R3_SQL = """
            SELECT CAST(cl.id AS VARCHAR) AS subject,
                   cl.used_amount AS expected,
                   COALESCE(a.inflight, 0) AS actual,
                   'credit_lines.used_amount vs 垫款未终结金额合计' AS note
              FROM credit_lines cl
              LEFT JOIN (SELECT credit_line_id, SUM(amount) AS inflight
                           FROM advances
                          WHERE status IN (:active)
                          GROUP BY credit_line_id) a
                     ON a.credit_line_id = cl.id
             WHERE cl.status = 'ACTIVE'
            """;

    /** R4：银行已匹配入账 vs 还款核销累计，按 VIN；FULL OUTER JOIN 覆盖"只有一侧有记录"的情形。 */
    private static final String R4_SQL = """
            SELECT COALESCE(p.vin, r.vin) AS subject,
                   COALESCE(p.inflow, 0) AS expected,
                   COALESCE(r.repaid, 0) AS actual,
                   '银行已匹配入账合计 vs 还款核销总额' AS note
              FROM (SELECT matched_vin AS vin, SUM(amount) AS inflow
                      FROM payment_transactions
                     WHERE match_status IN ('AUTO_MATCHED', 'MANUAL_MATCHED')
                       AND matched_vin IS NOT NULL AND amount > 0
                     GROUP BY matched_vin) p
              FULL OUTER JOIN (SELECT vin, SUM(total_amount) AS repaid
                                 FROM repayments GROUP BY vin) r
                ON r.vin = p.vin
            """;

    private static final RowMapper<Comparison> COMPARISON_MAPPER = (rs, i) -> new Comparison(
            rs.getString("subject"),
            rs.getLong("expected"),
            rs.getLong("actual"),
            rs.getString("note"));

    private static final RowMapper<ReconDiff> DIFF_MAPPER = (rs, i) -> {
        ReconDiff d = new ReconDiff();
        d.setId(rs.getLong("id"));
        d.setBatchNo(rs.getString("batch_no"));
        d.setRuleCode(rs.getString("rule_code"));
        d.setSubject(rs.getString("subject"));
        d.setExpectedValue(rs.getLong("expected_value"));
        d.setActualValue(rs.getLong("actual_value"));
        d.setDiffType(rs.getString("diff_type"));
        d.setStatus(rs.getString("status"));
        d.setDetail(rs.getString("detail"));
        Timestamp ts = rs.getTimestamp("detected_at");
        d.setDetectedAt(ts == null ? null : ts.toLocalDateTime());
        return d;
    };

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcReconRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // 账本只读：把账本读成待判定的事实
    // ------------------------------------------------------------------

    /**
     * 读取某条规则的账本事实。R5-R8 是占位规则，返回空列表（不读库、不产出差异）。
     *
     * @param ruleCode 规则编号 R1..R8
     * @return 待判定的 Comparison 列表
     */
    public List<Comparison> loadComparisons(String ruleCode) {
        Map<String, Object> params = Map.of(
                "active", ACTIVE_ADVANCE,
                "debtBearing", DEBT_BEARING_VEHICLE,
                "observed", DEBT_BEARING_VEHICLE);
        return switch (ruleCode) {
            // R2 三条列表参数都与 observed 集合相同，R1/R3 只用到 active，
            // 多传的命名参数不影响执行（NamedParameterJdbcTemplate 只绑定 SQL 中出现的参数）。
            case "R1" -> jdbc.query(R1_SQL, params, COMPARISON_MAPPER);
            case "R2" -> jdbc.query(R2_SQL, params, COMPARISON_MAPPER);
            case "R3" -> jdbc.query(R3_SQL, params, COMPARISON_MAPPER);
            case "R4" -> jdbc.query(R4_SQL, Map.of(), COMPARISON_MAPPER);
            case "R5", "R6", "R7", "R8" -> List.of(); // TODO 占位规则：规则细节待业务确认
            default -> throw new IllegalArgumentException("未知对拍规则：" + ruleCode);
        };
    }

    // ------------------------------------------------------------------
    // 自有表写入：批次 + 差异
    // ------------------------------------------------------------------

    /** 开跑即落库：进程被杀后库里仍留 RUNNING 痕迹。 */
    public void insertRun(ReconRun run) {
        jdbc.update("""
                INSERT INTO recon_runs (batch_no, biz_date, status, rule_count, diff_count,
                                        started_at, finished_at, error_detail)
                VALUES (:batchNo, :bizDate, :status, :ruleCount, :diffCount,
                        :startedAt, :finishedAt, :errorDetail)
                """, new MapSqlParameterSource()
                .addValue("batchNo", run.getBatchNo())
                .addValue("bizDate", run.getBizDate())
                .addValue("status", run.getStatus())
                .addValue("ruleCount", run.getRuleCount())
                .addValue("diffCount", run.getDiffCount())
                .addValue("startedAt", run.getStartedAt())
                .addValue("finishedAt", run.getFinishedAt())
                .addValue("errorDetail", run.getErrorDetail()));
    }

    /** 收尾更新（成功或失败都走这里）。 */
    public void updateRun(ReconRun run) {
        jdbc.update("""
                UPDATE recon_runs
                   SET status = :status, rule_count = :ruleCount, diff_count = :diffCount,
                       finished_at = :finishedAt, error_detail = :errorDetail
                 WHERE batch_no = :batchNo
                """, new MapSqlParameterSource()
                .addValue("batchNo", run.getBatchNo())
                .addValue("status", run.getStatus())
                .addValue("ruleCount", run.getRuleCount())
                .addValue("diffCount", run.getDiffCount())
                .addValue("finishedAt", run.getFinishedAt())
                .addValue("errorDetail", run.getErrorDetail()));
    }

    /**
     * 批量落差异。{@code ON CONFLICT DO NOTHING} 与 V20 的
     * {@code uq_recon_diff (batch_no, rule_code, subject)} 配套：
     * 同一批次同一规则同一对象重复出现时静默去重，避免"差异条数"被重试放大。
     *
     * @return 实际入库条数（去重后）
     */
    public int insertDiffs(List<ReconDiff> diffs) {
        if (diffs.isEmpty()) {
            return 0;
        }
        SqlParameterSource[] batch = new SqlParameterSource[diffs.size()];
        for (int i = 0; i < diffs.size(); i++) {
            ReconDiff d = diffs.get(i);
            batch[i] = new MapSqlParameterSource()
                    .addValue("batchNo", d.getBatchNo())
                    .addValue("ruleCode", d.getRuleCode())
                    .addValue("subject", d.getSubject())
                    .addValue("expectedValue", d.getExpectedValue())
                    .addValue("actualValue", d.getActualValue())
                    .addValue("diffType", d.getDiffType())
                    .addValue("status", d.getStatus())
                    .addValue("detail", d.getDetail())
                    .addValue("detectedAt", d.getDetectedAt());
        }
        int[] counts = jdbc.batchUpdate("""
                INSERT INTO recon_diffs (batch_no, rule_code, subject, expected_value,
                                         actual_value, diff_type, status, detail, detected_at)
                VALUES (:batchNo, :ruleCode, :subject, :expectedValue,
                        :actualValue, :diffType, :status, :detail, :detectedAt)
                ON CONFLICT (batch_no, rule_code, subject) DO NOTHING
                """, batch);
        int inserted = 0;
        for (int c : counts) {
            // batchUpdate 对 ON CONFLICT DO NOTHING 可能返回 -2（SUCCESS_NO_INFO），按 1 计
            inserted += (c == 1 || c == -2) ? 1 : 0;
        }
        return inserted;
    }

    /** 条件查询差异：batchNo / status 均为可选，拼 SQL 而非用 "(:p IS NULL OR ...)" 规避 JDBC 空参数类型推断。 */
    public List<ReconDiff> findDiffs(String batchNo, String status) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, batch_no, rule_code, subject, expected_value, actual_value,
                       diff_type, status, detail, detected_at
                  FROM recon_diffs
                 WHERE 1 = 1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (batchNo != null && !batchNo.isBlank()) {
            sql.append(" AND batch_no = :batchNo");
            params.addValue("batchNo", batchNo.trim());
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status.trim().toUpperCase());
        }
        sql.append(" ORDER BY detected_at DESC, id DESC");
        return new ArrayList<>(jdbc.query(sql.toString(), params, DIFF_MAPPER));
    }
}
