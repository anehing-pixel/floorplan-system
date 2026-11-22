package com.company.floorplan.collateral.model;

/** 权属流水事件类型：对应 collateral_registration_history.event_type。 */
public enum RegistrationEventType {
    REGISTER,
    CHANGE,
    DISCHARGE,
    /** 快照裁决留痕（ADR-Q27 后策略只诊断不改账，保留取值供历史数据兼容）。 */
    SNAPSHOT_ADOPT
}
