package com.company.floorplan.release.model;

/**
 * 出库指令状态（任务卡 T2-9 口径，优先于设计稿 §1.2⑤ 的 ISSUED/ACKNOWLEDGED 取值）。
 *
 * <p><b>为什么是六态而不是一个布尔"已放/未放"</b>：
 * 放车放证是跨机构的异步协同——我方发出、对方确认、实际放车、质押解除
 * 分别发生在<b>不同时间、不同责任人</b>身上。压成一个布尔就丢失了
 * "钱已到、指令已发、车还没交"这个中间态，巡检也无法发现账实缺口。</p>
 *
 * <p><b>迁移白名单由 {@code ReleaseStateMachine} 单独定义</b>，本枚举只承载状态语义与终态判定，
 * 避免把规则藏在枚举里又藏在服务里两份（与 ADR-002「禁止把业务规则藏在框架回调中」同精神）。</p>
 */
public enum ReleaseStatus {

    /** 待下发：指令已建档，尚未通过 {@code ReleaseChannel} 发给接收方。 */
    PENDING_RELEASE,

    /** 已下发：指令已送达接收方，等待对方回执确认。 */
    SENT,

    /** 已确认：接收方回执确认"收到指令、可以放车"。<b>放车的前置状态</b>。 */
    CONFIRMED,

    /** 已放车：车辆已实际交付。<b>解抵的前置状态</b>。 */
    RELEASED,

    /** 已解押：质押已解除、指令走完。<b>终态</b>。 */
    LIEN_DISCHARGED,

    /** 已撤销：未放车前取消指令。<b>终态</b>。 */
    CANCELLED;

    /** 终态集合：进入后不允许再流转，也不允许撤销。 */
    public boolean isTerminal() {
        return this == LIEN_DISCHARGED || this == CANCELLED;
    }
}
