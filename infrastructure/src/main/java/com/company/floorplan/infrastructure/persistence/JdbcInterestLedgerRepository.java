package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.InterestEntry;
import com.company.floorplan.domain.repository.InterestLedgerRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * 计息流水仓储实现（基础设施层）。
 *
 * <p><b>本类是"批处理可重跑"的基石</b>：计息作业最怕重复计息（金额翻倍），
 * 这里通过数据库唯一约束 {@code (vin, biz_date)} +
 * {@code INSERT ... ON CONFLICT DO NOTHING} 实现幂等写入，
 * 无论作业被触发多少次，同一台车同一业务日只会有一条流水。</p>
 *
 * <p><b>结清以流水汇总为准</b>：{@link #sumInterestByVin(String)} 提供应收利息，
 * 还款校验用它而不是重新计算，避免"重算结果与流水不一致"的对账纠纷。</p>
 */
@Repository
public class JdbcInterestLedgerRepository implements InterestLedgerRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<InterestEntry> MAPPER = (rs, i) -> {
        InterestEntry e = new InterestEntry();
        e.setId(rs.getLong("id"));
        e.setVin(rs.getString("vin"));
        Date d = rs.getDate("biz_date");
        e.setBizDate(d == null ? null : d.toLocalDate());
        e.setPrincipalBalance(rs.getLong("principal_balance"));
        e.setDailyInterest(rs.getLong("daily_interest"));
        e.setRateSnapshot(rs.getBigDecimal("rate_snapshot"));
        return e;
    };

    public JdbcInterestLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean exists(String vin, LocalDate bizDate) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interest_ledger WHERE vin = ? AND biz_date = ?", Integer.class, vin, bizDate);
        return count != null && count > 0;
    }

    @Override
    public InterestEntry saveIfAbsent(InterestEntry entry) {
        // 幂等：唯一约束 (vin, biz_date) 保证重复执行不产生重复计息
        jdbc.update("INSERT INTO interest_ledger (vin, biz_date, principal_balance, daily_interest, rate_snapshot) "
                        + "VALUES (?,?,?,?,?) ON CONFLICT (vin, biz_date) DO NOTHING",
                entry.getVin(), entry.getBizDate(), entry.getPrincipalBalance(),
                entry.getDailyInterest(), entry.getRateSnapshot());
        return entry;
    }

    @Override
    public long sumInterestByVin(String vin) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(daily_interest),0) FROM interest_ledger WHERE vin = ?", Long.class, vin);
        return sum == null ? 0L : sum;
    }

    @Override
    public List<InterestEntry> findByVin(String vin) {
        return jdbc.query("SELECT * FROM interest_ledger WHERE vin = ? ORDER BY biz_date", MAPPER, vin);
    }

    @Override
    public int archiveBefore(LocalDate cutoff) {
        // 先复制到历史表（按主键去重，重跑不报错），再从主表删除
        jdbc.update("INSERT INTO interest_ledger_archive SELECT * FROM interest_ledger WHERE biz_date < ? "
                + "ON CONFLICT (id) DO NOTHING", cutoff);
        return jdbc.update("DELETE FROM interest_ledger WHERE biz_date < ?", cutoff);
    }
}
