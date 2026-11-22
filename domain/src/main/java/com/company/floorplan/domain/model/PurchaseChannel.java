package com.company.floorplan.domain.model;

/**
 * 采购渠道枚举（USED 域 R2.1／R2.4；列 {@code vehicle_units.purchase_channel}，V1:42 既有列）。
 *
 * <p><b>取值定源</b>：D00 §8.1 的 {@code PurchaseChannel} 五值，
 * 与 V40 的 {@code ck_vu_purchase_channel}（{@code OEM/AUCTION/WHOLESALE/TRADE_IN/OTHER}，
 * 均允许 NULL）逐字对齐——这就是 R13.4「枚举双层约束」在本号段的落法：
 * Java 枚举拦写入、列 CHECK 拦旁路 SQL，两者取值集必须相同。</p>
 *
 * <p><b>为什么现在才有这个枚举</b>：V1 建列时无 CHECK，LEGACY 全程按字符串写
 * （实测现状全 {@code OEM}），所以没有类型需求；R2.1 验收点要求
 * "非法 {@code purchase_channel} 被 400 拦"，USED 建账与导入必须先有可校验的类型。</p>
 *
 * <p><b>与 {@link SaleChannel} 是两回事</b>：本枚举描述<b>车怎么进来的</b>（采购侧），
 * {@code SaleChannel} 描述<b>车打算怎么出去</b>（销售侧，V50）。
 * 两者都有 {@code AUCTION} 字样的取值，混用会让"再落札进来的车"被写成"再落札卖出的车"。</p>
 *
 * <p><b>不动 LEGACY 写入路径</b>（L4 回归）：既有 {@code PurchaseOrderApplicationService} 的
 * {@code NEW_STOCK} 硬编码与 {@code VehicleController} 的 {@code OEM} 默认值按 AGENTS 派发纪律
 * 属禁区（D12 §4），本枚举只用于 USED 侧的入参校验；两处硬编码的删除登记为跨包待办。</p>
 */
public enum PurchaseChannel {

    /** 厂家渠道（新车 LEGACY 现状默认值）。 */
    OEM("厂家"),

    /** 拍卖场落札：USED 的主要来源，建账时必须带落札引用（R2.3 第一行「VIN 不在落札数据里 → 拒建账」的可实现形式）。 */
    AUCTION("拍卖场落札"),

    /** 批发转售（非拍卖场通道）。 */
    WHOLESALE("批发"),

    /** 置换收购。 */
    TRADE_IN("置换"),

    /** 其他：V40 注释要求"OTHER 必须带备注"（R2.1 验收点），由应用层拦空备注。 */
    OTHER("其他");

    /** 中文展示名。 */
    private final String label;

    PurchaseChannel(String label) {
        this.label = label;
    }

    /**
     * 中文展示名。
     *
     * @return 展示名
     */
    public String getLabel() {
        return label;
    }

    /**
     * 是否拍卖场来源（需要落札引用的一档）。
     *
     * @return {@code AUCTION} 返回 true
     */
    public boolean isAuctionSourced() {
        return this == AUCTION;
    }
}
