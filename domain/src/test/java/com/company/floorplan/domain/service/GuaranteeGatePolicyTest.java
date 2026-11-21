package com.company.floorplan.domain.service;

import com.company.floorplan.domain.service.RoleSeparationPolicy.GuaranteeDecision;
import com.company.floorplan.domain.service.RoleSeparationPolicy.GuaranteeLookup.GuaranteeView;
import org.junit.jupiter.api.Test;

import static com.company.floorplan.domain.service.RoleSeparationPolicy.evaluateGuarantee;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1-5（B1 担保门禁）针对性单测。
 *
 * <p>修复前：放款流程完全不查担保状态，没登记也放款。本测试钉死接缝判定：
 * 开关默认关闭时完全不参与判定（Q27 未定源）；开启后只有 REGISTERED 放行，
 * PENDING/NONE/查询失败一律硬阻断，经销商白名单可放宽（Q21）。</p>
 */
class GuaranteeGatePolicyTest {

    private static GuaranteeView registered() {
        return new GuaranteeView("REGISTERED", "R-TOKYO-1", "金融公司", "2026-11-01T00:00:00");
    }

    @Test
    void gateDisabled_allowsEvenWithoutRegistration() {
        // Q27 判定源未定，默认关闭绝不参与放款判定
        assertFalse(evaluateGuarantee(null, false, false).blocked());
        assertFalse(evaluateGuarantee(new GuaranteeView("NONE", null, null, null), false, false).blocked());
    }

    @Test
    void gateEnabled_withRegistered_allows() {
        GuaranteeDecision decision = evaluateGuarantee(registered(), true, false);
        assertFalse(decision.blocked());
    }

    @Test
    void gateEnabled_statusIgnoreCase() {
        assertFalse(evaluateGuarantee(
                new GuaranteeView("registered", "R-1", null, null), true, false).blocked());
    }

    @Test
    void gateEnabled_withPendingOrNone_blocks() {
        assertTrue(evaluateGuarantee(
                new GuaranteeView("PENDING", null, null, null), true, false).blocked());
        assertTrue(evaluateGuarantee(
                new GuaranteeView("NONE", null, null, null), true, false).blocked());
    }

    @Test
    void gateEnabled_serviceUnavailable_failsClosed() {
        assertTrue(evaluateGuarantee(null, true, false).blocked());
    }

    @Test
    void gateEnabled_relaxedDealer_allows() {
        assertFalse(evaluateGuarantee(null, true, true).blocked());
    }
}
