package com.company.floorplan.bank.service;

import com.company.floorplan.bank.client.LoanSnapshot;
import com.company.floorplan.bank.model.CreditRestore;
import com.company.floorplan.bank.model.LienRelease;
import com.company.floorplan.bank.model.MatchStatus;
import com.company.floorplan.bank.model.PaymentMatch;
import com.company.floorplan.bank.model.ReleaseStatus;
import com.company.floorplan.bank.model.RestoreStatus;
import com.company.floorplan.bank.store.InMemoryPaymentStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 释放编排单测：顺序、幂等、以及"不冒充已回补"。 */
class ReleaseOrchestratorTest {

    private static LoanSnapshot loan() {
        return new LoanSnapshot(10L, "LN-1", 55L, "COLA017481", 42L, 7L, 1_200_000L,
                LocalDate.of(2026, 12, 21), "ACTIVE", null, 1_200_000L, 0L, 0L, 0L);
    }

    private static PaymentMatch matchedMatch(InMemoryPaymentStore store) {
        return store.saveMatchIfAbsent(
                PaymentMatch.unmatched("ACCT-1", "TX-1", 1_200_000L, "auto", null)
                        .withDecision(MatchStatus.AUTO_MATCHED, "COLA017481", 10L, 55L, 42L, 7L,
                                "VIN_MATCH", BigDecimal.valueOf(95), Instant.now(), "system", "auto"));
    }

    @Test
    void orchestrate_runsLienFirst_thenOnlyRequestsCreditRestore() {
        InMemoryPaymentStore store = new InMemoryPaymentStore();
        PaymentMatch match = matchedMatch(store);

        ReleaseResult result = new ReleaseOrchestrator(store)
                .orchestrate(match, loan(), "alice");

        assertEquals(List.of("LIEN_RELEASE_PENDING", "LIEN_RELEASED",
                "CREDIT_RESTORE_PENDING", "CREDIT_RESTORE_REQUESTED"), result.steps());
        assertEquals(ReleaseStatus.RELEASED, result.release().status());
        assertEquals("REQUESTED", result.release().cancellationStatus(), "抹消待办只能置 REQUESTED");
        assertEquals("alice", result.release().operator(), "操作人来自令牌，不被请求体覆盖");
        // 额度写入在 credit-service 的行锁事务里；bank 侧只登记请求，不得写 RESTORED / availableAfter
        assertEquals(RestoreStatus.PENDING, result.restore().status());
        assertNull(result.restore().availableBefore(), "bank 不再查询/快照授信侧可用额度");
        assertNull(result.restore().availableAfter(), "不得再写 availableAfter = before 冒充已回补");
        assertEquals(Long.valueOf(7L), result.restore().creditLineId(), "回补目标额度线来自借据投影");
    }

    @Test
    void orchestrate_repeatedSameMatch_isSkippedWithoutDuplicateSideEffects() {
        InMemoryPaymentStore store = new InMemoryPaymentStore();
        PaymentMatch match = matchedMatch(store);
        ReleaseOrchestrator orchestrator = new ReleaseOrchestrator(store);

        ReleaseResult first = orchestrator.orchestrate(match, loan(), "alice");
        ReleaseResult second = orchestrator.orchestrate(match, loan(), "alice");

        assertTrue(second.skipped());
        assertTrue(second.steps().contains("SKIPPED_RELEASE_IDEMPOTENT"));
        assertTrue(second.steps().contains("CREDIT_RESTORE_ALREADY_REQUESTED"));
        assertEquals(1, store.findAllReleases().size());
        assertEquals(1, store.findAllRestores().size());
        assertEquals(LienRelease.class, first.release().getClass());
        assertEquals(CreditRestore.class, first.restore().getClass());
    }

    @Test
    void orchestrate_unmatchedMatch_rejectedWithNoSideEffects() {
        InMemoryPaymentStore store = new InMemoryPaymentStore();
        PaymentMatch pending = store.saveMatchIfAbsent(
                PaymentMatch.unmatched("ACCT-1", "TX-1", 100L, "挂起", null));

        ReleaseResult result = new ReleaseOrchestrator(store)
                .orchestrate(pending, loan(), "alice");

        assertEquals(List.of("REJECTED_NOT_MATCHED"), result.steps());
        assertNull(result.release());
        assertNull(result.restore());
        assertTrue(store.findAllReleases().isEmpty());
        assertTrue(store.findAllRestores().isEmpty());
    }
}
