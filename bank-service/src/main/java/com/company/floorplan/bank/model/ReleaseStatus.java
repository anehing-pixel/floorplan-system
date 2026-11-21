package com.company.floorplan.bank.model;

/** 解押状态。RELEASED 指担保实体权利消灭，不等于登记簿抹消完成。 */
public enum ReleaseStatus {

    /** 待解押。 */
    PENDING,
    /** 已解押（担保实体权利消灭）。 */
    RELEASED,
    /** 解押失败，需强制工单重试（设计稿 §4.4 禁止静默失败）。 */
    FAILED;

    public String code() {
        return name();
    }
}
