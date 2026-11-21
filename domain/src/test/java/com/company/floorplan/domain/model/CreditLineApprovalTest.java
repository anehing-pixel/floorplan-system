package com.company.floorplan.domain.model;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1-4（M12 授信审批双段）针对性单测。
 *
 * <p>修复前：建额度直接置 ACTIVE，未经任何审批即可放款。本测试钉死
 * initiate 阶段（PENDING_APPROVAL）不可占用额度、complete 通过后才能占用、
 * 驳回后额度关闭、以及审批入口自身的非法流转保护。</p>
 */
class CreditLineApprovalTest {

    private static CreditLine pending() {
        CreditLine c = new CreditLine();
        c.setApprovedLimit(10_000_000L);
        c.setUsedAmount(0L);
        c.setFrozenAmount(0L);
        c.setStatus("PENDING_APPROVAL");
        return c;
    }

    @Test
    void pendingApproval_cannotOccupy() {
        CreditLine line = pending();

        BusinessException ex = assertThrows(BusinessException.class, () -> line.occupy(1_000_000L));

        assertEquals(ErrorCode.CREDIT_003.getCode(), ex.getErrorCode().getCode());
        assertEquals(0L, line.getUsedAmount());
    }

    @Test
    void afterApprove_canOccupy() {
        CreditLine line = pending();

        line.approve();
        assertEquals("ACTIVE", line.getStatus());
        line.occupy(1_000_000L);

        assertEquals(1_000_000L, line.getUsedAmount());
    }

    @Test
    void afterReject_cannotOccupy() {
        CreditLine line = pending();

        line.rejectApproval();
        assertEquals("CLOSED", line.getStatus());
        assertThrows(BusinessException.class, () -> line.occupy(1L));
    }

    @Test
    void approveFromActive_throws() {
        CreditLine line = pending();
        line.approve();

        assertThrows(BusinessException.class, line::approve);
        assertTrue(line.getStatus().equals("ACTIVE"));
    }
}
