package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.AgingPlan;
import com.company.floorplan.domain.repository.AgingPlanRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 库龄递减还款计划仓储实现。
 *
 * <p><b>业务背景</b>：车辆在库超过约定天数（预设 90 天）仍未售出，
 * 强制车商分期偿还部分本金，防止车辆贬值到跌破垫款余额
 * （即"库龄递减 Curtailment"机制）。</p>
 *
 * <p><b>规则版本化</b>：计划上记录 {@code rule_version}，
 * 业务规则调整时不影响存量计划——避免"改规则导致历史数据重算不一致"。</p>
 */
@Repository
public class JdbcAgingPlanRepository implements AgingPlanRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<AgingPlan> MAPPER = (rs, i) -> {
        AgingPlan p = new AgingPlan();
        p.setId(rs.getLong("id"));
        p.setVin(rs.getString("vin"));
        p.setRuleVersion(rs.getString("rule_version"));
        p.setFirstTriggerDate(toDate(rs.getDate("first_trigger_date")));
        Object periodDays = rs.getObject("period_days");
        p.setPeriodDays(periodDays == null ? null : ((Number) periodDays).intValue());
        p.setReduceRatio(rs.getBigDecimal("reduce_ratio"));
        p.setReduceAmount(rs.getLong("reduce_amount"));
        Object paidPeriods = rs.getObject("paid_periods");
        p.setPaidPeriods(paidPeriods == null ? 0 : ((Number) paidPeriods).intValue());
        p.setNextDueDate(toDate(rs.getDate("next_due_date")));
        p.setStatus(rs.getString("status"));
        return p;
    };

    private static LocalDate toDate(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    public JdbcAgingPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AgingPlan> findByVin(String vin) {
        return jdbc.query("SELECT * FROM aging_plans WHERE vin = ?", MAPPER, vin).stream().findFirst();
    }

    @Override
    public List<AgingPlan> findDueOn(LocalDate date) {
        return jdbc.query("SELECT * FROM aging_plans WHERE status = 'ACTIVE' AND next_due_date IS NOT NULL "
                + "AND next_due_date <= ? ORDER BY next_due_date", MAPPER, date);
    }

    @Override
    public AgingPlan save(AgingPlan plan) {
        jdbc.update("INSERT INTO aging_plans (vin, rule_version, first_trigger_date, period_days, reduce_ratio, "
                        + "reduce_amount, paid_periods, next_due_date, status) VALUES (?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT (vin) DO UPDATE SET period_days=EXCLUDED.period_days, "
                        + "reduce_amount=EXCLUDED.reduce_amount, paid_periods=EXCLUDED.paid_periods, "
                        + "next_due_date=EXCLUDED.next_due_date, status=EXCLUDED.status",
                plan.getVin(), plan.getRuleVersion(), plan.getFirstTriggerDate(), plan.getPeriodDays(),
                plan.getReduceRatio(), plan.getReduceAmount(), plan.getPaidPeriods(), plan.getNextDueDate(),
                plan.getStatus() == null ? "ACTIVE" : plan.getStatus());
        return plan;
    }
}
