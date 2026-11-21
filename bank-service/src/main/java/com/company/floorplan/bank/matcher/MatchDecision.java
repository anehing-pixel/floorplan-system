package com.company.floorplan.bank.matcher;

import com.company.floorplan.bank.client.LoanSnapshot;
import com.company.floorplan.bank.model.MatchStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * 匹配决策（VinMatcher 的纯输出）。
 *
 * <p>无论命中还是挂起，都带判定理由与候选摘要——设计稿的要求是
 * "事后可解释为什么这么匹配/为什么没匹配"，不是只给一个布尔值。</p>
 */
public record MatchDecision(
        MatchStatus status,
        String vin,
        LoanSnapshot loan,
        String rule,
        BigDecimal score,
        String reason,
        List<String> candidateVins
) {

    public static MatchDecision matched(LoanSnapshot loan, String rule, int score) {
        return new MatchDecision(MatchStatus.AUTO_MATCHED, loan.vin(), loan, rule,
                BigDecimal.valueOf(score), "自动匹配：" + rule + "，置信度 " + score,
                List.of(loan.vin()));
    }

    public static MatchDecision unmatched(String reason, List<String> candidates) {
        return new MatchDecision(MatchStatus.UNMATCHED, null, null, null, null,
                reason, candidates == null ? List.of() : List.copyOf(candidates));
    }

    public boolean isMatched() {
        return status == MatchStatus.AUTO_MATCHED;
    }
}
