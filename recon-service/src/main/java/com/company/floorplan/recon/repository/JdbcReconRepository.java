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

    /**
     * R5：台账累计计提利息 vs 计息流水累计（主表 + 季度归档表），按 VIN。
     *
     * <p><b>口径裁决</b>：DAILY_INTEREST 作业每次同时写 {@code interest_ledger} 流水
     * 与 {@code vehicle_units.accrued_interest} 累加，两者本就应精确相等；
     * INTEREST_ARCHIVE 只把流水从主表搬到 {@code interest_ledger_archive}，不减台账，
     * 因此取数必须 UNION ALL 两张流水表，否则归档后必然报出假差异。
     * FULL OUTER JOIN 覆盖「只有台账 / 只有流水」的单侧事实。</p>
     */
    private static final String R5_SQL = """
            SELECT COALESCE(v.vin, f.vin) AS subject,
                   COALESCE(v.accrued, 0) AS expected,
                   COALESCE(f.flow, 0) AS actual,
                   '台账累计计提利息 vs 计息流水累计(主表+归档)' AS note
              FROM (SELECT vin, accrued_interest AS accrued FROM vehicle_units) v
              FULL OUTER JOIN (
                    SELECT vin, SUM(daily_interest) AS flow
                      FROM (SELECT vin, daily_interest FROM interest_ledger
                            UNION ALL
                            SELECT vin, daily_interest FROM interest_ledger_archive) il
                     GROUP BY vin) f
                ON f.vin = v.vin
            """;

    /**
     * R6：V10 有效登记编码 1/0 vs 担保台账 REGISTERED 编码 1/0，按 VIN。
     *
     * <p><b>口径裁决（ADR-Q27）</b>：V10 {@code collateral_registration_batches + items}
     * 是唯一权威源，有效登记定义为 {@code registration_no IS NOT NULL}
     * 且 {@code cancellation_status NOT IN ('DONE','CANCELLED')}；
     * {@code collateral_ledger.security_status} 是台账标记，理应与权威源一致。
     * 两边不一致（如结清后台账已 RELEASED 而登记簿仍待抹消）正是本规则要暴露的账实差异。</p>
     */
    private static final String R6_SQL = """
            SELECT COALESCE(r.vin, l.vin) AS subject,
                   COALESCE(r.registered, 0) AS expected,
                   COALESCE(l.registered, 0) AS actual,
                   'V10 有效登记(1=有) vs 担保台账 REGISTERED(1=是)' AS note
              FROM (SELECT i.vin, 1 AS registered
                      FROM collateral_registration_items i
                      JOIN collateral_registration_batches b ON b.id = i.batch_id
                     WHERE b.registration_no IS NOT NULL
                       AND b.cancellation_status NOT IN ('DONE','CANCELLED')
                     GROUP BY i.vin) r
              FULL OUTER JOIN (
                    SELECT vin, CASE WHEN security_status = 'REGISTERED' THEN 1 ELSE 0 END AS registered
                      FROM collateral_ledger) l
                ON l.vin = r.vin
            """;

    /**
     * R7：库龄递减计划已付期数 vs 递减核销流水笔数，按 VIN。
     *
     * <p><b>口径裁决</b>：CurtailmentApplicationService 每偿付一期同时做
     * {@code aging_plans.paid_periods + 1} 与写一条 {@code curtailment_payments}（含幂等键），
     * 两者本就应精确相等。不做「期数容差」——差一期就是账实不符。</p>
     */
    private static final String R7_SQL = """
            SELECT COALESCE(a.vin, c.vin) AS subject,
                   COALESCE(a.paid_periods, 0) AS expected,
                   COALESCE(c.cnt, 0) AS actual,
                   '递减计划已付期数 vs 递减核销流水笔数' AS note
              FROM aging_plans a
              FULL OUTER JOIN (SELECT vin, COUNT(*) AS cnt FROM curtailment_payments GROUP BY vin) c
                ON c.vin = a.vin
            """;

    /**
     * R8：处置已结案编码 1/0 vs 借据已终止编码 1/0，按 VIN。
     *
     * <p><b>口径裁决</b>：规则名「处置损失 vs 账务核销」原被误读成金额对拍，
     * 但 {@code disposal_cases.loss_amount}（= 应收 - 变现所得）与借据未偿本金
     * <b>本就不该相等</b>，直接比金额会产出 100% 噪音差异、毫无判别力。
     * 因此按 R2 同款做法归一为布尔关系：处置进入 CLOSED_* 终态 ↔ 借据进入
     * DEFAULTED/WRITTEN_OFF 终态，两者应同时成立。当前系统处置结案未联动借据减记，
     * 本规则会如实报出该缺口（正是它的价值），而非假装通过。</p>
     */
    private static final String R8_SQL = """
            SELECT COALESCE(d.vin, l.vin) AS subject,
                   CASE WHEN d.vin IS NOT NULL THEN 1 ELSE 0 END AS expected,
                   CASE WHEN l.vin IS NOT NULL THEN 1 ELSE 0 END AS actual,
                   '处置已结案 CLOSED_*(1) vs 借据 DEFAULTED/WRITTEN_OFF(1)' AS note
              FROM (SELECT DISTINCT vin FROM disposal_cases
                     WHERE status IN ('CLOSED_LOSS','CLOSED_RECOVERED')) d
              FULL OUTER JOIN (SELECT DISTINCT vin FROM loans
                                WHERE status IN ('DEFAULTED','WRITTEN_OFF')) l
                ON l.vin = d.vin
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
     * 读取某条规则的账本事实。8 条规则全部真实取数，取数口径写在各自 SQL 常量上。
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
            // R5-R8 无列表参数：《业务口径总定源》ADR 裁决后按真实账本取数
            case "R5" -> jdbc.query(R5_SQL, Map.of(), COMPARISON_MAPPER);
            case "R6" -> jdbc.query(R6_SQL, Map.of(), COMPARISON_MAPPER);
            case "R7" -> jdbc.query(R7_SQL, Map.of(), COMPARISON_MAPPER);
            case "R8" -> jdbc.query(R8_SQL, Map.of(), COMPARISON_MAPPER);
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
