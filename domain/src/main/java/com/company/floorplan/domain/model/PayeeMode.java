package com.company.floorplan.domain.model;

/**
 * 打款模式（ADR-007 决策二）。
 *
 * <p><b>关键约束：这是 {@link SupplierType} 的派生值，不是独立可编辑字段。</b>
 * 若让操作员能直接选择打款模式，"选 {@code SELF_PAY} 就绕开三方委托付款协议"
 * 就成了一个现成的漏洞——协议校验、授权上限、账户管控会全部失效。</p>
 *
 * <p>因此：人工可以改 {@code supplierType}（有审核流），但打款模式永远由它推导。</p>
 */
public enum PayeeMode {

    /**
     * 代付上游：钱由金融公司直接付给制造商销售公司 / 区域总代理 / 批发公司 /
     * 拍卖行 / 中古车批发商。融资用途是"采购车辆"，资金直接覆盖采购款。
     *
     * <p>适用时必须校验：三方委托付款协议已生效、账户在授权上限内、账户已过冷静期。</p>
     */
    DIRECT_PAY("代付上游"),

    /**
     * 回款经销商：经销商已有库存，用库存做抵押补充周转，钱回到经销商自身账户。
     *
     * <p><b>这是营运资金，不是代付采购款</b>——三方委托付款协议不适用。
     * 相应地换成另一套校验：要求库存实物盘点在库、按库存估值 × 独立抵押率计、
     * 账户变更管控更严（T+2 + 双签）、审计标注"非代付、属营运资金"。</p>
     */
    SELF_PAY("回款经销商");

    private final String label;

    PayeeMode(String label) {
        this.label = label;
    }

    /** 中文展示名。 */
    public String getLabel() {
        return label;
    }
}
