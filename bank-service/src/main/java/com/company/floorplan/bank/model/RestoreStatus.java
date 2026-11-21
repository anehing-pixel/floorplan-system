package com.company.floorplan.bank.model;

/** 额度回补状态。匹配成功才回补，到账不回补（设计稿 §3.8）。 */
public enum RestoreStatus {

    /** 待回补。 */
    PENDING,
    /** 已回补。 */
    RESTORED,
    /** 回补失败，需重试且不得重复扣减。 */
    FAILED;

    public String code() {
        return name();
    }
}
