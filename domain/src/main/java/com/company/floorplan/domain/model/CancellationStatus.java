package com.company.floorplan.domain.model;

/**
 * 抹消登记状态（债务还清后的担保解除手续）。
 *
 * <p><b>为什么是一组状态而不是一个布尔字段</b>：日本让渡担保的抹消登记
 * <b>需权利人与登记义务人共同申请</b>（金融公司 + 经销商一起去法务局），
 * 不是一方能单方完成的动作。这意味着"债务已还清"与"担保已解除"之间
 * 存在<b>真实的待办区间</b>——这段时间里担保仍然挂着，
 * 权利仍受限制，登记也可能已过 5 年期限。
 * 用一个布尔位会把这个区间抹平，导致"已结清但未抹消"的担保被漏管。</p>
 *
 * <p>新法（《譲渡担保契約及び所有権留保契約に関する法律》，2025-05-30 成立、
 * 2025-06-06 公布，施行日最晚 2027-12-06）施行后，
 * 担保权的清算与优先顺位更明确，但**内部证据留存与登记管理的重要性不变**——
 * 本状态机就是这套内部留存的落点。</p>
 */
public enum CancellationStatus {

    /**
     * 未到抹消时点：登记已设立、担保存续中，但债务尚未还清，不需要抹消。
     *
     * <p><b>为什么必须与 {@link #NOT_REQUIRED} 区分开</b>（这是踩过的坑）：
     * 两者都表示"现在不需要抹消"，但含义完全相反——</p>
     * <ul>
     *   <li>{@code NOT_DUE} = <b>将来需要抹消</b>，只是还没到时间。
     *       债务还清后必须从它流转到 {@code REQUESTED}，产生待办。</li>
     *   <li>{@code NOT_REQUIRED} = <b>永远不需要抹消</b>（如厂家回购结清、
     *       非登记方式担保），债务还清后也不产生待办。</li>
     * </ul>
     * <p>若用同一个值表达两者，登记刚建立时（默认状态）会被误判为"无需抹消"，
     * 导致<b>债务结清后不产生抹消待办</b>——担保在登记簿上一直挂着却无人跟进。
     * 这正是让渡担保监管最忌讳的漏管。</p>
     */
    NOT_DUE("未到抹消时点"),

    /** 不需抹消（如厂家回购结清、无登记场景）——确定永远不需要抹消。 */
    NOT_REQUIRED("不需抹消"),

    /** 待申请：债务已还清，已生成抹消待办，等待共同申请。 */
    REQUESTED("待申请抹消（需双方共同申请）"),

    /** 已提交抹消申请：材料已递至法务局，等待登记完成。 */
    APPLIED("已提交抹消申请"),

    /** 抹消完成：已取得抹消凭证（謄本）并归档。 */
    DONE("抹消完成"),

    /** 免于抹消：经审批无需办理（如权利已因其他原因消灭）。 */
    CANCELLED("免于抹消");

    private final String label;

    CancellationStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 是否处于待办状态（需人工推进）。
     *
     * @return true 表示待办
     */
    public boolean isPending() {
        return this == REQUESTED || this == APPLIED;
    }

    /**
     * 是否已完成（担保在登记簿上不再挂着）。
     *
     * @return true 表示已完成或免于办理
     */
    public boolean isClosed() {
        return this == DONE || this == CANCELLED || this == NOT_REQUIRED;
    }

    /**
     * 是否需要办理抹消（债务消灭后是否要产生待办）。
     *
     * <p>{@code NOT_REQUIRED} 与 {@code CANCELLED} 返回 false——这两种情形下
     * 担保不会在登记簿上挂着，债务消灭也不需要办抹消。</p>
     *
     * @return true 表示债务消灭后应产生抹消待办
     */
    public boolean requiresCancellation() {
        return this != NOT_REQUIRED && this != CANCELLED && this != DONE;
    }
}
