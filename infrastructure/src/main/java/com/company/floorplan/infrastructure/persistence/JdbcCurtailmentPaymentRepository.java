package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.repository.CurtailmentPaymentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 库龄递减还款核销流水仓储实现。
 *
 * <p>主键为自增；{@code idempotency_key} 唯一约束保证重复提交（网络重试）不会重复入账
 * （{@code ON CONFLICT DO NOTHING}）。服务层在扣款<b>之前</b>先用
 * {@link #findByIdempotencyKey(String)} 判断是否已入账，命中则直接重放首次结果——
 * "先查后扣"才是完整幂等，仅靠唯一约束拦截会晚一步（本金已扣、流水被吞）。</p>
 */
@Repository
public class JdbcCurtailmentPaymentRepository implements CurtailmentPaymentRepository {

    private final JdbcTemplate jdbc;

    public JdbcCurtailmentPaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(String vin, int periodNo, long amount, String idempotencyKey, String operator) {
        jdbc.update("INSERT INTO curtailment_payments (vin, period_no, amount, idempotency_key, operator) "
                        + "VALUES (?,?,?,?,?) ON CONFLICT (idempotency_key) DO NOTHING",
                vin, periodNo, amount, idempotencyKey, operator);
    }

    @Override
    public Optional<long[]> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.query(
                        "SELECT period_no, amount FROM curtailment_payments WHERE idempotency_key = ?",
                        rs -> {
                            if (rs.next()) {
                                return Optional.of(new long[]{rs.getInt("period_no"), rs.getLong("amount")});
                            }
                            return Optional.<long[]>empty();
                        },
                        idempotencyKey);
    }
}
