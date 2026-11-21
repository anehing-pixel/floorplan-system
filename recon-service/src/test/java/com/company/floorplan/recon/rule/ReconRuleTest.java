package com.company.floorplan.recon.rule;

import com.company.floorplan.recon.model.Comparison;
import com.company.floorplan.recon.model.ReconDiff;
import com.company.floorplan.recon.rule.ReconRuleConfig;
import com.company.floorplan.recon.rule.R1AdvanceVsRepaymentRule;
import com.company.floorplan.recon.rule.R2VehicleVsAdvanceRule;
import com.company.floorplan.recon.rule.R3CreditVsAdvanceRule;
import com.company.floorplan.recon.rule.R4BankVsRepaymentRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 8 条规则的判定逻辑参数化单测：不连库、不启 Spring，只喂账本事实。
 *
 * <p>纯函数约束在此被直接验证：差异不带批次号、不带检测时间——
 * 若哪天有人在规则里塞了 UUID.now() 或 JdbcTemplate，这些断言会立刻失败。</p>
 */
class ReconRuleTest {

    /** (规则, 期望侧, 实际侧, 预期差异数, 预期差异类型, 说明中应包含的片段) */
    static Stream<Arguments> judgments() {
        ReconRule r1 = new R1AdvanceVsRepaymentRule();
        ReconRule r2 = new R2VehicleVsAdvanceRule();
        ReconRule r3 = new R3CreditVsAdvanceRule();
        ReconRule r4 = new R4BankVsRepaymentRule();
        return Stream.of(
                // ---- R1 垫款金额 vs 未偿本金+累计核销 ----
                Arguments.of(r1, 100_000L, 100_000L, 0, null, null),
                Arguments.of(r1, 100_000L,  60_000L, 1, "BALANCE_MISMATCH", "差额(借出-已核证)=40000"),
                Arguments.of(r1,  50_000L,  80_000L, 1, "BALANCE_MISMATCH", "差额(借出-已核证)=-30000"),
                // ---- R2 车辆状态编码 vs 未结清垫款编码 ----
                Arguments.of(r2, 1L, 1L, 0, null, null),
                Arguments.of(r2, 0L, 0L, 0, null, null),
                Arguments.of(r2, 1L, 0L, 1, "STATE_MISMATCH", "应有未结清垫款"),
                Arguments.of(r2, 0L, 1L, 1, "STATE_MISMATCH", "不应有未结清垫款"),
                // ---- R3 额度占用 vs 未终结垫款 ----
                Arguments.of(r3, 500_000L, 500_000L, 0, null, null),
                Arguments.of(r3, 500_000L, 450_000L, 1, "CREDIT_MISMATCH", "差额(占用-垫款)=50000"),
                // ---- R4 银行入账 vs 还款核销 ----
                Arguments.of(r4, 120_000L, 120_000L, 0, null, null),
                Arguments.of(r4, 120_000L, 100_000L, 1, "CASH_MISMATCH", "差额(银行-核销)=20000"),
                Arguments.of(r4,       0L,  30_000L, 1, "CASH_MISMATCH", "差额(银行-核销)=-30000"),
                // ---- R5-R8 占位：无论事实如何都不产出差异 ----
                Arguments.of(new PlaceholderReconRule(ReconRuleCode.R5), 1L, 2L, 0, null, null),
                Arguments.of(new PlaceholderReconRule(ReconRuleCode.R6), 1L, 2L, 0, null, null),
                Arguments.of(new PlaceholderReconRule(ReconRuleCode.R7), 1L, 2L, 0, null, null),
                Arguments.of(new PlaceholderReconRule(ReconRuleCode.R8), 1L, 2L, 0, null, null));
    }

    @ParameterizedTest(name = "{0} 期望={1} 实际={2} → 差异数={3}")
    @MethodSource("judgments")
    void ruleJudgment(ReconRule rule, long expected, long actual,
                      int expectedDiffCount, String expectedDiffType, String detailFragment) {
        Comparison fact = new Comparison("VIN-TEST-001", expected, actual, "单测事实");

        List<ReconDiff> diffs = rule.evaluate(List.of(fact));

        assertEquals(expectedDiffCount, diffs.size(), rule.code() + " 判定条数不符");
        if (expectedDiffCount == 0) {
            return;
        }
        ReconDiff d = diffs.get(0);
        assertEquals(rule.code(), d.getRuleCode());
        assertEquals(expectedDiffType, d.getDiffType());
        assertEquals(expected, d.getExpectedValue());
        assertEquals(actual, d.getActualValue());
        assertEquals("VIN-TEST-001", d.getSubject());
        assertEquals("OPEN", d.getStatus());
        assertTrue(d.getDetail().contains(detailFragment), "说明缺少口径：" + d.getDetail());
        assertNull(d.getBatchNo(), "规则是纯函数，不得自行认领批次");
        assertNull(d.getDetectedAt(), "规则是纯函数，不得读取时钟");
    }

    /** 规则清单必须恰好 8 条、按 R1..R8 排列，且实现标记与目录一致。 */
    @Test
    void ruleSetContainsAllEightInOrder() {
        List<ReconRule> rules = new ReconRuleConfig().reconRuleSet().rules();
        assertEquals(8, rules.size());
        for (int i = 0; i < 8; i++) {
            ReconRuleCode code = ReconRuleCode.values()[i];
            assertEquals(code.name(), rules.get(i).code());
            assertEquals(code.name().charAt(1) <= '4', code.implemented(),
                    "R1-R4 应实现、R5-R8 应占位");
        }
    }

    /** 空事实、null 事实、无 subject 的事实都不能产出差异（subject 在表上 NOT NULL）。 */
    @Test
    void ignoresEmptyNullAndSubjectlessFacts() {
        ReconRule rule = new R3CreditVsAdvanceRule();
        assertTrue(rule.evaluate(null).isEmpty());
        assertTrue(rule.evaluate(List.of()).isEmpty());
        assertTrue(rule.evaluate(java.util.Arrays.asList(
                null, new Comparison(null, 1, 2), new Comparison("  ", 1, 2))).isEmpty());
    }

    /** 占位规则构造时必须确实是未实现的编号，防止误把 R1 标成占位。 */
    @ParameterizedTest
    @EnumSource(value = ReconRuleCode.class, names = {"R5", "R6", "R7", "R8"})
    void placeholderRejectsImplementedCode(ReconRuleCode placeholderCode) {
        PlaceholderReconRule rule = new PlaceholderReconRule(placeholderCode);
        assertEquals(placeholderCode.name(), rule.code());
        assertTrue(rule.evaluate(List.of(new Comparison("VIN", 9, 9))).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> new PlaceholderReconRule(ReconRuleCode.R1));
    }
}
