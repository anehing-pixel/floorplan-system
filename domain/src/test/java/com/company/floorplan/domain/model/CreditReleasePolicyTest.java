package com.company.floorplan.domain.model;

import com.company.floorplan.domain.model.VehicleUnit.ReleaseDecision;
import com.company.floorplan.domain.model.VehicleUnit.RepaymentCause;
import org.junit.jupiter.api.Test;

import static com.company.floorplan.domain.model.VehicleUnit.evaluateCreditRelease;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1-7（B1b 提前还本金误伤）针对性单测。
 *
 * <p>修复前：partialRepay 一律不回补额度，在库车主动提前还本金被误伤；
 * 若只按车辆状态修又会连带把库龄递减（强制还款）的敞口放回去。
 * 本测试钉死"动因 + 本金下降 + 质押物状态"三维判定，并验证判定过程
 * <b>绝不改变担保状态</b>。</p>
 */
class CreditReleasePolicyTest {

    private static final long PAID = 100_000L;

    private static VehicleUnit unit(VehicleStatus status, SecurityStatus securityStatus) {
        VehicleUnit u = new VehicleUnit();
        u.setVin("TESTRP-B1B");
        u.setStatus(status);
        u.setSecurityStatus(securityStatus);
        u.setPrincipalBalance(732_500L);
        return u;
    }

    @Test
    void inStockVoluntaryPrepay_releasesAndDoesNotTouchSecurity() {
        VehicleUnit u = unit(VehicleStatus.IN_STOCK, SecurityStatus.REGISTERED);

        ReleaseDecision decision = evaluateCreditRelease(u, PAID, RepaymentCause.VOLUNTARY_PREPAY);

        assertTrue(decision.release());
        assertEquals(PAID, decision.amount());
        // 提前还本金绝不误触发担保消灭
        assertEquals(SecurityStatus.REGISTERED, u.getSecurityStatus());
    }

    @Test
    void agingReduction_neverReleasesEvenInStock() {
        VehicleUnit u = unit(VehicleStatus.IN_STOCK, SecurityStatus.REGISTERED);

        ReleaseDecision decision = evaluateCreditRelease(u, PAID, RepaymentCause.AGING_REDUCTION);

        assertFalse(decision.release());
    }

    @Test
    void overdueMakeup_neverReleases() {
        VehicleUnit u = unit(VehicleStatus.OVERDUE, SecurityStatus.REGISTERED);

        assertFalse(evaluateCreditRelease(u, PAID, RepaymentCause.OVERDUE_MAKEUP).release());
    }

    @Test
    void soldPendingUnreleased_noRelease() {
        VehicleUnit u = unit(VehicleStatus.SOLD_PENDING, SecurityStatus.REGISTERED);

        assertFalse(evaluateCreditRelease(u, PAID, RepaymentCause.VOLUNTARY_PREPAY).release());
    }

    @Test
    void securityReleased_fullRelease() {
        VehicleUnit u = unit(VehicleStatus.SOLD_PENDING, SecurityStatus.RELEASED);

        ReleaseDecision decision = evaluateCreditRelease(u, PAID, RepaymentCause.VOLUNTARY_PREPAY);

        assertTrue(decision.release());
        assertEquals(PAID, decision.amount());
    }

    @Test
    void disposed_fullRelease() {
        VehicleUnit u = unit(VehicleStatus.DISPOSING, SecurityStatus.REGISTERED);

        ReleaseDecision decision = evaluateCreditRelease(u, PAID, RepaymentCause.VOLUNTARY_PREPAY);

        assertTrue(decision.release());
        assertEquals(PAID, decision.amount());
    }

    @Test
    void zeroPrincipal_noRelease() {
        VehicleUnit u = unit(VehicleStatus.IN_STOCK, SecurityStatus.REGISTERED);

        assertFalse(evaluateCreditRelease(u, 0L, RepaymentCause.VOLUNTARY_PREPAY).release());
    }
}
