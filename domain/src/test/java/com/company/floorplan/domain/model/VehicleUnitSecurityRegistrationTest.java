package com.company.floorplan.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * T1-3（M9 登记编号落库）针对性单测。
 *
 * <p>修复前：老接口虽收 regNo 但直接丢弃，车辆担保状态永远停在 PENDING，
 * 使用方误以为登记已完成。本测试钉死领域状态判定：
 * 带合法登记编号时为 REGISTERED；无编号/空白编号仍为 PENDING；NONE 类型不受编号影响。</p>
 */
class VehicleUnitSecurityRegistrationTest {

    private static VehicleUnit unit() {
        VehicleUnit u = new VehicleUnit();
        u.setVin("TESTVIN-M9");
        return u;
    }

    @Test
    void withRegistrationNo_marksRegistered() {
        VehicleUnit u = unit();

        u.setSecurity(SecurityType.ASSIGNMENT, "R-TOKYO-12345");

        assertSame(SecurityType.ASSIGNMENT, u.getSecurityType());
        assertEquals(SecurityStatus.REGISTERED, u.getSecurityStatus());
    }

    @Test
    void withoutRegistrationNo_staysPending() {
        VehicleUnit u = unit();

        u.setSecurity(SecurityType.ASSIGNMENT, null);

        assertEquals(SecurityStatus.PENDING, u.getSecurityStatus());
    }

    @Test
    void blankRegistrationNo_staysPending() {
        VehicleUnit u = unit();

        u.setSecurity(SecurityType.RETENTION, "   ");

        assertEquals(SecurityStatus.PENDING, u.getSecurityStatus());
    }

    @Test
    void noneType_withRegistrationNo_staysNone() {
        VehicleUnit u = unit();

        u.setSecurity(SecurityType.NONE, "R-TOKYO-12345");

        assertEquals(SecurityStatus.NONE, u.getSecurityStatus());
    }
}
