package com.company.floorplan.recon.rule;

import com.company.floorplan.recon.model.Comparison;
import com.company.floorplan.recon.model.ReconDiff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 8 条规则的判定逻辑参数化单测：不连库、不启 Spring，只喂账本事实。
 *
 * <p>纯函数约束在此被直接验证：差异不带批次号、不带检测时间——
 * 若哪天有人在规则里塞了 UUID.now() 或 JdbcTemplate，这些断言会立刻失败。</p>
 *
 * <p><b>2026-11-22</b>：R5–R8 从占位改为真实判定后，单测同步改为
 * 「相等 → 0 差异 / 不等 → 1 差异且差异类型正确」。原占位用例无论输入如何都断言 0 差异，
 * 属于用测试证明占位实现的循环论证，已删除。</p>
 */
class ReconRuleTest {

    /** (规则, 期望侧, 实际侧, 预期差异数, 预期差异类型, 说明中应包含的片段) */
    static Stream<Arguments> judgments() {
        ReconRule r1 = new R1AdvanceVsRepaymentRule();
        ReconRule r2 = new R2VehicleVsAdvanceRule();
        ReconRule r3 = new R3CreditVsAdvanceRule();
        ReconRule r4 = new R4BankVsRepaymentRule();
        ReconRule r5 = new ExactValueReconRule(ReconRuleCode.R5, "台账累计计提利息", "计息流水累计(主表+归档)");
        ReconRule r6 = new ExactValueReconRule(ReconRuleCode.R6, "V10 有效登记(1=有)", "担保台账 REGISTERED(1=是)");
        ReconRule r7 = new ExactValueReconRule(ReconRuleCode.R7, "递减计划已付期数", "递减核销流水笔数");
        ReconRule r8 = new ExactValueReconRule(ReconRuleCode.R8, "处置认定损失", "借据 WRITTEN_OFF 未偿敞口");
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
                // ---- R5 台账累计计提 vs 计息流水（含归档）：精确相等 ----
                Arguments.of(r5, 12_000L, 12_000L, 0, null, null),
                Arguments.of(r5, 12_000L, 11_500L, 1, "INTEREST_MISMATCH", "差额(期望-实际)=500"),
                // ---- R6 V10 有效登记编码 vs 担保台账编码：精确相等 ----
                Arguments.of(r6, 1L, 1L, 0, null, null),
                Arguments.of(r6, 0L, 0L, 0, null, null),
                Arguments.of(r6, 1L, 0L, 1, "COLLATERAL_MISMATCH", "V10 有效登记(1=有)=1"),
                Arguments.of(r6, 0L, 1L, 1, "COLLATERAL_MISMATCH", "差额(期望-实际)=-1"),
                // ---- R7 递减已付期数 vs 核销流水笔数：精确相等 ----
                Arguments.of(r7, 3L, 3L, 0, null, null),
                Arguments.of(r7, 3L, 2L, 1, "CURTAILMENT_MISMATCH", "递减计划已付期数=3"),
                // ---- R8 处置已结案编码 vs 借据已终止编码：精确相等 ----
                Arguments.of(r8, 1L, 1L, 0, null, null),
                Arguments.of(r8, 0L, 0L, 0, null, null),
                Arguments.of(r8, 1L, 0L, 1, "WRITEOFF_MISMATCH", "处置认定损失=1"));
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

    /** 规则清单必须恰好 8 条、按 R1..R8 排列，且 8 条全部为已实现（不再有占位）。 */
    @Test
    void ruleSetContainsAllEightImplementedInOrder() {
        List<ReconRule> rules = new ReconRuleConfig().reconRuleSet().rules();
        assertEquals(8, rules.size());
        for (int i = 0; i < 8; i++) {
            ReconRuleCode code = ReconRuleCode.values()[i];
            assertEquals(code.name(), rules.get(i).code());
            assertTrue(code.implemented(), code.name() + " 必须已实现（R5-R8 已按 ADR 裁决落地）");
        }
        // 反向护栏：一旦有人把某条改回占位，这条断言立刻失败
        assertEquals(8, java.util.Arrays.stream(ReconRuleCode.values())
                .filter(ReconRuleCode::implemented).count());
    }

    /** 8 条规则的差异类型必须是真实类型，绝不残留 TODO（防止报告里出现不可统计的脏类型）。 */
    @ParameterizedTest
    @EnumSource(ReconRuleCode.class)
    void diffTypeIsNeverPlaceholder(ReconRuleCode code) {
        assertTrue(code.implemented(), code + " 应已实现");
        assertTrue(!code.diffType().isBlank() && !"TODO".equals(code.diffType()),
                code + " 差异类型必须是真实类型");
    }

    /** 反假绿护栏：8 条规则面对同一个「不等」事实，都必须产出 1 条差异。 */
    @Test
    void everyRuleReportsMismatchOnUnequalFacts() {
        Comparison unequal = new Comparison("VIN-UNEQUAL", 10L, 20L, "护栏事实");
        for (ReconRule rule : new ReconRuleConfig().reconRuleSet().rules()) {
            List<ReconDiff> diffs = rule.evaluate(List.of(unequal));
            assertEquals(1, diffs.size(), rule.code() + " 面对不等事实必须报差异，不得静默通过");
            assertEquals(rule.code(), diffs.get(0).getRuleCode());
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
}
