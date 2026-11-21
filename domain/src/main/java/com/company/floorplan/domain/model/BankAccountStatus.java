package com.company.floorplan.domain.model;

/**
 * 收款账户状态。
 *
 * <p>账户状态直接影响放款：{@code FROZEN}（已冻结）或 {@code CLOSED}（已关闭）的账户
 * 不得作为放款收款方。</p>
 */
public enum BankAccountStatus {

    /** 生效中：可作为收款方。 */
    ACTIVE("生效中"),

    /** 待复核：录入后等待双人复核，未生效前不可用。 */
    PENDING_REVIEW("待复核"),

    /** 已冻结：暂停使用（风控发现异常时冻结）。 */
    FROZEN("已冻结"),

    /** 已关闭：终止使用（终态）。 */
    CLOSED("已关闭");

    private final String label;

    BankAccountStatus(String label) {
        this.label = label;
    }

    /** 是否可用于放款收款。 */
    public boolean isUsable() {
        return this == ACTIVE;
    }

    /** 中文展示名。 */
    public String getLabel() {
        return label;
    }
}
