package com.company.floorplan.domain.model;

import com.company.floorplan.domain.model.AgingPlan.AgingAction;
import com.company.floorplan.domain.model.AgingPlan.Tier;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.company.floorplan.domain.model.AgingPlan.currentAmount;
import static com.company.floorplan.domain.model.AgingPlan.isExempt;
import static com.company.floorplan.domain.model.AgingPlan.tierFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2-4（M6 库龄递减增强）针对性单测。
 *
 * <p>覆盖 G1 分层规则边界、G2 应还额按当前余额动态重算、G4 豁免判定。
 * 91–120 天档沿用 10% 以保持存量 V1 计划兼容。</p>
 */
class AgingPlanTierTest {

    @Test
    void tierFor_coversAllBoundaries() {
        assertEquals(AgingAction.MONITOR, tierFor(0).action());
        assertEquals(AgingAction.MONITOR, tierFor(60).action());
        assertEquals(AgingAction.WARN, tierFor(61).action());
        assertEquals(AgingAction.WARN, tierFor(90).action());
        assertEquals(AgingAction.REPAY, tierFor(91).action());
        assertEquals(AgingAction.REPAY, tierFor(120).action());
        assertEquals(AgingAction.REPAY, tierFor(150).action());
        assertEquals(AgingAction.FREEZE_REPAY, tierFor(181).action());
        assertEquals(AgingAction.FREEZE_REPAY, tierFor(270).action());
        assertEquals(AgingAction.DISPOSE, tierFor(271).action());
    }

    @Test
    void tier_121to180_usesHigherRatio() {
        Tier tier = tierFor(150);
        assertEquals(AgingAction.REPAY, tier.action());
        assertEquals(0, new java.math.BigDecimal("0.20").compareTo(tier.ratio()));
    }

    @Test
    void currentAmount_recomputesFromCurrentBalanceAndTier() {
        long balance = 1_000_000L;
        // 91–120 天：10%，沿用存量口径
        assertEquals(100_000L, currentAmount(balance, 100, 166_500L));
        // 121–180 天：20%，按当前余额动态重算（不是生成时算死的金额）
        assertEquals(200_000L, currentAmount(balance, 150, 166_500L));
        // 181–270 天：30% + 冻结额度档
        assertEquals(300_000L, currentAmount(balance, 200, 166_500L));
    }

    @Test
    void currentAmount_nonRepayTier_fallsBackToPlanAmount() {
        assertEquals(166_500L, currentAmount(1_000_000L, 45, 166_500L));
        assertEquals(166_500L, currentAmount(1_000_000L, 300, 166_500L));
        assertEquals(166_500L, currentAmount(1_000_000L, null, 166_500L));
    }

    @Test
    void isExempt_matchesConfiguredVinsOnly() {
        Set<String> exempt = Set.of("TRANSFER-001", "INTRANSIT-002");
        assertTrue(isExempt("TRANSFER-001", exempt));
        assertFalse(isExempt("NORMAL-003", exempt));
        assertFalse(isExempt("TRANSFER-001", Set.of()));
        assertFalse(isExempt(null, exempt));
    }
}
