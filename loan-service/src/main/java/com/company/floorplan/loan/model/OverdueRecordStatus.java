package com.company.floorplan.loan.model;

/** 逾期记录状态（V16 overdue_records.status）。 */
public enum OverdueRecordStatus {
    /** 逾期中。 */
    OPEN,
    /** 已补还。 */
    RESOLVED,
    /** 已升级（阶段升档或转处置）。 */
    ESCALATED,
    /** 已转违约处置。 */
    DEFAULTED
}
