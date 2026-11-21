package com.company.floorplan.bank.model;

import java.time.Instant;

/**
 * 额度回补流水（V17 credit_restores 一行的内存表达）。
 *
 * <p>额度写入仍在授信侧行锁事务内（CreditLineClient 契约只提供只读查询），
 * 本记录是回补动作的可审计凭证：哪笔流水、哪个经销商、回补多少、谁触发。</p>
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
