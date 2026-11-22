package com.company.floorplan.domain.model;

/**
 * 售出渠道（USED 域 R2.1；结算窗口与回款路径的分支键，R9.2 / R8.3）。
 *
 * <p><b>取值定源</b>：D00 §8.1 的 {@code SaleChannel} 🆕 行——
 * {@code RETAIL · WHOLESALE_RESELL · AUCTION_RELIST}，「本集合冻结」；
 * DB 侧双层约束在 V50 的 {@code ck_vu_sale_channel}（允许 NULL）。</p>
 *
 * <p><b>为什么本包只建列不建行为</b>：D00 §8.1 说明本枚举「决定结算窗口与回款路径」，
 * 而窗口天数属 R9.2 的待定源（D00 §16 的 U 系列），回款路径属 R8/R9 的 W-U3/W-U6 交付物。
 * 本包（R1＋R2＋R3.1 字段定义）负责把 USED 主数据的这枚分支键建成受约束的列并给出枚举类型，
 * <b>不</b>据它推导任何窗口或路径——那是并行包的接口约定，不是本包的实现内容。</p>
 *
 * <p>LEGACY 侧的售出登记（{@code POST /api/vehicles/{vin}/sold}）不写本列，
 * 本列的写入者是 USED 建账与售出登记链路。</p>
 */
public enum SaleChannel {

    /** 零售（终端客户）。 */
    RETAIL("零售"),

    /** 批发转售（对车商）。 */
    WHOLESALE_RESELL("批发转售"),

    /** 再落札（回拍卖场重出）。 */
    AUCTION_RELIST("再落札");

    /** 中文展示名。 */
    private final String label;

    SaleChannel(String label) {
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
}
