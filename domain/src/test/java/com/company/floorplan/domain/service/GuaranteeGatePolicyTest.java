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
 * 开关默认关闭时完全不参与判定（Q27 未定源）；开启后只有 REGISTERED 放行。</p>
 *
 * <p><b>Q21 已定源（2026-11-22）：采用 A 硬阻断</b>——PENDING / NONE / 未知状态 /
 * 查询无结果一律拒付，且<b>不存在按经销商放宽的通道</b>。原先的
 * {@code gateEnabled_relaxedDealer_allows} 断言随决策删除，改为钉死「无绕过」。</p>
 */
class GuaranteeGatePolicyTest {

    private static GuaranteeView registered() {
        return new GuaranteeView("REGISTERED", "R-TOKYO-1", "金融公司", "2026-11-01T00:00:00");
    }

    @Test
    void gateDisabled_allowsEvenWithoutRegistration() {
        // Q27 判定源未定，默认关闭绝不参与放款判定
        assertFalse(evaluateGuarantee(null, false).blocked());
        assertFalse(evaluateGuarantee(new GuaranteeView("NONE", null, null, null), false).blocked());
    }

    @Test
    void gateEnabled_withRegistered_allows() {
        GuaranteeDecision decision = evaluateGuarantee(registered(), true);
        assertFalse(decision.blocked());
    }

    @Test
    void gateEnabled_statusIgnoreCase() {
        assertFalse(evaluateGuarantee(
                new GuaranteeView("registered", "R-1", null, null), true).blocked());
    }

    @Test
    void gateEnabled_withPendingOrNone_blocks() {
        assertTrue(evaluateGuarantee(
                new GuaranteeView("PENDING", null, null, null), true).blocked());
        assertTrue(evaluateGuarantee(
                new GuaranteeView("NONE", null, null, null), true).blocked());
    }

    @Test
    void gateEnabled_unknownStatus_blocks() {
        // 未知状态/null 绝不能被误判为已登记
        assertTrue(evaluateGuarantee(
                new GuaranteeView("SOMETHING_ELSE", null, null, null), true).blocked());
        assertTrue(evaluateGuarantee(
                new GuaranteeView(null, null, null, null), true).blocked());
    }

    @Test
    void gateEnabled_serviceUnavailable_failsClosed() {
        assertTrue(evaluateGuarantee(null, true).blocked());
    }

    @Test
    void gateEnabled_noDealerBypass_blocks() {
        // Q21 硬阻断：判定只认权属视图，签名里已无 dealerRelaxed 参数，
        // 任何经销商都不存在「先于 view == null 直接 allow」的绕过分支。
        // 这一组覆盖原 relaxedDealer 用例最危险的场景：服务不可达 + 白名单经销商。
        assertTrue(evaluateGuarantee(null, true).blocked());
        assertTrue(evaluateGuarantee(new GuaranteeView("NONE", null, null, null), true).blocked());
        assertTrue(evaluateGuarantee(new GuaranteeView("PENDING", null, null, null), true).blocked());
    }
}
