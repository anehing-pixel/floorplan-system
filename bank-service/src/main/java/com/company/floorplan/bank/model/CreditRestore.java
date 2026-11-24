package com.company.floorplan.bank.model;

import java.time.Instant;

/**
 * 额度回补流水（V17 credit_restores 一行的内存表达）。
 *
 * <p>额度写入在 credit-service 自己的行锁事务内（bank 不允许跨服务直写 credit_lines），
 * 本记录是 bank 侧的「回补请求」凭证：哪笔流水、哪个额度线、回补多少、谁触发。
 * 真实回补由 PAYMENT_MATCHED 的消费方完成，bank 侧不再写 availableBefore/availableAfter。</p>
 */
public record CreditRestore(
        Long id,
        Long matchId,
        String transactionId,
        Long dealerId,
        Long creditLineId,
        long restoreAmount,
        Long availableBefore,
        Long availableAfter,
        RestoreStatus status,
        String idempotencyKey,
        Instant restoredAt,
        String failedReason,
        String operator
) {

    public static CreditRestore pending(Long matchId, String transactionId, Long dealerId,
                                        Long creditLineId, long restoreAmount, String operator) {
        return new CreditRestore(null, matchId, transactionId, dealerId, creditLineId, restoreAmount,
                null, null, RestoreStatus.PENDING, transactionId, null, null, operator);
    }

    public CreditRestore withBefore(Long availableBefore) {
        return new CreditRestore(id, matchId, transactionId, dealerId, creditLineId, restoreAmount,
                availableBefore, availableAfter, status, idempotencyKey, restoredAt, failedReason, operator);
    }

    public CreditRestore markedRestored(Instant at, Long availableAfter) {
        return new CreditRestore(id, matchId, transactionId, dealerId, creditLineId, restoreAmount,
                availableBefore, availableAfter, RestoreStatus.RESTORED, idempotencyKey, at, null, operator);
    }

    public CreditRestore markedFailed(String reason) {
        return new CreditRestore(id, matchId, transactionId, dealerId, creditLineId, restoreAmount,
                availableBefore, availableAfter, RestoreStatus.FAILED, idempotencyKey, null, reason, operator);
    }
}
