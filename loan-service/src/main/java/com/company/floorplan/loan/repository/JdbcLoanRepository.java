package com.company.floorplan.loan.repository;

import com.company.floorplan.loan.model.DealerRiskProfile;
import com.company.floorplan.loan.model.Loan;
import com.company.floorplan.loan.model.OverdueRecord;
import com.company.floorplan.loan.model.RepaymentPlan;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 借据仓储（真实 JDBC）。
 *
 * <p>写法参考 infrastructure.JdbcPaymentTransactionRepository，但使用
 * {@link NamedParameterJdbcTemplate}——借据查询的筛选条件多（VIN + 未结清状态、经销商 + 状态、
 * 阶段 + DPD），命名参数比一串 {@code ?} 可读且不易错位。</p>
 *
 * <p><b>幂等与单一写入点</b>：借据生成用 {@code loan_no} 唯一约束 + ON CONFLICT；
 * 逾期记录用 {@code uk_overdue_loan} 做"同记录升档"；画像用全量 upsert（不做增量 patch）。
 * 外部严禁直接 UPDATE loans.status / current_dpd。</p>
 */
@Repository
public class JdbcLoanRepository {

    private final NamedParameterJdbcTemplate jdbc;

    /** 未结清状态集合，与契约 §3 映射规则及 idx_loans_vin_active 一致。 */
    public static final String OPEN_STATUSES = "'DRAFT','ACTIVE','OVERDUE','SETTLING'";

    private static final RowMapper<Loan> LOAN_MAPPER = (rs, i) -> {
        Loan l = new Loan();
        l.setId(rs.getLong("id"));
        l.setLoanNo(rs.getString("loan_no"));
        l.setAdvanceId(nullableLong(rs, "advance_id"));
        l.setVin(rs.getString("vin"));
        l.setDealerId(rs.getLong("dealer_id"));
        l.setCreditLineId(nullableLong(rs, "credit_line_id"));
        l.setPurchaseOrderId(nullableLong(rs, "purchase_order_id"));
        l.setPrincipal(rs.getLong("principal"));
        l.setDueDate(toLocalDate(rs.getDate("due_date")));
        l.setStatus(rs.getString("status"));
        l.setCreatedAt(toLocalDateTime(rs.getTimestamp("created_at")));
        l.setDisbursedAt(toLocalDateTime(rs.getTimestamp("disbursed_at")));
        l.setMaturityDate(toLocalDate(rs.getDate("maturity_date")));
        l.setGracePeriodDays(rs.getInt("grace_period_days"));
        l.setOverdueRate(rs.getBigDecimal("overdue_rate"));
        l.setTermSource(rs.getString("term_source"));
        l.setTermConfirmedBy(rs.getString("term_confirmed_by"));
        l.setTermConfirmedAt(toLocalDateTime(rs.getTimestamp("term_confirmed_at")));
        l.setPrincipalOutstanding(rs.getLong("principal_outstanding"));
        l.setInterestOutstanding(rs.getLong("interest_outstanding"));
        l.setPenaltyOutstanding(rs.getLong("penalty_outstanding"));
        l.setFeeOutstanding(rs.getLong("fee_outstanding"));
        l.setCurrentDpd(rs.getInt("current_dpd"));
        l.setCurrentStageCode(rs.getString("current_stage_code"));
        l.setOverdueSince(toLocalDate(rs.getDate("overdue_since")));
        l.setMaxDpdEver(rs.getInt("max_dpd_ever"));
        l.setOverdueCount(rs.getInt("overdue_count"));
        l.setCurrentStageEnteredAt(toLocalDateTime(rs.getTimestamp("current_stage_entered_at")));
        l.setDeductionFailStreak(rs.getInt("deduction_fail_streak"));
        l.setDeductionFailTotal(rs.getInt("deduction_fail_total"));
        l.setLastDeductionFailAt(toLocalDateTime(rs.getTimestamp("last_deduction_fail_at")));
        l.setLastDeductionFailCode(rs.getString("last_deduction_fail_code"));
        l.setDefaultFlag(rs.getBoolean("default_flag"));
        l.setDefaultedAt(toLocalDateTime(rs.getTimestamp("defaulted_at")));
        l.setDefaultReasonCode(rs.getString("default_reason_code"));
        l.setDefaultCuredAt(toLocalDateTime(rs.getTimestamp("default_cured_at")));
        l.setVersion(rs.getInt("version"));
        l.setUpdatedAt(toLocalDateTime(rs.getTimestamp("updated_at")));
        return l;
    };

