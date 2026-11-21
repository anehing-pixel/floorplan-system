package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.PaymentTransaction;
import com.company.floorplan.domain.repository.PaymentTransactionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

/**
 * 银行回款流水仓储实现。
 *
 * <p><b>核心是 {@link #insertIfAbsent}</b>：幂等由数据库唯一约束完成，
 * 应用层不查重。这里有三个理由，任何一个都足以否掉"先查后插"：</p>
 * <ol>
 *   <li><b>先查后插在并发下必然穿</b>：两个实例同时查"不存在"，然后都插——
 *       查询和插入之间没有原子性，加多少判断都没用。</li>
 *   <li><b>查询成本白付</b>：重复拉取是常态，先查意味着每笔都要多一次索引查找；
 *       而 {@code ON CONFLICT DO NOTHING} 让数据库在插入路径上一次完成。</li>
 *   <li><b>影响行数本身就是答案</b>：1=新入账，0=重复。不需要额外查询来判断结果。</li>
 * </ol>
 *
 * <p><b>为什么不做批量插入</b>：批量会引入"一批中有一条失败怎么办"的语义问题。
 * 逐行插入时，某条脏数据只丢自己，同批其他流水照常入账——
 * 对采集作业来说，"尽可能多记事实"比"要么全成要么全败"更符合业务。</p>
 */
@Repository
public class JdbcPaymentTransactionRepository implements PaymentTransactionRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<PaymentTransaction> MAPPER = (rs, i) -> {
        PaymentTransaction t = new PaymentTransaction();
        t.setId(rs.getLong("id"));
        t.setTransactionId(rs.getString("transaction_id"));
        t.setAccountRef(rs.getString("account_ref"));
        t.setValueDate(toLocalDate(rs.getDate("value_date")));
        t.setAmount(rs.getLong("amount"));
        t.setPayerName(rs.getString("payer_name"));
        t.setPayerAccount(rs.getString("payer_account"));
        t.setReference(rs.getString("reference"));
        t.setMatchStatus(rs.getString("match_status"));
        t.setMatchedVin(rs.getString("matched_vin"));
        Object advanceId = rs.getObject("matched_advance_id");
        t.setMatchedAdvanceId(advanceId == null ? null : ((Number) advanceId).longValue());
        t.setMatchedAt(toLocalDateTime(rs.getTimestamp("matched_at")));
        t.setMatchedBy(rs.getString("matched_by"));
        t.setMatchRule(rs.getString("match_rule"));
        t.setMatchScore(rs.getBigDecimal("match_score"));
        t.setSource(rs.getString("source"));
        t.setBatchNo(rs.getString("batch_no"));
        t.setRawReference(rs.getString("raw_reference"));
        t.setCollectedAt(toLocalDateTime(rs.getTimestamp("collected_at")));
        return t;
    };

    private static LocalDate toLocalDate(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    private static java.time.LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    public JdbcPaymentTransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@inheritDoc}
     *
     * <p>冲突目标必须精确写成 {@code (account_ref, transaction_id)}——
     * 写成别的组合（比如只写 {@code transaction_id}）会匹配不到唯一约束，
     * PostgreSQL 直接报 "no unique or exclusion constraint matching the ON CONFLICT specification"。
     * 而更危险的是：有人为了"让它跑起来"改成先查后插，把幂等防线拆了。</p>
     */
    @Override
    public boolean insertIfAbsent(PaymentTransaction t) {
        int rows = jdbc.update(
                "INSERT INTO payment_transactions "
                        + "(transaction_id, account_ref, value_date, amount, payer_name, payer_account, "
                        + " reference, match_status, source, batch_no, raw_reference) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT (account_ref, transaction_id) DO NOTHING",
                t.getTransactionId(), t.getAccountRef(), t.getValueDate(), t.getAmount(),
                t.getPayerName(), t.getPayerAccount(), t.getReference(),
                t.getMatchStatus() == null ? "UNMATCHED" : t.getMatchStatus(),
                t.getSource(), t.getBatchNo(), t.getRawReference());
        return rows == 1;
    }

    @Override
    public List<PaymentTransaction> findByBatchNo(String batchNo) {
        return jdbc.query("SELECT * FROM payment_transactions WHERE batch_no = ? ORDER BY id",
                MAPPER, batchNo);
    }

    @Override
    public List<PaymentTransaction> findUnmatched() {
        return jdbc.query("SELECT * FROM payment_transactions WHERE match_status = 'UNMATCHED' "
                + "AND amount > 0 ORDER BY value_date, id", MAPPER);
    }

    @Override
    public List<PaymentTransaction> findByAccountAndDateRange(String accountRef, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT * FROM payment_transactions "
                        + "WHERE account_ref = ? AND value_date BETWEEN ? AND ? ORDER BY value_date, id",
                MAPPER, accountRef, from, to);
    }

    @Override
    public long countByBatchNo(String batchNo) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE batch_no = ?",
                Long.class, batchNo);
        return n == null ? 0L : n;
    }
}
