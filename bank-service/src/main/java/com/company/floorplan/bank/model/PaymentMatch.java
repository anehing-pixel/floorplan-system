package com.company.floorplan.bank.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 匹配结论（V17 payment_matches 一行的内存表达）。
 *
 * <p>用不可变 record：匹配是一次判定，人工改判时生成新结论而非原地改事实。
 * 幂等由 store 的 {@code (accountRef, transactionId)} 唯一键保证。</p>
 *
 * <p>字段与 V16 借据契约对齐：{@code loanId}=loans.id、{@code advanceId}=loans.advance_id
 * （可空）、{@code dealerId}=loans.dealer_id、{@code creditLineId}=loans.credit_line_id。</p>
 */
public record PaymentMatch(
        Long id,
        String accountRef,
        String transactionId,
        String vin,
        Long loanId,
        Long advanceId,
        Long dealerId,
        Long creditLineId,
        MatchStatus matchStatus,
        String matchRule,
        BigDecimal matchScore,
        long matchedAmount,
        Instant matchedAt,
        String matchedBy,
        String decisionReason,
        String candidateSummary
) {

    /** 幂等键：与 V15 uk_payment_txn / V17 uk_payment_match_txn 同口径。 */
    public String key() {
        return accountRef + "|" + transactionId;
    }

    /** 无法自动匹配时的挂起结论（保留线索供人工裁决）。 */
    public static PaymentMatch unmatched(String accountRef, String transactionId, long amount,
                                         String reason, String candidates) {
        return new PaymentMatch(null, accountRef, transactionId,
                null, null, null, null, null,
                MatchStatus.UNMATCHED, null, null, amount,
                null, null, reason, candidates);
    }

    /** 人工判定为非本业务。 */
    public static PaymentMatch ignored(String accountRef, String transactionId, long amount,
                                       String reason, String operator) {
        return new PaymentMatch(null, accountRef, transactionId,
                null, null, null, null, null,
                MatchStatus.IGNORED, "MANUAL_IGNORE", null, amount,
                Instant.now(), operator, reason, null);
    }

    /** 在已有结论上落定匹配结果，返回新实例（不改原对象）。 */
    public PaymentMatch withDecision(MatchStatus status, String vin, Long loanId, Long advanceId,
                                     Long dealerId, Long creditLineId, String rule, BigDecimal score,
                                     Instant at, String operator, String reason) {
        return new PaymentMatch(id, accountRef, transactionId,
                vin, loanId, advanceId, dealerId, creditLineId,
                status, rule, score, matchedAmount,
                at, operator, reason, candidateSummary);
    }

    /** 是否可进入释放编排：只有自动/人工匹配成功才允许解押与回补。 */
    public boolean isReleasable() {
        return matchStatus == MatchStatus.AUTO_MATCHED || matchStatus == MatchStatus.MANUAL_MATCHED;
    }
}
