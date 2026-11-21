package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.Repayment;
import com.company.floorplan.domain.repository.RepaymentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 还款核销记录仓储实现。
 *
 * <p><b>幂等</b>：与垫款同理，{@code idempotency_key} 唯一索引兜底，
 * 防止网络重试或重复提交造成"一笔还款记两次"。</p>
 *
 * <p><b>金额口径</b>：同时记录本金（principal）与利息（interest），
 * 便于后续对账、财务入账与利息收入的拆分统计。</p>
 */
@Repository
public class JdbcRepaymentRepository implements RepaymentRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Repayment> MAPPER = (rs, i) -> {
        Repayment r = new Repayment();
        r.setId(rs.getLong("id"));
        r.setRepaymentNo(rs.getString("repayment_no"));
        r.setVin(rs.getString("vin"));
        r.setPrincipal(rs.getLong("principal"));
        r.setInterest(rs.getLong("interest"));
        r.setTotalAmount(rs.getLong("total_amount"));
        Timestamp settledAt = rs.getTimestamp("settled_at");
        r.setSettledAt(settledAt == null ? null : settledAt.toLocalDateTime());
        Object operator = rs.getObject("operator_id");
        r.setOperatorId(operator == null ? null : ((Number) operator).longValue());
        r.setIdempotencyKey(rs.getString("idempotency_key"));
        r.setRepaymentType(rs.getString("repayment_type"));
        return r;
    };

    public JdbcRepaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Repayment> findByIdempotencyKey(String key) {
        return jdbc.query("SELECT * FROM repayments WHERE idempotency_key = ?", MAPPER, key).stream().findFirst();
    }

    @Override
    public List<Repayment> findByVin(String vin) {
        return jdbc.query("SELECT * FROM repayments WHERE vin = ? ORDER BY id", MAPPER, vin);
    }

    @Override
    public Repayment save(Repayment repayment) {
        if (repayment.getId() == null) {
            if (repayment.getRepaymentNo() == null) {
                repayment.setRepaymentNo("REP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            }
            Long id = jdbc.queryForObject(
                    "INSERT INTO repayments (repayment_no, vin, principal, interest, total_amount, settled_at, operator_id, idempotency_key, repayment_type) "
                            + "VALUES (?,?,?,?,?,?,?,?,?) RETURNING id",
                    Long.class,
                    repayment.getRepaymentNo(), repayment.getVin(), repayment.getPrincipal(),
                    repayment.getInterest(), repayment.getTotalAmount(),
                    repayment.getSettledAt() == null ? null : Timestamp.valueOf(repayment.getSettledAt()),
                    repayment.getOperatorId(), repayment.getIdempotencyKey(),
                    repayment.getRepaymentType() == null ? "FULL" : repayment.getRepaymentType());
            repayment.setId(id);
        }
        return repayment;
    }
}
