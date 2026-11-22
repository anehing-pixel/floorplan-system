package com.company.floorplan.loan.service;

import com.company.floorplan.loan.model.DpdStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DpdCalculator 边界单测。
 *
 * <p>覆盖两套口径列出的全部边界点值：任务卡提示的 0/1-29/30-59/60-90/90+
 * 与设计稿种子数据的 1-30/31-60/61-90/91+。实现采用设计稿口径，边界在 30/31、60/61、90/91 处钉死。</p>
 */
class DpdCalculatorTest {

    @ParameterizedTest(name = "DPD={0} → {1}")
    @CsvSource({
            // 未逾期侧（含 ADR-008 0-based 负值场景）
            "-1, NONE",
            "0,  NONE",
            // M1：1-30（任务卡 1-29 边界 + 设计稿 30 上界）
            "1,   M1",
            "29,  M1",
            "30,  M1",
            // M2：31-60（设计稿 31 下界 + 任务卡 30-59 边界 + 设计稿 60 上界）
            "31,  M2",
            "59,  M2",
            "60,  M2",
            // M3：61-90（设计稿 61 下界 + 任务卡 60-90 边界 + 90 上界）
            "61,  M3",
            "90,  M3",
            // M4：≥91（设计稿 91 下界 + 任务卡 90+ 边界）
            "91,  M4",
            "120, M4",
            "200, M4"
    })
    void stageOf_coversAllBoundaries(int dpd, DpdStage expected) {
        assertEquals(expected, DpdCalculator.stageOf(dpd));
    }

    @Test
    void daysPastDue_whenNotYetDue_returnsZero() {
        LocalDate due = LocalDate.of(2026, 12, 21);
        assertEquals(0, DpdCalculator.daysPastDue(due.minusDays(1), due, 0));
    }

    @Test
    void daysPastDue_whenDueToday_returnsZero() {
        LocalDate due = LocalDate.of(2026, 12, 21);
        assertEquals(0, DpdCalculator.daysPastDue(due, due, 0));
    }

    @Test
    void daysPastDue_countsCalendarDays() {
        LocalDate due = LocalDate.of(2026, 11, 1);
        assertEquals(1, DpdCalculator.daysPastDue(due.plusDays(1), due, 0));
        assertEquals(30, DpdCalculator.daysPastDue(due.plusDays(30), due, 0));
        assertEquals(91, DpdCalculator.daysPastDue(due.plusDays(91), due, 0));
    }

    /**
     * Q14 业务定源回归用例：逾期天数按自然日计，周末/节假日不跳过。
     * 2026-11-01 实证为 Sunday，从它到 11-08 恰为 7 个自然日；
     * 若有人把 DPD 改成营业日口径（跳过周末）只会得到 5，本用例立即变红。
     */
    @Test
    void daysPastDue_calendarBasis_doesNotSkipWeekends() {
        LocalDate sunday = LocalDate.of(2026, 11, 1);
        assertEquals(7, DpdCalculator.daysPastDue(sunday.plusDays(7), sunday, 0));
        // 宽限期同样按自然日累加，不因跨周末而缩短
        assertEquals(7, DpdCalculator.daysPastDue(sunday.plusDays(10), sunday, 3));
    }

    @Test
    void daysPastDue_gracePeriodIsExcluded() {
        LocalDate due = LocalDate.of(2026, 11, 1);
        // 到期后 3 天、宽限期 3 天 → 仍在宽限期内，不算逾期
        assertEquals(0, DpdCalculator.daysPastDue(due.plusDays(3), due, 3));
        // 宽限期届满次日 → DPD=1
        assertEquals(1, DpdCalculator.daysPastDue(due.plusDays(4), due, 3));
    }

    @Test
    void daysPastDue_whenDueDateMissing_returnsZero() {
        assertEquals(0, DpdCalculator.daysPastDue(LocalDate.of(2026, 11, 21), null, 0));
    }

    @Test
    void daysPastDue_negativeGraceTreatedAsZero() {
        LocalDate due = LocalDate.of(2026, 11, 1);
        assertEquals(5, DpdCalculator.daysPastDue(due.plusDays(5), due, -3));
    }

    @Test
    void evaluate_combinesDpdAndStage() {
        LocalDate due = LocalDate.of(2026, 1, 1);
        DpdCalculator.DpdResult result = DpdCalculator.evaluate(due.plusDays(31), due, 0);
        assertEquals(31, result.dpd());
        assertEquals(DpdStage.M2, result.stage());
        assertEquals("M2", result.stageCode());
    }

    @Test
    void evaluate_whenNotOverdue_stageCodeIsNull() {
        LocalDate due = LocalDate.of(2026, 1, 1);
        DpdCalculator.DpdResult result = DpdCalculator.evaluate(due.plusDays(2), due, 3);
        assertEquals(0, result.dpd());
        assertEquals(DpdStage.NONE, result.stage());
        assertNull(result.stageCode());
    }

    @Test
    void ruleSnapshot_constantsMatchDesignSeed() {
        assertEquals("v1", DpdCalculator.RULE_VERSION);
        assertEquals("CALENDAR", DpdCalculator.DAY_BASIS);
        assertTrue(DpdCalculator.THRESHOLD_SNAPSHOT.contains("\"M1\":30"));
        assertTrue(DpdCalculator.THRESHOLD_SNAPSHOT.contains("\"M3\":90"));
    }
}
