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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机穷举测试：6×6 = 36 种状态对全部覆盖。
 *
 * <p>合法迁移清单与非法迁移清单都由 {@code ReleaseStateMachine.canTransit} 反向生成，
 * 因此"新增一个状态却忘了补测试"不会静默漏过——合法集合变了，非法集合自动跟着变。</p>
 */
class ReleaseStateMachineTest {

    static Stream<Arguments> legalTransitions() {
        List<Arguments> cases = new ArrayList<>();
        for (ReleaseStatus from : ReleaseStatus.values()) {
            for (ReleaseStatus to : ReleaseStatus.values()) {
                if (ReleaseStateMachine.canTransit(from, to)) {
                    cases.add(Arguments.of(from, to));
                }
            }
        }
        return cases.stream();
    }

    static Stream<Arguments> illegalTransitions() {
        List<Arguments> cases = new ArrayList<>();
        for (ReleaseStatus from : ReleaseStatus.values()) {
            for (ReleaseStatus to : ReleaseStatus.values()) {
                if (!ReleaseStateMachine.canTransit(from, to)) {
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
