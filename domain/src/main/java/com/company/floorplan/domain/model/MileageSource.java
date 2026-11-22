package com.company.floorplan.domain.model;

/**
 * 里程来源（R2.3 里程可追溯性的判定词汇）。
 *
 * <p><b>取值定源</b>：D00 §8.1 {@code MileageSource} 🆕——
 * {@code AUCTION_REPORT · WMS_INBOUND · OWN_AUDIT · DEALER_DECLARED · UNKNOWN}，「本集合冻结」。
 * DB 侧双层约束在 V50 的 {@code ck_vu_mileage_source}（允许 NULL）。</p>
 *
 * <p><b>{@code NULL} 与 {@code UNKNOWN} 是两种事实</b>（D00 §8.1 原文）：
 * {@code NULL} = 从未采集（只允许出现在 LEGACY 存量行），
 * {@code UNKNOWN} = 采集过但不可信，此时 {@code mileage} 必须为 NULL
 * （V50 的 {@code ck_vu_mileage_unknown} 用 {@code IS DISTINCT FROM} 守住这条，
 * 存量行 {@code mileage_source IS NULL} 时该约束恒真）。
 * 两者在 D08 §4.1 取带时行为一致（都按最保守档），但数据层不得合并成一类。</p>
 *
 * <p><b>与 D06 §2.4 优先级链的命名差异</b>：链里写的
 * {@code WMS_SNAPSHOT / AUCTION_TICKET / REGISTRY / MANUAL} 不在定源集合内，
 * 映射与缺档说明在 {@code MileageResolutionPolicy} 的类注释里，冲突已上报。</p>
 */
public enum MileageSource {

    /** 拍卖场落札报告票面值（D06 §2.4 称 {@code AUCTION_TICKET}）。 */
    AUCTION_REPORT("落札报告"),

    /** WMS 实物观测（入库确认与在库快照共用本值）。优先级最高档。 */
    WMS_INBOUND("WMS 实物观测"),

    /** 自盘实测。 */
    OWN_AUDIT("自盘实测"),

    /** 车商申报值（D06 §2.4 的 {@code MANUAL} 近义）。 */
    DEALER_DECLARED("车商申报"),

    /** 采集过但不可信：{@code mileage} 必须为 NULL。 */
    UNKNOWN("不可信");

    /** 中文展示名。 */
    private final String label;

    MileageSource(String label) {
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
