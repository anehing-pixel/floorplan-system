package com.company.floorplan.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2-6（M8 法律清算三态 + 撤销通道）针对性单测。
 *
 * <p>钉死新增的 NOTIFIED / LEGAL_ACTION / CANCELLED 三态流转白名单：
 * 通知→法律程序→收车→变现→结案主路径可达；补救期可从 OPEN/NOTIFIED/LEGAL_ACTION
 * 撤销；终态与变现中不可撤销；撤销后车辆 DISPOSING → OVERDUE。</p>
 */
class DisposalStateMachineTest {

    private static DisposalCase opened() {
        DisposalCase c = new DisposalCase();
        c.setCaseNo("DSP-TEST");
        c.open();
        return c;
    }

    @Test
    void notifiedLegalActionRepossessLiquidate_closePath() {
        DisposalCase c = opened();
        c.notifyParty();
        assertTrue(c.getStatus() == DisposalStatus.NOTIFIED);
        c.startLegalAction();
        assertTrue(c.getStatus() == DisposalStatus.LEGAL_ACTION);
        c.repossess();
        c.startLiquidating();
        assertTrue(c.getStatus() == DisposalStatus.LIQUIDATING);
        c.close(1_000_000L, 800_000L);
        assertTrue(c.getStatus() == DisposalStatus.CLOSED_LOSS);
    }

    @Test
    void openCanGoDirectlyToRepossessed_legacyPathPreserved() {
        DisposalCase c = opened();
        c.repossess();
        assertTrue(c.getStatus() == DisposalStatus.REPOSSESSED);
    }

    @Test
    void cancelFromOpenOrNotified_isAllowed() {
        DisposalCase c1 = opened();
        c1.cancel();
        assertTrue(c1.getStatus() == DisposalStatus.CANCELLED);

        DisposalCase c2 = opened();
        c2.notifyParty();
        c2.cancel();
        assertTrue(c2.getStatus() == DisposalStatus.CANCELLED);
    }

    @Test
    void cancelFromLegalAction_isAllowed() {
        DisposalCase c = opened();
        c.notifyParty();
        c.startLegalAction();
        c.cancel();
        assertTrue(c.getStatus() == DisposalStatus.CANCELLED);
    }

    @Test
    void cancelledAndClosed_areTerminal() {
        DisposalCase cancelled = opened();
        cancelled.cancel();
        assertTrue(cancelled.getStatus().isTerminal());
        assertFalse(cancelled.getStatus().canTransitTo(DisposalStatus.REPOSSESSED));

        DisposalCase closed = opened();
        closed.close(100L, 100L);
        assertTrue(closed.getStatus().isTerminal());
    }

    @Test
    void liquidating_cannotBeCancelled() {
        DisposalCase c = opened();
        c.repossess();
        c.startLiquidating();
        assertFalse(c.getStatus().canTransitTo(DisposalStatus.CANCELLED));
    }

    @Test
    void cancelDisposal_returnsVehicleToOverdue() {
        VehicleUnit u = new VehicleUnit();
        u.setVin("TESTDSP-M8");
        u.setStatus(VehicleStatus.DISPOSING);

        u.cancelDisposal();

        assertTrue(u.getStatus() == VehicleStatus.OVERDUE);
    }
}
