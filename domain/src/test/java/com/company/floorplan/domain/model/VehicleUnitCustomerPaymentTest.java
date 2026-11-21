package com.company.floorplan.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2-8（M11 终端客户支付确认）针对性单测。
 *
 * <p>钉死确认前置条件：仅已售待还（SOLD_PENDING）且本金未结清的车可确认客户支付，
 * 在库车/已结清车/已售但本金归零的车均不可——防止资金未到账就确认放车（防脱信）。</p>
 */
class VehicleUnitCustomerPaymentTest {

    private static VehicleUnit unit(VehicleStatus status, long principal) {
        VehicleUnit u = new VehicleUnit();
        u.setVin("TESTPAY-M11");
        u.setStatus(status);
        u.setPrincipalBalance(principal);
        return u;
    }

    @Test
    void soldPendingWithBalance_canConfirm() {
        assertTrue(unit(VehicleStatus.SOLD_PENDING, 1_000_000L).canConfirmCustomerPayment());
    }

    @Test
    void inStock_cannotConfirm() {
        assertFalse(unit(VehicleStatus.IN_STOCK, 1_000_000L).canConfirmCustomerPayment());
    }

    @Test
    void soldPendingButSettledPrincipal_cannotConfirm() {
        assertFalse(unit(VehicleStatus.SOLD_PENDING, 0L).canConfirmCustomerPayment());
    }

    @Test
    void settled_cannotConfirm() {
        assertFalse(unit(VehicleStatus.SETTLED, 0L).canConfirmCustomerPayment());
    }
}