    private static final RowMapper<RepaymentPlan> PLAN_MAPPER = (rs, i) -> {
        RepaymentPlan p = new RepaymentPlan();
        p.setId(rs.getLong("id"));
        p.setLoanId(rs.getLong("loan_id"));
        p.setPeriodNo(rs.getInt("period_no"));
        p.setDueDate(toLocalDate(rs.getDate("due_date")));
        p.setPrincipalDue(rs.getLong("principal_due"));
        p.setInterestDue(rs.getLong("interest_due"));
        p.setTotalDue(rs.getLong("total_due"));
        p.setPrincipalPaid(rs.getLong("principal_paid"));
        p.setInterestPaid(rs.getLong("interest_paid"));
        p.setPaidAt(toLocalDateTime(rs.getTimestamp("paid_at")));
        p.setStatus(rs.getString("status"));
        return p;
    };

    private static final RowMapper<OverdueRecord> OVERDUE_MAPPER = (rs, i) -> {
        OverdueRecord o = new OverdueRecord();
        o.setId(rs.getLong("id"));
        o.setOverdueNo(rs.getString("overdue_no"));
        o.setLoanId(rs.getLong("loan_id"));
        o.setVin(rs.getString("vin"));
        o.setDealerId(rs.getLong("dealer_id"));
        o.setScheduleId(nullableLong(rs, "schedule_id"));
        o.setOverdueDate(toLocalDate(rs.getDate("overdue_date")));
        o.setDpd(rs.getInt("dpd"));
        o.setCurrentStageCode(rs.getString("current_stage_code"));
        o.setMaxStageCode(rs.getString("max_stage_code"));
        o.setStageRuleVersion(rs.getString("stage_rule_version"));
        o.setDayBasisSnapshot(rs.getString("day_basis_snapshot"));
        o.setStageThresholdSnapshot(rs.getString("stage_threshold_snapshot"));
        o.setOutstanding(rs.getLong("outstanding"));
        o.setStatus(rs.getString("status"));
        o.setEscalatedAt(toLocalDateTime(rs.getTimestamp("escalated_at")));
        o.setResolvedAt(toLocalDateTime(rs.getTimestamp("resolved_at")));
        o.setCreatedAt(toLocalDateTime(rs.getTimestamp("created_at")));
        return o;
    };

