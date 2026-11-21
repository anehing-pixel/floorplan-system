package com.company.floorplan.release.statemachine;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.release.model.ReleaseStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机穷举测试：6×6 = 36 种状态对全部覆盖。
 *
 * <p><b>release 测试循环论证修复（CORE_VERIFIER_REPORT）</b>：旧版合法/非法用例都由
 * {@code ReleaseStateMachine.canTransit} 自身反向生成，再断言 {@code canTransit}，
 * 等于"用被测代码证明被测代码"——白名单里写错一条边，穷举测试照样全绿。
 * 现在改为<b>硬编码期望表</b>作为唯一事实来源：用例由期望表生成，
 * 另加 {@link #implementationMatchesHardcodedWhitelist()} 逐格比对 36 种组合，
 * 实现与期望表一旦分叉立即失败。</p>
 */
class ReleaseStateMachineTest {

    /**
     * 业务白名单（硬编码，不从被测代码派生）。与设计稿 §状态机一致：
     * <pre>
     *   PENDING_RELEASE → SENT | CANCELLED
     *   SENT            → CONFIRMED | CANCELLED
     *   CONFIRMED       → RELEASED | CANCELLED
     *   RELEASED        → LIEN_DISCHARGED
     *   LIEN_DISCHARGED → （终态）
     *   CANCELLED       → （终态）
     * </pre>
     */
    private static final Map<ReleaseStatus, Set<ReleaseStatus>> EXPECTED_ALLOWED = Map.of(
            ReleaseStatus.PENDING_RELEASE, Set.of(ReleaseStatus.SENT, ReleaseStatus.CANCELLED),
            ReleaseStatus.SENT, Set.of(ReleaseStatus.CONFIRMED, ReleaseStatus.CANCELLED),
            ReleaseStatus.CONFIRMED, Set.of(ReleaseStatus.RELEASED, ReleaseStatus.CANCELLED),
            ReleaseStatus.RELEASED, Set.of(ReleaseStatus.LIEN_DISCHARGED),
            ReleaseStatus.LIEN_DISCHARGED, Set.of(),
            ReleaseStatus.CANCELLED, Set.of());

    /** 合法用例来自硬编码期望表，而非 canTransit 反向生成。 */
    static Stream<Arguments> legalTransitions() {
        List<Arguments> cases = new ArrayList<>();
        for (var entry : EXPECTED_ALLOWED.entrySet()) {
            for (ReleaseStatus to : entry.getValue()) {
                cases.add(Arguments.of(entry.getKey(), to));
            }
        }
        return cases.stream();
    }

    /** 非法用例 = 全 36 组合减去硬编码期望表里的合法边。 */
    static Stream<Arguments> illegalTransitions() {
        List<Arguments> cases = new ArrayList<>();
        for (ReleaseStatus from : ReleaseStatus.values()) {
            Set<ReleaseStatus> allowed = EXPECTED_ALLOWED.getOrDefault(from, Set.of());
            for (ReleaseStatus to : ReleaseStatus.values()) {
                if (!allowed.contains(to)) {
                    cases.add(Arguments.of(from, to));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "合法迁移 {0} -> {1}")
    @MethodSource("legalTransitions")
    void legalTransitionsAreAccepted(ReleaseStatus from, ReleaseStatus to) {
        assertTrue(ReleaseStateMachine.canTransit(from, to));
        assertTrue(ReleaseStateMachine.nextStates(from).contains(to));
        assertDoesNotThrow(() -> ReleaseStateMachine.requireTransition(from, to));
    }

    @ParameterizedTest(name = "非法迁移 {0} -> {1}")
    @MethodSource("illegalTransitions")
    void illegalTransitionsAreRejected(ReleaseStatus from, ReleaseStatus to) {
        assertFalse(ReleaseStateMachine.canTransit(from, to));
        assertFalse(ReleaseStateMachine.nextStates(from).contains(to));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> ReleaseStateMachine.requireTransition(from, to));
        assertEquals(ErrorCode.VEHICLE_002, ex.getErrorCode());
        assertTrue(ex.getMessage().contains(from.name() + " -> " + to.name()));
    }

    /** 逐格比对实现与硬编码白名单，彻底消除循环论证。 */
    @Test
    void implementationMatchesHardcodedWhitelist() {
        int legalCount = 0;
        for (ReleaseStatus from : ReleaseStatus.values()) {
            Set<ReleaseStatus> expected = EXPECTED_ALLOWED.getOrDefault(from, Set.of());
            for (ReleaseStatus to : ReleaseStatus.values()) {
                boolean expectedLegal = expected.contains(to);
                assertEquals(expectedLegal, ReleaseStateMachine.canTransit(from, to),
                        "状态迁移判定与业务白名单不一致：" + from + " -> " + to);
                if (expectedLegal) {
                    legalCount++;
                }
            }
        }
        assertEquals(7, legalCount, "合法迁移白名单应为 7 条边");
    }

    /** 红线一：跳步放车——没有 CONFIRMED 就 RELEASED，必须拒绝。 */
    @Test
    void releaseWithoutConfirmationIsRejected() {
        assertFalse(ReleaseStateMachine.canTransit(ReleaseStatus.PENDING_RELEASE, ReleaseStatus.RELEASED));
        assertFalse(ReleaseStateMachine.canTransit(ReleaseStatus.SENT, ReleaseStatus.RELEASED));
        assertThrows(BusinessException.class,
                () -> ReleaseStateMachine.requireTransition(ReleaseStatus.PENDING_RELEASE, ReleaseStatus.RELEASED));
        assertThrows(BusinessException.class,
                () -> ReleaseStateMachine.requireTransition(ReleaseStatus.SENT, ReleaseStatus.RELEASED));
        // 只有 CONFIRMED 是放车的唯一入口
        assertTrue(ReleaseStateMachine.canTransit(ReleaseStatus.CONFIRMED, ReleaseStatus.RELEASED));
    }

    /** 红线二：未放车先解抵——没有 RELEASED 就 LIEN_DISCHARGED，必须拒绝。 */
    @Test
    void dischargeLienBeforeReleaseIsRejected() {
        assertFalse(ReleaseStateMachine.canTransit(ReleaseStatus.PENDING_RELEASE, ReleaseStatus.LIEN_DISCHARGED));
        assertFalse(ReleaseStateMachine.canTransit(ReleaseStatus.SENT, ReleaseStatus.LIEN_DISCHARGED));
        assertFalse(ReleaseStateMachine.canTransit(ReleaseStatus.CONFIRMED, ReleaseStatus.LIEN_DISCHARGED));
        assertThrows(BusinessException.class,
                () -> ReleaseStateMachine.requireTransition(ReleaseStatus.CONFIRMED, ReleaseStatus.LIEN_DISCHARGED));
        // 只有 RELEASED 是解抵的唯一入口
        assertTrue(ReleaseStateMachine.canTransit(ReleaseStatus.RELEASED, ReleaseStatus.LIEN_DISCHARGED));
    }

    /** 终态不可再流转；自迁移一律拒绝。 */
    @ParameterizedTest
    @EnumSource(ReleaseStatus.class)
    void terminalAndSelfTransitionsAreRejected(ReleaseStatus status) {
        assertFalse(ReleaseStateMachine.canTransit(status, status));
        if (status.isTerminal()) {
            assertTrue(ReleaseStateMachine.nextStates(status).isEmpty());
            for (ReleaseStatus to : ReleaseStatus.values()) {
                assertFalse(ReleaseStateMachine.canTransit(status, to));
            }
        }
    }

    /** 空值一律拒绝，不能因为 null 而悄悄放过。 */
    @Test
    void nullStatesAreRejected() {
        assertFalse(ReleaseStateMachine.canTransit(null, ReleaseStatus.SENT));
        assertFalse(ReleaseStateMachine.canTransit(ReleaseStatus.PENDING_RELEASE, null));
        assertThrows(BusinessException.class,
                () -> ReleaseStateMachine.requireTransition(null, ReleaseStatus.SENT));
    }
}
