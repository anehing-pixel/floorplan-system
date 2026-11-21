package com.company.floorplan.loan.model;

/** 还款计划状态（V16 repayment_plans.status）。 */
public enum RepaymentPlanStatus {
    /** 待还。 */
    PENDING,
    /** 部分还。 */
    PARTIAL,
    /** 已还清。 */
    PAID,
    /** 已逾期（到期未足额）。 */
    OVERDUE,
    /** 核销。 */
    WRITTEN_OFF
}
