package com.company.floorplan.collateral.model;

/** 权属流水事件类型：对应 collateral_registration_history.event_type。 */
public enum RegistrationEventType {
    REGISTER,
    CHANGE,
    DISCHARGE,
    /** 权威源策略裁决后落定的权属（仅策略启用时产生）。 */
    SNAPSHOT_ADOPT
}
