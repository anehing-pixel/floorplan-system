package com.company.floorplan.domain.model;

/**
 * 車検証状态（USED 域 R2.5 / R5；取值定源 = D00 §8.2，转移定源 = D03 §3.2）。
 *
 * <p><b>六态是本枚举的全部值</b>：D00 §8.2 把需求书的
 * {@code HELD→PLEDGED→TRANSFERRED→RELEASED} 细化为可运维的 6 态（{@code [待定源 U-02]}），
 * 且明确「{@code TRANSFERRED} 归并入 {@code RELEASED} + {@code registration_applications} 结果」。
 * D04 的表规格同步为 6 态（V52 的 {@code ck_cert_status CHECK}）。</p>
 *
 * <p><b>⚠ 与 D03 §3.2 的图不一致（已登记为文档冲突，本包不自行扩值）</b>：
 * D03 §3.2 的状态图里出现第七态 {@code AMENDED_IN_PROGRESS}（記載変更/換証在途），
 * 但 D00 §8.2 的六值清单标注「本集合冻结」，D04 的 CHECK 也只有六值。
 * 处理方式：<b>本枚举保持六值</b>，"换证在途"这一事实由
 * {@code certificate_custody_events.event_type = 'AMENDED'}（V52 的九值之一，
 * 定源 D04 §3.10）表达，旧证在新证到达时置 {@code REISSUED}。
 * 反过来（往枚举与 CHECK 里加第七值）需要 D00 §8.2 + D04 + D03 三处同步，
 * 属未签字口径扩张，不在本包擅自落地。</p>
 *
 * <p>状态转移白名单与硬阻断规则在 {@code CertificateStateMachine}（本包唯一实现的状态机）。</p>
 */
public enum CertificateStatus {

    /** 已收证、未设质（D00 §8.2「已收证未设质」）。 */
    RECEIVED("已收证"),

    /** 已设质（质押成立）。 */
    PLEDGED("已设质"),

    /** 已设质、债权消灭后待交还（放证待办）。 */
    RELEASE_PENDING("待交还"),

    /** 已交还（终态；需求书的 {@code TRANSFERRED} 归并进本值）。 */
    RELEASED("已交还"),

    /** 换证后作废的旧证。 */
    REISSUED("换证作废"),

    /** 灭失（终态，触发车检证补发流程）。 */
    LOST("灭失");

    /** 中文展示名。 */
    private final String label;

    CertificateStatus(String label) {
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
     * 是否终态（D03 §3.2「禁止 {@code RELEASED -> *}」，{@code LOST} 同样只作终点）。
     *
     * <p>{@code REISSUED} 也归入不可再流转：被换掉的旧证一旦作废，
     * 后续任何状态写入都会破坏 {@code predecessor_id} 换证链的可追溯性。</p>
     *
     * @return 终态返回 true
     */
    public boolean isTerminal() {
        return this == RELEASED || this == LOST || this == REISSUED;
    }
}
