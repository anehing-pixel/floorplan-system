package com.company.floorplan.bank.service;

import com.company.floorplan.bank.client.LoanSnapshot;
import com.company.floorplan.bank.model.CreditRestore;
import com.company.floorplan.bank.model.LienRelease;
import com.company.floorplan.bank.model.MatchStatus;
import com.company.floorplan.bank.model.PaymentMatch;
import com.company.floorplan.bank.model.ReleaseStatus;
import com.company.floorplan.bank.model.RestoreStatus;
import com.company.floorplan.bank.store.InMemoryPaymentStore;
import com.company.floorplan.common.contract.CreditLineClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 释放编排单测：顺序、幂等、失败留痕。 */
class ReleaseOrchestratorTest {

    private static LoanSnapshot loan() {
        return new LoanSnapshot(10L, "LN-1", 55L, "COLA017481", 42L, 1_200_000L,
                LocalDate.of(2026, 12, 21), "ACTIVE", null, 1_200_000L, 0L, 0L, 0L);
    }

    private static PaymentMatch matchedMatch(InMemoryPaymentStore store) {
        return store.saveMatchIfAbsent(
                PaymentMatch.unmatched("ACCT-1", "TX-1", 1_200_000L, "auto", null)
                        .withDecision(MatchStatus.AUTO_MATCHED, "COLA017481", 10L, 55L, 42L, null,
                                "VIN_MATCH", BigDecimal.valueOf(95), Instant.now(), "system", "auto"));
    }

    /** 手写 fake，代替 CreditLineClient 的 Feign 实现，便于钉死"只读查询发生在回补前"。 */
    private static class StubCredit implements CreditLineClient {
        private final BigDecimal available;
        private final boolean fail;
        private int calls;

        StubCredit(BigDecimal available, boolean fail) {
            this.available = available;
            this.fail = fail;
        }

        @Override
        public BigDecimal getAvailable(Long dealerId) {
            calls++;
            if (fail) {
                throw new IllegalStateException("credit-service down");
            }
            return available;
        }
    }

    @Test
    void orchestrate_runsLienBeforeCredit_andRecordsAuditSteps() {
        InMemoryPaymentStore store = new InMemoryPaymentStore();
        PaymentMatch match = matchedMatch(store);
        StubCredit credit = new StubCredit(BigDecimal.valueOf(7_654_321L), false);

        ReleaseResult result = new ReleaseOrchestrator(store, credit)
                .orchestrate(match, loan(), "alice");

        assertEquals(List.of("LIEN_RELEASE_PENDING", "LIEN_RELEASED",
                "CREDIT_RESTORE_PENDING", "CREDIT_AVAILABLE_QUERY", "CREDIT_RESTORED"), result.steps());
        assertEquals(ReleaseStatus.RELEASED, result.release().status());
        assertEquals("REQUESTED", result.release().cancellationStatus(), "抹消待办只能置 REQUESTED");
        assertEquals("alice", result.release().operator(), "操作人来自令牌，不被请求体覆盖");
        assertEquals(RestoreStatus.RESTORED, result.restore().status());
        assertEquals(Long.valueOf(7_654_321L), result.restore().availableBefore());
        assertEquals(1, credit.calls);
    }

    @Test
    void orchestrate_repeatedSameMatch_isSkippedWithoutDuplicateSideEffects() {
        InMemoryPaymentStore store = new InMemoryPaymentStore();
        PaymentMatch match = matchedMatch(store);
        StubCredit credit = new StubCredit(BigDecimal.TEN, false);
        ReleaseOrchestrator orchestrator = new ReleaseOrchestrator(store, credit);

        ReleaseResult first = orchestrator.orchestrate(match, loan(), "alice");
        ReleaseResult second = orchestrator.orchestrate(match, loan(), "alice");

        assertTrue(second.skipped());
        assertTrue(second.steps().contains("SKIPPED_RELEASE_IDEMPOTENT"));
        assertTrue(second.steps().contains("SKIPPED_RESTORE_IDEMPOTENT"));
        assertEquals(1, store.findAllReleases().size());
        assertEquals(1, store.findAllRestores().size());
        assertEquals(1, credit.calls, "重复触发不得再次查询授信侧，更不得重复回补");
        assertEquals(LienRelease.class, first.release().getClass());
    }

    @Test
    void orchestrate_unmatchedMatch_rejectedWithNoSideEffects() {
        InMemoryPaymentStore store = new InMemoryPaymentStore();
        PaymentMatch pending = store.saveMatchIfAbsent(
                PaymentMatch.unmatched("ACCT-1", "TX-1", 100L, "挂起", null));
        StubCredit credit = new StubCredit(BigDecimal.ZERO, false);

        ReleaseResult result = new ReleaseOrchestrator(store, credit)
                .orchestrate(pending, loan(), "alice");

        assertEquals(List.of("REJECTED_NOT_MATCHED"), result.steps());
        assertNull(result.release());
        assertNull(result.restore());
        assertTrue(store.findAllReleases().isEmpty());
        assertTrue(store.findAllRestores().isEmpty());
        assertEquals(0, credit.calls);
    }

    @Test
    void orchestrate_creditFailure_leavesFailedRestoreForRetry() {
        InMemoryPaymentStore store = new InMemoryPaymentStore();
        PaymentMatch match = matchedMatch(store);

        ReleaseResult result = new ReleaseOrchestrator(store, new StubCredit(BigDecimal.ZERO, true))
                .orchestrate(match, loan(), "alice");

        assertTrue(result.steps().contains("CREDIT_RESTORE_FAILED"));
        assertEquals(RestoreStatus.FAILED, result.restore().status(), "禁止静默失败，必须留 FAILED 凭证");
        assertEquals(ReleaseStatus.RELEASED, result.release().status(),
                "解押已发生；真实实现需单事务回滚或工单补偿回补");
    }
}
