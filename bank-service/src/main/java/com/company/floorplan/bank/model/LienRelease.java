package com.company.floorplan.bank.model;

import java.time.Instant;

/**
 * 解押留痕（V17 lien_releases 一行的内存表达）。
 *
 * <p>一次匹配最多一条，幂等锚点 matchId。状态迁移：PENDING → RELEASED / FAILED。</p>
 */
public record LienRelease(
        Long id,
        Long matchId,
        String transactionId,
        String vin,
        Long loanId,
        long releaseAmount,
        ReleaseStatus status,
        String cancellationStatus,
        Instant releasedAt,
        String failedReason,
        String operator
) {

    public static LienRelease pending(Long matchId, String transactionId, String vin, Long loanId,
                                      long releaseAmount, String operator) {
        return new LienRelease(null, matchId, transactionId, vin, loanId, releaseAmount,
                ReleaseStatus.PENDING, "NOT_REQUIRED", null, null, operator);
    }

    /**
     * 标记已解押：担保实体权利消灭，并同时生成抹消待办。
     *
     * <p>日本实务抹消需权利人+登记义务人共同申请，系统只能置 REQUESTED，不能自动 DONE。</p>
     */
    public LienRelease markedReleased(Instant at) {
        return new LienRelease(id, matchId, transactionId, vin, loanId, releaseAmount,
                ReleaseStatus.RELEASED, "REQUESTED", at, null, operator);
    }

    public LienRelease markedFailed(String reason) {
        return new LienRelease(id, matchId, transactionId, vin, loanId, releaseAmount,
                ReleaseStatus.FAILED, cancellationStatus, null, reason, operator);
    }
}
