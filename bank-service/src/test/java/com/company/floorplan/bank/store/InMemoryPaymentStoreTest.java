package com.company.floorplan.bank.store;

import com.company.floorplan.bank.model.CreditRestore;
import com.company.floorplan.bank.model.LienRelease;
import com.company.floorplan.bank.model.PaymentMatch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 幂等单测：与 V17 三条唯一约束一一对应。 */
class InMemoryPaymentStoreTest {

    @Test
    void saveMatchIfAbsent_sameKey_returnsFirstAndKeepsOneRow() {
        InMemoryPaymentStore store = new InMemoryPaymentStore();
        PaymentMatch first = store.saveMatchIfAbsent(
                PaymentMatch.unmatched("ACCT-1", "TX-1", 100L, "首次", null));
        PaymentMatch second = store.saveMatchIfAbsent(
                PaymentMatch.unmatched("ACCT-1", "TX-1", 999L, "重复", null));

        assertEquals(first.id(), second.id(), "uk_payment_match_txn：同一账户同一流水号只一行");
        assertEquals(100L, second.matchedAmount(), "重复写入不得覆盖首次结论");
        assertEquals(1, store.findAllMatches().size());
    }

    @Test
    void differentAccountSameTransactionId_areDifferentRows() {
        InMemoryPaymentStore store = new InMemoryPaymentStore();
        store.saveMatchIfAbsent(PaymentMatch.unmatched("ACCT-1", "TX-1", 100L, "a", null));
        store.saveMatchIfAbsent(PaymentMatch.unmatched("ACCT-2", "TX-1", 200L, "b", null));
        assertEquals(2, store.findAllMatches().size());
    }

    @Test
    void saveReleaseIfAbsent_sameMatchId_idempotent() {
        InMemoryPaymentStore store = new InMemoryPaymentStore();
        LienRelease first = store.saveReleaseIfAbsent(
                LienRelease.pending(1L, "TX-1", "COLA017481", 10L, 100L, "system"));
        LienRelease duplicate = store.saveReleaseIfAbsent(
                LienRelease.pending(1L, "TX-1", "COLA017481", 10L, 999L, "system"));

        assertEquals(first.id(), duplicate.id(), "uk_lien_release_match：一次匹配只解押一次");
        assertEquals(1, store.findAllReleases().size());
    }

    @Test
    void saveRestoreIfAbsent_sameMatchId_idempotent() {
        InMemoryPaymentStore store = new InMemoryPaymentStore();
        CreditRestore first = store.saveRestoreIfAbsent(
                CreditRestore.pending(1L, "TX-1", 42L, null, 100L, "system"));
        CreditRestore duplicate = store.saveRestoreIfAbsent(
                CreditRestore.pending(1L, "TX-1", 42L, null, 999L, "system"));

        assertEquals(first.id(), duplicate.id(), "uk_credit_restore_match：一次匹配只回补一次");
        assertEquals(1, store.findAllRestores().size());
    }
}
