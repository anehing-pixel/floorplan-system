package com.company.floorplan.domain.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1-2（B4 盘点缺失改状态）针对性单测。
 *
 * <p>修复前：盘点缺失只置 ootSuspected 布尔标记，车辆仍是 IN_STOCK，
 * 违约处置扫描作业（只扫 OVERDUE）永远捕获不到。本测试钉死
 * {@link VehicleUnit#markInventoryMissing(LocalDate)} 把在库车升级为 OVERDUE、
 * 从而可进入处置流程的行为，以及重复盘点与非在库车的边界。</p>
 */
class VehicleUnitInventoryMissingTest {

    private static final LocalDate AUDIT_DATE = LocalDate.of(2026, 11, 22);

    private static VehicleUnit inStock() {
        VehicleUnit u = new VehicleUnit();
        u.setVin("TESTVIN-B4");
        u.setStatus(VehicleStatus.IN_STOCK);
        return u;
    }

    @Test
    void missingInStock_escalatesToOverdueAndMarksRiskSignals() {
        VehicleUnit u = inStock();

        u.markInventoryMissing(AUDIT_DATE);

        assertEquals(VehicleStatus.OVERDUE, u.getStatus());
        assertTrue(u.isOotSuspected());
        assertEquals(1, u.getAuditMismatchCount());
        assertEquals(AUDIT_DATE, u.getLastAuditDate());
        assertEquals("HIGH", u.getRiskLevel());
    }

    @Test
    void escalatedVehicle_canEnterDisposalFlow() {
        VehicleUnit u = inStock();
        u.markInventoryMissing(AUDIT_DATE);

        // 处置扫描作业捕获 OVERDUE 后开单：OVERDUE → DISPOSING 必须合法
        assertTrue(u.getStatus().canTransitTo(VehicleStatus.DISPOSING));
    }

    @Test
    void repeatedMissing_statusIsIdempotent_mismatchAccumulates() {
        VehicleUnit u = inStock();
        u.markInventoryMissing(AUDIT_DATE);
        u.markInventoryMissing(AUDIT_DATE.plusDays(1));

        assertEquals(VehicleStatus.OVERDUE, u.getStatus());
        assertEquals(2, u.getAuditMismatchCount());
    }

    @Test
    void missingSoldPending_onlyMarks_noIllegalStatusChange() {
        VehicleUnit u = inStock();
        u.setStatus(VehicleStatus.SOLD_PENDING);

        u.markInventoryMissing(AUDIT_DATE);

        assertEquals(VehicleStatus.SOLD_PENDING, u.getStatus());
        assertTrue(u.isOotSuspected());
        assertEquals(1, u.getAuditMismatchCount());
    }

    @Test
    void missingAlreadyOverdue_doesNotThrow() {
        VehicleUnit u = inStock();
        u.setStatus(VehicleStatus.OVERDUE);

        assertDoesNotThrow(() -> u.markInventoryMissing(AUDIT_DATE));
        assertEquals(VehicleStatus.OVERDUE, u.getStatus());
    }
}
