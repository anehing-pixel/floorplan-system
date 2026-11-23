package com.company.floorplan.domain.model;

/**
 * 产品线：同一张 {@code release_orders} 表上区分 LEGACY 与 USED 两套语义的开关列。
 *
 * <p>落库列是 V67 加的 {@code release_orders.product_line VARCHAR(12)}
 * （D03:470 与 D04 §2 的 V40 语义都要求分线，但 D04 §5.2 的 V67 行漏列，已补并登记）。
 * 默认 {@link #LEGACY}，保证存量行与旧链路行为不变。</p>
 *
 * <p><b>它决定的是"同一句指令的含义"，不是"数据长什么样"</b>，共三处分叉：</p>
 * <ul>
 *   <li>{@code INTERNAL_WITNESS} 能否推结清：LEGACY 可以（V24 折中，A2.5 要求不破），
 *       USED 不可以（A2.4）；</li>
 *   <li>{@code LIEN_DISCHARGED} 的到达条件：LEGACY 到 RELEASED 即算解押完成，
 *       USED 必须等登记抹消完成回执（D09 两段式，{@code [待定源 D-07]}）；</li>
 *   <li>状态表选哪张：LEGACY 走 {@code ReleaseStateMachine} 的 6×6，
 *       USED 走 {@code UsedReleaseStateMachine} 的 10 态。</li>
 * </ul>
 */
public enum ReleaseProductLine {

    /** 存量库存融资线：行为与本轮之前完全一致。 */
    LEGACY("存量库存融资"),

    /** 二手车库存垫资线：本波次新增语义只在这条线上生效。 */
    USED("二手车库存垫资");

    private final String label;

    ReleaseProductLine(String label) {
        this.label = label;
    }

    /** 中文标签。 */
    public String getLabel() {
        return label;
    }

    /** 解析：列值为 NULL 或空时按 LEGACY 处理——这是存量行的真实形态，不是猜的默认值。 */
    public static ReleaseProductLine from(String value) {
        if (value == null || value.isBlank()) {
            return LEGACY;
        }
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }

    /** 是否走 USED 的两段式解押与 PARTNER_RECEIPT 强约束。 */
    public boolean isUsed() {
        return this == USED;
    }
}