    private static final RowMapper<DealerRiskProfile> PROFILE_MAPPER = (rs, i) -> {
        DealerRiskProfile p = new DealerRiskProfile();
        p.setId(rs.getLong("id"));
        p.setDealerId(rs.getLong("dealer_id"));
        p.setActiveLoanCount(rs.getInt("active_loan_count"));
        p.setOutstandingTotal(rs.getLong("outstanding_total"));
        p.setCreditUsageRatio(rs.getBigDecimal("credit_usage_ratio"));
        p.setOldestDueDate(toLocalDate(rs.getDate("oldest_due_date")));
        p.setCurrentOverdueCount(rs.getInt("current_overdue_count"));
        p.setCurrentOverdueAmount(rs.getLong("current_overdue_amount"));
        p.setCurrentWorstStage(rs.getString("current_worst_stage"));
        p.setMaxOverdueDays(rs.getInt("max_overdue_days"));
        p.setOverdueCount12m(rs.getInt("overdue_count_12m"));
        p.setAvgOverdueDays12m(rs.getBigDecimal("avg_overdue_days_12m"));
        p.setWorstStage12m(rs.getString("worst_stage_12m"));
        p.setDefaultsLast12m(rs.getInt("defaults_last_12m"));
        p.setDefaultsTotal(rs.getInt("defaults_total"));
        p.setDefaultAmount12m(rs.getLong("default_amount_12m"));
        p.setLastDefaultAt(toLocalDateTime(rs.getTimestamp("last_default_at")));
        p.setDaysSinceLastDefault((Integer) rs.getObject("days_since_last_default"));
        p.setDeductionFailCount30d(rs.getInt("deduction_fail_count_30d"));
        p.setDeductionFailRate30d(rs.getBigDecimal("deduction_fail_rate_30d"));
        p.setDeductionFailStreakMax(rs.getInt("deduction_fail_streak_max"));
        p.setRiskTier(rs.getString("risk_tier"));
        p.setTierReason(rs.getString("tier_reason"));
        p.setTierRuleCode(rs.getString("tier_rule_code"));
        p.setTierRuleVersion(rs.getString("tier_rule_version"));
        p.setScoredAt(toLocalDateTime(rs.getTimestamp("scored_at")));
        p.setDataAsOf(toLocalDate(rs.getDate("data_as_of")));
        p.setComputedAt(toLocalDateTime(rs.getTimestamp("computed_at")));
        p.setComputedBy(rs.getString("computed_by"));
        p.setUpdatedAt(toLocalDateTime(rs.getTimestamp("updated_at")));
        return p;
    };

    public JdbcLoanRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按主键查借据详情。 */
    public Loan findById(long id) {
        return jdbc.query("SELECT * FROM loans WHERE id = :id",
                Map.of("id", id), LOAN_MAPPER).stream().findFirst().orElse(null);
    }

    /**
     * 契约端点：按 VIN 查最新一笔未结清借据。
     * 同 VIN 多笔未结清（脏数据）时取 id 最大者，与应用服务的告警日志配合。
     */
    public Loan findActiveByVin(String vin) {
        String sql = "SELECT * FROM loans WHERE vin = :vin AND status IN (" + OPEN_STATUSES
                + ") ORDER BY id DESC LIMIT 1";
        return jdbc.query(sql, Map.of("vin", vin), LOAN_MAPPER).stream().findFirst().orElse(null);
    }

    /** 查全部未结清借据（DPD 批量刷新用）。 */
    public List<Loan> findAllOpen() {
        return jdbc.query("SELECT * FROM loans WHERE status IN (" + OPEN_STATUSES + ") ORDER BY id",
                Map.of(), LOAN_MAPPER);
    }

    /** 按经销商查全部借据（画像全量重算用）。 */
    public List<Loan> findByDealerId(long dealerId) {
        return jdbc.query("SELECT * FROM loans WHERE dealer_id = :dealerId ORDER BY id",
                Map.of("dealerId", dealerId), LOAN_MAPPER);
    }

    /**
     * 借据状态机唯一写入点（带 from 状态乐观条件）。
     * 外部禁止直接 UPDATE loans.status——逾期只能由还款计划未足额触发。
     */
    public int updateStatus(long loanId, String fromStatus, String toStatus) {
        return jdbc.update("UPDATE loans SET status = :toStatus, updated_at = now(), "
                        + "version = version + 1 WHERE id = :id AND status = :fromStatus",
                new MapSqlParameterSource()
                        .addValue("id", loanId)
                        .addValue("fromStatus", fromStatus)
                        .addValue("toStatus", toStatus));
    }

