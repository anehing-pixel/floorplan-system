package com.company.floorplan.domain.model;

import java.util.Set;

/**
 * 上游类型（ADR-007 决策二）。
 *
 * <p><b>为什么不是一个"厂家"枚举</b>：日本的经销商通常不直接向汽车工厂下单，
 * 而是通过制造商销售公司、区域总代理、批发公司、拍卖行、中古车批发商多层渠道采购。
 * 把上游统一建模为"厂家"，会让打款对象与担保能力的判断全部算错。</p>
 *
 * <p><b>本枚举的唯一职责是推导 {@link PayeeMode}</b>——这决定了放款走哪套校验链，
 * 是资金安全的关键分岔口。</p>
 */
public enum SupplierType {

    /** 制造商销售公司（自動車メーカーの販売会社）。担保能力通常最强，有厂商回购。 */
    MAKER_SALES_CO("制造商销售公司"),

    /** 区域总代理（地域総代理店）。中间层级，回购覆盖弱于厂商。 */
    REGIONAL_AGENCY("区域总代理"),

    /** 批发公司（卸売会社）。 */
    WHOLESALER("批发公司"),

    /** 拍卖行（オートオークション）。车辆来源非厂商，普遍无厂商回购。 */
    AUCTION("拍卖行"),

    /** 中古车批发商（中古車卸売業者）。 */
    USED_WHOLESALER("中古车批发商"),

    /**
     * 经销商自身（场景 C：已有库存做抵押补充周转）。
     *
     * <p>这是唯一会派生 {@link PayeeMode#SELF_PAY} 的类型——钱回经销商自己账户，
     * 属营运资金，三方委托付款不适用。</p>
     */
    DEALER_SELF("经销商自身");

    /** 走代付上游（直付代付协议链）的一组类型。 */
    private static final Set<SupplierType> DIRECT_PAY_TYPES = Set.of(
            MAKER_SALES_CO, REGIONAL_AGENCY, WHOLESALER, AUCTION, USED_WHOLESALER);

    private final String label;

    SupplierType(String label) {
        this.label = label;
    }

    /**
     * 推导打款模式。
     *
     * <p>这是<b>唯一</b>的打款模式推导入口。调用方不允许自己判断
     * "是不是代付"——那正是漏洞的来源。</p>
     *
     * @return {@link PayeeMode#DIRECT_PAY} 或 {@link PayeeMode#SELF_PAY}
     */
    public PayeeMode derivePayeeMode() {
        return DIRECT_PAY_TYPES.contains(this) ? PayeeMode.DIRECT_PAY : PayeeMode.SELF_PAY;
    }

    /** 是否走代付上游链路。 */
    public boolean isDirectPay() {
        return derivePayeeMode() == PayeeMode.DIRECT_PAY;
    }

    /** 是否为经销商自身（场景 C）。 */
    public boolean isDealerSelf() {
        return this == DEALER_SELF;
    }

    /** 中文展示名。 */
    public String getLabel() {
        return label;
    }
}
