package com.company.floorplan.bank.service;

import com.company.floorplan.bank.model.PaymentMatch;

/** 单笔流水的匹配处理结果。 */
public record MatchRunResult(
        String accountRef,
        String transactionId,
        PaymentMatch match,
        String rule,
        String score,
        boolean matched,
        boolean released
) {
}
