package com.company.floorplan.domain.model;

/**
 * USED 域放车指令的<b>项</b>状态（六值）。
 *
 * <p><b>取值源是数据库，不是文档</b>：V68__release_item_receiver.sql:61-62 的
 * {@code chk_roi_item_status} 钉死这六个值。之所以是六而不是五——
 * D03:321 的 ItemStatus 只列了 5 个，而 D09:232 在 RL-2 的判定里用了
 * {@code item_status='ACKNOWLEDGED'}，两边都是规格，取并集才不与任一边冲突
 * （已登记为自审矛盾，见实现说明）。D07 §7.6 的下发重校验表也要求项上有
 * INSTRUCTED（"每个 item 仍处可释放态（未被并发取消）"这一行的判据就是项状态）。</p>
 *
 * <p><b>与主状态的关系</b>：项状态是事实，主状态是聚合结论
 * （「取最不利」，见 {@code UsedReleaseStatus#PARTIAL}）。
 * 二者不同层，不许互相赋值。</p>
 *
 * <p>约束落点：V68__release_item_receiver.sql:61-62 的 {@code chk_roi_item_status}。</p>
 */
public enum UsedReleaseItemStatus {

    /** 初始：随指令创建，尚未下发。 */
    PENDING("待处理"),

    /** 已随指令下发给对方（下发时逐项重校验通过后写入，D07 §7.6）。 */
    INSTRUCTED("已指令"),

    /** 对方已受理该项（D09:232，RL-2 的判据）。 */
    ACKNOWLEDGED("对方已受理"),

    /** 该项实放完成，有对应回执。 */
    RELEASED("已释放"),

    /** 该项本次未放（部分释放时未覆盖的项；与 REFUSED 的区别是"没做"而不是"拒绝"）。 */
    NOT_RELEASED("本次未释放"),

    /** 对方拒绝该项（需 refuse_reason，V68 列 + D09 §7.2）。 */
    REFUSED("已拒绝");

    private final String label;

    UsedReleaseItemStatus(String label) {
        this.label = label;
    }

    /** 中文标签。 */
    public String getLabel() {
        return label;
    }

    /** 是否仍处"可被下发/可被释放"的活态（D07 §7.6 重校验清单第 4 行的判据）。 */
    public boolean isReleasable() {
        return this == PENDING || this == INSTRUCTED || this == ACKNOWLEDGED;
    }

    /** 是否已是终态项（终态项不得再被回执改写，防重复核销同一项）。 */
    public boolean isFinal() {
        return this == RELEASED || this == REFUSED;
    }

    /** 按数据库文本解析；空值返回 null 由调用方决定默认，未知值抛 IllegalArgumentException。 */
    public static UsedReleaseItemStatus from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