    /** 建据。loan_no 唯一约束保证 outbox 事件重投时不产生第二笔债。 */
    public long insert(Loan l) {
        String sql = "INSERT INTO loans (loan_no, advance_id, vin, dealer_id, credit_line_id, "
                + "purchase_order_id, principal, due_date, maturity_date, status, grace_period_days, "
                + "term_source, principal_outstanding) "
                + "VALUES (:loanNo, :advanceId, :vin, :dealerId, :creditLineId, :purchaseOrderId, "
                + ":principal, :dueDate, :maturityDate, :status, :gracePeriodDays, :termSource, "
                + ":principalOutstanding) "
                + "ON CONFLICT (loan_no) DO NOTHING";
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("loanNo", l.getLoanNo())
                .addValue("advanceId", l.getAdvanceId())
                .addValue("vin", l.getVin())
                .addValue("dealerId", l.getDealerId())
                .addValue("creditLineId", l.getCreditLineId())
                .addValue("purchaseOrderId", l.getPurchaseOrderId())
                .addValue("principal", l.getPrincipal())
                .addValue("dueDate", l.getDueDate())
                .addValue("maturityDate", l.getMaturityDate() != null ? l.getMaturityDate() : l.getDueDate())
                .addValue("status", l.getStatus() == null ? "DRAFT" : l.getStatus())
                .addValue("gracePeriodDays", l.getGracePeriodDays())
                .addValue("termSource", l.getTermSource() == null ? "UNKNOWN" : l.getTermSource())
                .addValue("principalOutstanding", l.getPrincipal() > 0 ? l.getPrincipal() : l.getPrincipalOutstanding());
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(sql, p, keys, new String[]{"id"});
        return keys.getKey() == null ? 0L : keys.getKey().longValue();
    }

