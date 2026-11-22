package com.company.floorplan.domain.model;

/**
 * 証書 custody 事件類型（D04 §3.10 的 {@code certificate_custody_events.event_type} 九值）。
 *
 * <p><b>取值定源</b>：D04 §3.10（R2.5 / R5.3）——
 * {@code RECEIVED · PLEDGED · TRANSFERRED · RELEASED · REISSUED · LOST ·
 * AMENDED · ENCUMBRANCE_FOUND · CHECK_DONE}，V52 的 {@code ck_cce_event_type} 同集合。</p>
 *
 * <p><b>本枚举比 {@link CertificateStatus} 多三个值，不是口径漂移</b>：
 * 台账事件与主状态是两件事（D04 §3.10 的表就是为此存在的）——
 * {@code TRANSFERRED}（需求书原词，状态侧已按 D00 §8.2 归并进 {@code RELEASED}）
 * 在事件流里必须留原名，否则历史报文回放会对不上；
 * {@code ENCUMBRANCE_FOUND} 与 {@code CHECK_DONE} 是核查动作的留痕，
 * <b>不改变</b>証書状态；
 * {@code AMENDED} 承担 D03 §3.2 图里的"换证／記載変更在途"——
 * 状态枚举没有第七态，在途这件事只能由事件表达（V52 表末注释同此）。</p>
 */
public enum CustodyEventType {

    /** 收证。 */
    RECEIVED("收证"),

    /** 设质。 */
    PLEDGED("设质"),

    /** 需求书原词：移转／交还动作的事件留痕。 */
    TRANSFERRED("移转"),

    /** 交还完成。 */
    RELEASED("交还"),

    /** 换证：旧证作废、新证生效。 */
    REISSUED("换证"),

    /** 灭失申报。 */
    LOST("灭失"),

    /** 記載変更／换证在途（状态侧无对应第七态，见类注释）。 */
    AMENDED("记载变更"),

    /** 核查发现权利瑕疵（不改状态）。 */
    ENCUMBRANCE_FOUND("发现权利瑕疵"),

    /** 一次核查完成（不改状态）。 */
    CHECK_DONE("核查完成");

    /** 中文展示名。 */
    private final String label;

    CustodyEventType(String label) {
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
