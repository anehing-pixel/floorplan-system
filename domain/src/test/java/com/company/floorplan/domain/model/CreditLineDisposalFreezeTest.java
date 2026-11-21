package com.company.floorplan.domain.model;

import com.company.floorplan.domain.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T1-1（B3 处置冻结额度）针对性单测。
 *
 * <p>修复前：处置开单不冻结额度，违约经销商仍可继续占用额度放款。
 * 本测试钉死"处置冻结决策"{@link CreditLine#freezeForDisposal(long)} 的行为：
 * 可用量同步下降、状态转 FROZEN 后新占用被拦截、敞口为 0 时也必须冻结状态、
 * 冻结量受可用额上限保护且不影响既有的结清回补路径。</p>
 */
class CreditLineDisposalFreezeTest {

    private static CreditLine line(long approvedLimit, long used) {
        CreditLine c = new CreditLine();
        c.setApprovedLimit(approvedLimit);
        c.setUsedAmount(used);
        c.setStatus("ACTIVE");
        return c;
    }

    @Test
    void openDisposal_freezesExposure_availableDropsAndOccupyBlocked() {
        CreditLine line = line(1_000_000L, 400_000L);
        long before = line.available();

        long frozen = line.freezeForDisposal(200_000L);

        assertEquals(200_000L, frozen);
        assertEquals(200_000L, line.getFrozenAmount());
        assertEquals(before - 200_000L, line.available());
        assertEquals("FROZEN", line.getStatus());
        // 处置中的经销商不得再占用新额度放款
        assertThrows(BusinessException.class, () -> line.occupy(10_000L));
    }

    @Test
    void openDisposal_zeroExposure_stillBlocksFurtherOccupation() {
        CreditLine line = line(1_000_000L, 500_000L);

        long frozen = line.freezeForDisposal(0L);

        assertEquals(0L, frozen);
        assertEquals(0L, line.getFrozenAmount());
        // 未放款/已还清的车敞口为 0，但仍须冻结状态，否则违约经销商可继续放款
        assertEquals("FROZEN", line.getStatus());
        assertThrows(BusinessException.class, () -> line.occupy(1L));
    }

    @Test
    void openDisposal_exposureAboveAvailable_isCappedAtAvailable() {
        CreditLine line = line(1_000_000L, 900_000L);

        long frozen = line.freezeForDisposal(500_000L);

        assertEquals(100_000L, frozen);
        assertEquals(100_000L, line.getFrozenAmount());
        assertEquals(0L, line.available());
    }

    @Test
    void frozenLine_stillAllowsExistingRepaymentRelease() {
        CreditLine line = line(1_000_000L, 400_000L);
        line.freezeForDisposal(200_000L);

        // 结清回补（release）不检查状态，冻结不应破坏既有回补路径
        line.release(100_000L);

        assertEquals(300_000L, line.getUsedAmount());
        assertEquals(200_000L, line.getFrozenAmount());
    }
}
