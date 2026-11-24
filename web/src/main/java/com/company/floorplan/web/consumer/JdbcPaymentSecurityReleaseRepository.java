package com.company.floorplan.web.consumer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code payment_security_releases}（V81）的写入端口。
 *
 * <p><b>认领先于写入</b>：{@link #claim} 用 {@code INSERT ... ON CONFLICT (payment_ref) DO NOTHING}
 * 在事务里先占住 paymentRef。返回 false 说明同一笔回款已经解除过担保（无论事件 id 是否相同），
 * 消费方必须直接返回。认领行与车辆/台账更新在同一本地事务：回滚时认领一起回滚，重试可重新认领。</p>
 */
@Repository
public class JdbcPaymentSecurityReleaseRepository {

    private final JdbcTemplate jdbc;

    public JdbcPaymentSecurityReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true = 本次首次认领；false = paymentRef 已存在，必须跳过 */
    public boolean claim(String paymentRef, long eventId, String traceId, String vin, String securityBefore) {
        int rows = jdbc.update(
                "INSERT INTO payment_security_releases "
                        + "(payment_ref, event_id, trace_id, vin, security_before, status) "
                        + "VALUES (?,?,?,?,?,'RELEASED') ON CONFLICT (payment_ref) DO NOTHING",
                paymentRef, eventId, traceId, vin, securityBefore);
        return rows == 1;
    }

    /** 回填释放结果（必须在同一事务内调用）。 */
    public void complete(String paymentRef, String securityAfter, boolean ledgerReleased,
                         boolean cancellationRequested, String status, String note) {
        jdbc.update("UPDATE payment_security_releases SET security_after=?, ledger_released=?, "
                        + "cancellation_requested=?, status=?, note=? WHERE payment_ref=?",
                securityAfter, ledgerReleased, cancellationRequested, status, note, paymentRef);
    }
}
