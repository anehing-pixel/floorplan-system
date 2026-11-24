package com.company.floorplan.credit.consumer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code credit_line_restorations}（V80）的写入端口。
 *
 * <p><b>认领先于写入</b>：{@link #claim} 用 {@code INSERT ... ON CONFLICT (payment_ref) DO NOTHING}
 * 在事务里先占住 paymentRef。返回 false 说明同一笔银行回款已经被处理过（无论事件 id 是否相同），
 * 消费方必须直接返回，不能再改额度。认领行与额度更新在同一个本地事务：事务回滚时认领一起回滚，
 * 下一轮可以重新认领。</p>
 */
@Repository
public class JdbcCreditRestorationRepository {

    private final JdbcTemplate jdbc;

    public JdbcCreditRestorationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 认领一笔回补业务事实。
     *
     * @return true = 本次是首次认领；false = paymentRef 已存在，本次必须跳过
     */
    public boolean claim(String paymentRef, long eventId, String traceId, long creditLineId,
                         Long dealerId, long restoreAmount) {
        int rows = jdbc.update(
                "INSERT INTO credit_line_restorations "
                        + "(payment_ref, event_id, trace_id, credit_line_id, dealer_id, restore_amount, "
                        + " applied_amount, available_before, available_after, status) "
                        + "VALUES (?,?,?,?,?,?,0,0,0,'RESTORED') "
                        + "ON CONFLICT (payment_ref) DO NOTHING",
                paymentRef, eventId, traceId, creditLineId, dealerId, restoreAmount);
        return rows == 1;
    }

    /** 回填回补前后快照与实际释放额（必须在同一事务内调用）。 */
    public void complete(String paymentRef, long availableBefore, long availableAfter,
                         long appliedAmount, String note) {
        jdbc.update("UPDATE credit_line_restorations SET available_before=?, available_after=?, "
                        + "applied_amount=?, note=? WHERE payment_ref=?",
                availableBefore, availableAfter, appliedAmount, note, paymentRef);
    }
}
