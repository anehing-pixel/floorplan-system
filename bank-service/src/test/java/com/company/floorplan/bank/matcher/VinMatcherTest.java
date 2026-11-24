package com.company.floorplan.bank.matcher;

import com.company.floorplan.bank.client.LoanSnapshot;
import com.company.floorplan.bank.model.MatchStatus;
import com.company.floorplan.domain.model.PaymentTransaction;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VIN 匹配器边界单测：精确命中 / 一流水多借据 / 无法匹配 / 纯函数性。 */
class VinMatcherTest {

    private static LoanSnapshot loan(long id, String vin, long outstanding, String status) {
        return new LoanSnapshot(id, "LN-" + id, id + 100, vin, 42L, 34L, outstanding,
                LocalDate.of(2026, 12, 21), status, null, outstanding, 0L, 0L, 0L);
    }

    private static PaymentTransaction tx(String reference, long amount) {
        PaymentTransaction t = new PaymentTransaction();
        t.setAccountRef("ACCT-1");
        t.setTransactionId("TX-1");
        t.setReference(reference);
        t.setAmount(amount);
        t.setMatchStatus("UNMATCHED");
        return t;
    }

    @Test
    void extractVins_nullBlankAndPureDigits_returnEmpty() {
        assertTrue(VinMatcher.extractVins(null).isEmpty());
        assertTrue(VinMatcher.extractVins("   ").isEmpty());
        assertTrue(VinMatcher.extractVins("123456789012").isEmpty(), "纯数字订单号/金额不得当 VIN");
    }

    @Test
    void extractVins_alphaNumericToken_deduplicated() {
        List<String> vins = VinMatcher.extractVins("車代 COLA017481 / COLA017481 手数料");
        assertEquals(List.of("COLA017481"), vins);
    }

    @Test
    void match_vinExactHit_autoMatchedWith95() {
        MatchDecision d = VinMatcher.match(tx("支払 COLA017481", 1_200_000L),
                List.of(loan(1L, "COLA017481", 1_200_000L, "ACTIVE")));
        assertTrue(d.isMatched());
        assertEquals(MatchStatus.AUTO_MATCHED, d.status());
        assertEquals("COLA017481", d.vin());
        assertEquals("VIN_MATCH", d.rule());
        assertEquals(95, d.score().intValue());
    }

    @Test
    void match_oneFlowTwoVins_ambiguousGoesManual() {
        MatchDecision d = VinMatcher.match(tx("COLA017481 COLA017482", 2_400_000L), List.of(
                loan(1L, "COLA017481", 1_200_000L, "ACTIVE"),
                loan(2L, "COLA017482", 1_200_000L, "ACTIVE")));
        assertFalse(d.isMatched(), "两个不同 VIN 同为 95 分，差距 0，禁止自动挑一个");
        assertEquals(MatchStatus.UNMATCHED, d.status());
        assertEquals(2, d.candidateVins().size());
        assertTrue(d.reason().contains("多候选分数接近"));
    }

    @Test
    void match_sameVinTwoActiveLoans_takesNewestIdAndWarns() {
        MatchDecision d = VinMatcher.match(tx("COLA017481", 1_200_000L), List.of(
                loan(1L, "COLA017481", 1_200_000L, "ACTIVE"),
                loan(9L, "COLA017481", 1_200_000L, "OVERDUE")));
        assertTrue(d.isMatched());
        assertEquals(9L, d.loan().id(), "契约 §3：同 VIN 多笔未结清取 id DESC 最新");
        assertTrue(d.reason().contains("同VIN多笔未结清"));
    }

    @Test
    void match_noCandidate_unmatchedAsNoActiveDebt() {
        MatchDecision d = VinMatcher.match(tx("COLA999999", 100L), List.of());
        assertFalse(d.isMatched());
        assertTrue(d.reason().contains("无在贷债务"), "契约 404 = 无在贷债务，不是系统错误");
    }

    @Test
    void match_amountOnly_score60_isCandidateNotAutoMatch() {
        MatchDecision d = VinMatcher.match(tx("支払い", 1_200_000L),
                List.of(loan(1L, "COLA017481", 1_200_000L, "ACTIVE")));
        assertFalse(d.isMatched(), "金额单独命中只有 60 分，不足以自动绑定");
        assertTrue(d.reason().contains("最高置信度 60"));
    }

    @Test
    void match_reversalTransaction_notCandidate() {
        MatchDecision d = VinMatcher.match(tx("COLA017481", -500L),
                List.of(loan(1L, "COLA017481", 1_200_000L, "ACTIVE")));
        assertFalse(d.isMatched());
        assertTrue(d.reason().contains("冲正"));
    }

    @Test
    void match_settledLoanFiltered_out() {
        MatchDecision d = VinMatcher.match(tx("COLA017481", 1_200_000L),
                List.of(loan(1L, "COLA017481", 1_200_000L, "SETTLED")));
        assertFalse(d.isMatched());
        assertTrue(d.reason().contains("非未结清"));
    }

    @Test
    void match_pureFunction_sameInputSameOutput() {
        PaymentTransaction t = tx("COLA017481", 1_200_000L);
        List<LoanSnapshot> loans = List.of(loan(1L, "COLA017481", 1_200_000L, "ACTIVE"));
        assertEquals(VinMatcher.match(t, loans), VinMatcher.match(t, loans));
    }
}
