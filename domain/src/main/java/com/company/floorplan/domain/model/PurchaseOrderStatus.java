package com.company.floorplan.domain.model;

/**
 * 采购订单状态。
 *
 * <p>主单是融资放款的<b>前置凭证</b>，因此状态从"已确认"开始，
 * 而不是从草稿开始——进入本系统的采购订单都是已发生的真实采购。</p>
 */
public enum PurchaseOrderStatus {

    /** 草稿：已导入但尚未确认（Excel 通道走 preview 时不落主单，故本状态主要用于人工补录）。 */
    DRAFT("草稿"),

    /** 已确认：主单已生效，可作为放款前置依据。 */
    CONFIRMED("已确认"),

    /** 已放款：关联的垫款已支付。 */
    DISBURSED("已放款"),

    /** 已结清：本单项下车辆全部结清。 */
    SETTLED("已结清"),

    /** 已取消：订单作废（终态）。 */
    CANCELLED("已取消");

    private final String label;

    PurchaseOrderStatus(String label) {
        this.label = label;
    }

    /** 中文展示名。 */
    public String getLabel() {
        return label;
    }
}
