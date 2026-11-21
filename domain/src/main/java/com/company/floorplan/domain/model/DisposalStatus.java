package com.company.floorplan.domain.model;

/**
 * 违约处置工单状态（业务闭环 P0-② 违约处置）。
 *
 * <p><b>流转白名单</b>（非法流转抛 {@code DISPOSAL_002}）：
 * <pre>
 *   OPEN              → NOTIFIED, REPOSSESSED, CANCELLED, CLOSED_*
 *   NOTIFIED          → LEGAL_ACTION, REPOSSESSED, CANCELLED, CLOSED_*
 *   LEGAL_ACTION      → REPOSSESSED, CANCELLED, CLOSED_*
 *   REPOSSESSED       → LIQUIDATING, CANCELLED, CLOSED_*
 *   LIQUIDATING       → CLOSED_*
 *   CLOSED_* / CANCELLED → （终态）
 * </pre>
 * 设计意图：开单后要么走通知/法律程序、要么直接收车；收车后进入变现；
 * 变现结束形成"收回（残值覆盖应收）"或"损失（残值不足）"两种终态。
 * T2-6 补三态：NOTIFIED（已通知，进入补救期）、LEGAL_ACTION（法律程序中）、
 * CANCELLED（补救期内还清或和解 → 撤销处置，非单向门）。</p>
 */
public enum DisposalStatus {

    /** 已开单（待收车 / 待通知）。 */
    OPEN,
    /** T2-6：违约通知已送达（含送达证明），进入补救期。 */
    NOTIFIED,
    /** T2-6：法律程序中（已委托律师 / 强制执行 / 竞拍）。 */
    LEGAL_ACTION,
    /** 已收车（车辆已回收至金融公司/监管方）。 */
    REPOSSESSED,
    /** 变现中（已收车，正在处置/拍卖）。 */
    LIQUIDATING,
    /** 终态：残值覆盖应收，收回结案。 */
    CLOSED_RECOVERED,
    /** 终态：认定损失结案。 */
    CLOSED_LOSS,
    /** T2-6 终态：客户补救期内还清或和解，撤销处置。 */
    CANCELLED;

    /** 是否终态（终态不可再流转）。 */
    public boolean isTerminal() {
        return this == CLOSED_RECOVERED || this == CLOSED_LOSS || this == CANCELLED;
    }

    /**
     * 判断能否流转到目标状态。
     *
     * @param target 目标状态
     * @return 白名单内返回 true
     */
    public boolean canTransitTo(DisposalStatus target) {
        switch (this) {
            case OPEN:
                return target == NOTIFIED || target == REPOSSESSED || target == CANCELLED
                        || target == CLOSED_RECOVERED || target == CLOSED_LOSS;
            case NOTIFIED:
                return target == LEGAL_ACTION || target == REPOSSESSED || target == CANCELLED
                        || target == CLOSED_RECOVERED || target == CLOSED_LOSS;
            case LEGAL_ACTION:
                return target == REPOSSESSED || target == CANCELLED
                        || target == CLOSED_RECOVERED || target == CLOSED_LOSS;
            case REPOSSESSED:
                return target == LIQUIDATING || target == CANCELLED
                        || target == CLOSED_RECOVERED || target == CLOSED_LOSS;
            case LIQUIDATING:
                return target == CLOSED_RECOVERED || target == CLOSED_LOSS;
            default:
                return false; // CLOSED_* / CANCELLED 终态不可再流转
        }
    }
}