    /** DPD job 唯一写入点：当前 DPD / 阶段 / 历史峰值（GREATEST 保证只增不减）。 */
    public int updateDpdSnapshot(long loanId, int dpd, String stageCode, LocalDate overdueSince) {
        String sql = "UPDATE loans SET current_dpd = :dpd, current_stage_code = :stageCode, "
                + "overdue_since = :overdueSince, max_dpd_ever = GREATEST(max_dpd_ever, :dpd), "
                + "current_stage_entered_at = CASE WHEN current_stage_code IS DISTINCT FROM :stageCode "
                + "THEN now() ELSE current_stage_entered_at END, "
                + "updated_at = now(), version = version + 1 WHERE id = :id";
        return jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", loanId)
                .addValue("dpd", dpd)
                .addValue("stageCode", stageCode)
                .addValue("overdueSince", overdueSince));
    }

    /** 状态机留痕。 */
    public int insertStatusHistory(long loanId, String fromStatus, String toStatus,
                                   String reason, String operator) {
        return jdbc.update("INSERT INTO loan_status_history (loan_id, from_status, to_status, reason, operator) "
                        + "VALUES (:loanId, :fromStatus, :toStatus, :reason, :operator)",
                new MapSqlParameterSource()
                        .addValue("loanId", loanId)
                        .addValue("fromStatus", fromStatus)
                        .addValue("toStatus", toStatus)
                        .addValue("reason", reason)
                        .addValue("operator", operator == null ? "system" : operator));
    }

    /** 生成一期还款计划，(loan_id, period_no) 唯一保证重复触发不产生第二期。 */
    public int insertRepaymentPlan(RepaymentPlan p) {
        return jdbc.update("INSERT INTO repayment_plans (loan_id, period_no, due_date, principal_due, "
                        + "interest_due, total_due, status) "
                        + "VALUES (:loanId, :periodNo, :dueDate, :principalDue, :interestDue, :totalDue, :status) "
                        + "ON CONFLICT (loan_id, period_no) DO NOTHING",
                new MapSqlParameterSource()
                        .addValue("loanId", p.getLoanId())
                        .addValue("periodNo", p.getPeriodNo())
                        .addValue("dueDate", p.getDueDate())
                        .addValue("principalDue", p.getPrincipalDue())
                        .addValue("interestDue", p.getInterestDue())
                        .addValue("totalDue", p.getTotalDue())
                        .addValue("status", p.getStatus() == null ? "PENDING" : p.getStatus()));
    }

    public List<RepaymentPlan> findPlansByLoanId(long loanId) {
        return jdbc.query("SELECT * FROM repayment_plans WHERE loan_id = :loanId ORDER BY period_no",
                Map.of("loanId", loanId), PLAN_MAPPER);
    }

    public int countPaidPlans(long loanId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM repayment_plans WHERE loan_id = :loanId "
                        + "AND status = 'PAID'", Map.of("loanId", loanId), Long.class);
        return n == null ? 0 : n.intValue();
    }

    /**
     * 逾期记录升档：uk_overdue_loan 保证一笔借据一条记录。
     * 已存在时只更新当前阶段/DPD/峰值阶段，绝不插新记录（否则催收工单重复派发）。
     */
    public int upsertOverdueRecord(OverdueRecord o) {
        return jdbc.update("INSERT INTO overdue_records (overdue_no, loan_id, vin, dealer_id, schedule_id, "
                        + "overdue_date, dpd, current_stage_code, max_stage_code, stage_rule_version, "
                        + "day_basis_snapshot, stage_threshold_snapshot, outstanding, status) "
                        + "VALUES (:overdueNo, :loanId, :vin, :dealerId, :scheduleId, :overdueDate, :dpd, "
                        + ":stageCode, :stageCode, :ruleVersion, :dayBasis, :snapshot, :outstanding, 'OPEN') "
                        + "ON CONFLICT (loan_id) DO UPDATE SET dpd = EXCLUDED.dpd, "
                        + "current_stage_code = EXCLUDED.current_stage_code, "
                        + "max_stage_code = GREATEST(overdue_records.max_stage_code, EXCLUDED.max_stage_code), "
                        + "stage_rule_version = EXCLUDED.stage_rule_version, "
                        + "day_basis_snapshot = EXCLUDED.day_basis_snapshot, "
                        + "stage_threshold_snapshot = EXCLUDED.stage_threshold_snapshot, "
                        + "outstanding = EXCLUDED.outstanding, status = 'OPEN'",
                overdueParams(o));
    }

    public OverdueRecord findOverdueByLoanId(long loanId) {
        return jdbc.query("SELECT * FROM overdue_records WHERE loan_id = :loanId",
                Map.of("loanId", loanId), OVERDUE_MAPPER).stream().findFirst().orElse(null);
    }

    /** 画像全量 upsert：不做增量 patch，漏跑一次也能自愈。 */
    public int upsertProfile(DealerRiskProfile p) {
        String cols = "dealer_id, active_loan_count, outstanding_total, oldest_due_date, "
                + "current_overdue_count, current_overdue_amount, current_worst_stage, max_overdue_days, "
                + "overdue_count_12m, worst_stage_12m, defaults_last_12m, defaults_total, "
                + "default_amount_12m, last_default_at, days_since_last_default, "
                + "deduction_fail_count_30d, deduction_fail_rate_30d, deduction_fail_streak_max, "
                + "risk_tier, tier_reason, data_as_of, computed_by";
        String sql = "INSERT INTO dealer_risk_profile (" + cols + ") VALUES (:dealerId, :activeLoanCount, "
                + ":outstandingTotal, :oldestDueDate, :currentOverdueCount, :currentOverdueAmount, "
                + ":currentWorstStage, :maxOverdueDays, :overdueCount12m, :worstStage12m, "
                + ":defaultsLast12m, :defaultsTotal, :defaultAmount12m, :lastDefaultAt, "
                + ":daysSinceLastDefault, :deductionFailCount30d, :deductionFailRate30d, "
                + ":deductionFailStreakMax, :riskTier, :tierReason, :dataAsOf, :computedBy) "
                + "ON CONFLICT (dealer_id) DO UPDATE SET "
                + "active_loan_count = EXCLUDED.active_loan_count, "
                + "outstanding_total = EXCLUDED.outstanding_total, "
                + "oldest_due_date = EXCLUDED.oldest_due_date, "
                + "current_overdue_count = EXCLUDED.current_overdue_count, "
                + "current_overdue_amount = EXCLUDED.current_overdue_amount, "
                + "current_worst_stage = EXCLUDED.current_worst_stage, "
                + "max_overdue_days = EXCLUDED.max_overdue_days, "
                + "overdue_count_12m = EXCLUDED.overdue_count_12m, "
                + "worst_stage_12m = EXCLUDED.worst_stage_12m, "
                + "defaults_last_12m = EXCLUDED.defaults_last_12m, "
                + "defaults_total = EXCLUDED.defaults_total, "
                + "default_amount_12m = EXCLUDED.default_amount_12m, "
                + "last_default_at = EXCLUDED.last_default_at, "
                + "days_since_last_default = EXCLUDED.days_since_last_default, "
                + "deduction_fail_count_30d = EXCLUDED.deduction_fail_count_30d, "
                + "deduction_fail_rate_30d = EXCLUDED.deduction_fail_rate_30d, "
                + "deduction_fail_streak_max = EXCLUDED.deduction_fail_streak_max, "
                + "risk_tier = EXCLUDED.risk_tier, tier_reason = EXCLUDED.tier_reason, "
                + "data_as_of = EXCLUDED.data_as_of, computed_by = EXCLUDED.computed_by, "
                + "computed_at = now(), updated_at = now()";
        return jdbc.update(sql, new MapSqlParameterSource()
                .addValue("dealerId", p.getDealerId())
                .addValue("activeLoanCount", p.getActiveLoanCount())
                .addValue("outstandingTotal", p.getOutstandingTotal())
                .addValue("oldestDueDate", p.getOldestDueDate())
                .addValue("currentOverdueCount", p.getCurrentOverdueCount())
                .addValue("currentOverdueAmount", p.getCurrentOverdueAmount())
                .addValue("currentWorstStage", p.getCurrentWorstStage())
                .addValue("maxOverdueDays", p.getMaxOverdueDays())
                .addValue("overdueCount12m", p.getOverdueCount12m())
                .addValue("worstStage12m", p.getWorstStage12m())
                .addValue("defaultsLast12m", p.getDefaultsLast12m())
                .addValue("defaultsTotal", p.getDefaultsTotal())
                .addValue("defaultAmount12m", p.getDefaultAmount12m())
                .addValue("lastDefaultAt", p.getLastDefaultAt())
                .addValue("daysSinceLastDefault", p.getDaysSinceLastDefault())
                .addValue("deductionFailCount30d", p.getDeductionFailCount30d())
                .addValue("deductionFailRate30d", p.getDeductionFailRate30d())
                .addValue("deductionFailStreakMax", p.getDeductionFailStreakMax())
                .addValue("riskTier", p.getRiskTier())
                .addValue("tierReason", p.getTierReason())
                .addValue("dataAsOf", p.getDataAsOf())
                .addValue("computedBy", p.getComputedBy()));
    }

    public DealerRiskProfile findProfileByDealerId(long dealerId) {
        return jdbc.query("SELECT * FROM dealer_risk_profile WHERE dealer_id = :dealerId",
                Map.of("dealerId", dealerId), PROFILE_MAPPER).stream().findFirst().orElse(null);
    }

    private static MapSqlParameterSource overdueParams(OverdueRecord o) {
        return new MapSqlParameterSource()
                .addValue("overdueNo", o.getOverdueNo())
                .addValue("loanId", o.getLoanId())
                .addValue("vin", o.getVin())
                .addValue("dealerId", o.getDealerId())
                .addValue("scheduleId", o.getScheduleId())
                .addValue("overdueDate", o.getOverdueDate())
                .addValue("dpd", o.getDpd())
                .addValue("stageCode", o.getCurrentStageCode())
                .addValue("ruleVersion", o.getStageRuleVersion())
                .addValue("dayBasis", o.getDayBasisSnapshot())
                .addValue("snapshot", o.getStageThresholdSnapshot())
                .addValue("outstanding", o.getOutstanding());
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Object v = rs.getObject(column);
        return v == null ? null : ((Number) v).longValue();
    }

    private static LocalDate toLocalDate(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
