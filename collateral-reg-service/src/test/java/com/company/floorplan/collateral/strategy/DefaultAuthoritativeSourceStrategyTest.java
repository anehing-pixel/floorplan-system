package com.company.floorplan.collateral.strategy;

import com.company.floorplan.collateral.model.RegistrationStatus;
import com.company.floorplan.collateral.model.SourceSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static com.company.floorplan.collateral.model.RegistrationStatus.CANCELLED;
import static com.company.floorplan.collateral.model.RegistrationStatus.NONE;
import static com.company.floorplan.collateral.model.RegistrationStatus.PENDING;
import static com.company.floorplan.collateral.model.RegistrationStatus.REGISTERED;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 三源裁决逻辑单测：即使策略默认关闭，也要证明开启时的裁决行为可用。 */
class DefaultAuthoritativeSourceStrategyTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 0, 0);

    @ParameterizedTest(name = "{0}")
    @MethodSource("decisionCases")
    @DisplayName("开关与三源组合 -> 裁决结果")
    void decideCases(String name, boolean enabled, List<SourceSnapshot> snapshots,
                     boolean expectedAdopted, RegistrationStatus expectedStatus, String expectedSource) {
        AuthoritativeSourceStrategy.Decision d =
                new DefaultAuthoritativeSourceStrategy(enabled).decide("VIN123", snapshots);
        assertEquals(expectedAdopted, d.isAdopted(), name + "：是否采纳");
        assertEquals(expectedStatus, d.getStatus(), name + "：状态");
        assertEquals(expectedSource, d.getSource(), name + "：采信来源");
    }

    static Stream<Arguments> decisionCases() {
        return Stream.of(
                Arguments.of("策略关闭：三源均 REGISTERED 也不采纳",
                        false,
                        List.of(snap("REGISTRATION_BATCH", REGISTERED, "R1"),
                                snap("COLLATERAL_LEDGER", REGISTERED, "R1")),
                        false, NONE, null),

                Arguments.of("开启+空快照：无可采信数据，保持原状",
                        true, List.of(), false, NONE, null),

                Arguments.of("开启+三源一致 REGISTERED：直接采纳",
                        true,
                        List.of(snap("REGISTRATION_BATCH", REGISTERED, "R1"),
                                snap("COLLATERAL_LEDGER", REGISTERED, "R1"),
                                snap("VEHICLE_UNITS", REGISTERED, "R1")),
                        true, REGISTERED, "REGISTRATION_BATCH"),

                Arguments.of("开启+三源一致 NONE：采纳为无担保",
                        true,
                        List.of(snap("REGISTRATION_BATCH", NONE, null),
                                snap("COLLATERAL_LEDGER", NONE, null),
                                snap("VEHICLE_UNITS", NONE, null)),
                        true, NONE, "REGISTRATION_BATCH"),

                Arguments.of("开启+冲突：批次表 REGISTERED 胜过台账 CANCELLED/车辆 NONE",
                        true,
                        List.of(snap("REGISTRATION_BATCH", REGISTERED, "R1"),
                                snap("COLLATERAL_LEDGER", CANCELLED, "R2"),
                                snap("VEHICLE_UNITS", NONE, null)),
                        true, REGISTERED, "REGISTRATION_BATCH"),

                Arguments.of("开启+冲突：批次表 NONE 仍优先于其他源 REGISTERED",
                        true,
                        List.of(snap("REGISTRATION_BATCH", NONE, null),
                                snap("COLLATERAL_LEDGER", REGISTERED, "R2"),
                                snap("VEHICLE_UNITS", REGISTERED, "R3")),
                        true, NONE, "REGISTRATION_BATCH"),

                Arguments.of("开启+批次表缺失：台账 REGISTERED 优先于车辆 PENDING",
                        true,
                        List.of(snap("COLLATERAL_LEDGER", REGISTERED, "R2"),
                                snap("VEHICLE_UNITS", PENDING, null)),
                        true, REGISTERED, "COLLATERAL_LEDGER"),

                Arguments.of("开启+仅车辆源 REGISTERED：采信车辆源",
                        true,
                        List.of(snap("VEHICLE_UNITS", REGISTERED, "R3")),
                        true, REGISTERED, "VEHICLE_UNITS"),

                Arguments.of("开启+未知来源：不可采信",
                        true,
                        List.of(snap("UNKNOWN_SRC", REGISTERED, "RX")),
                        false, NONE, null)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("latestCases")
    @DisplayName("同源多份快照：取最新一份")
    void latestSnapshotWins(String name, SourceSnapshot older, SourceSnapshot newer,
                            RegistrationStatus expectedStatus) {
        AuthoritativeSourceStrategy.Decision d =
                new DefaultAuthoritativeSourceStrategy(true).decide("VIN", List.of(older, newer));
        assertEquals(expectedStatus, d.getStatus(), name);
    }

    static Stream<Arguments> latestCases() {
        return Stream.of(Arguments.of(
                "同源取 capturedAt 最新",
                snap("COLLATERAL_LEDGER", REGISTERED, "OLD", T0.minusDays(1)),
                snap("COLLATERAL_LEDGER", CANCELLED, "NEW", T0),
                CANCELLED));
    }

    private static SourceSnapshot snap(String source, RegistrationStatus status, String regNo) {
        return snap(source, status, regNo, T0);
    }

    private static SourceSnapshot snap(String source, RegistrationStatus status, String regNo,
                                       LocalDateTime capturedAt) {
        SourceSnapshot s = new SourceSnapshot();
        s.setSource(source);
        s.setStatus(status);
        s.setRegNo(regNo);
        s.setOwnerName("权利人-" + source);
        s.setCapturedAt(capturedAt);
        return s;
    }
}
