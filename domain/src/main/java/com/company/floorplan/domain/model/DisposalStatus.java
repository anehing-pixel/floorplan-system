package com.company.floorplan.domain.model;

/**
 * 违约处置工单状态（业务闭环 P0-② 违约处置）。
 *
 * <p><b>流转白名单</b>（非法流转抛 {@code DISPOSAL_002}）：
 * <pre>
 *   OPEN              → REPOSSESSED, CLOSED_RECOVERED, CLOSED_LOSS
 *   REPOSSESSED       → LIQUIDATING, CLOSED_RECOVERED, CLOSED_LOSS
 *   LIQUIDATING       → CLOSED_RECOVERED, CLOSED_LOSS
 *   CLOSED_*          → （终态）
 * </pre>
 * 设计意图：开单后要么收车、要么直接认定（收回/损失）；收车后进入变现；
 * 变现结束形成"收回（残值覆盖应收）"或"损失（残值不足）"两种终态。</p>
 */
public enum DisposalStatus {

    /** 已开单（待收车 / 待认定）。 */
    OPEN,
    /** 已收车（车辆已回收至金融公司/监管方）。 */
    REPOSSESSED,
    /** 变现中（已收车，正在处置/拍卖）。 */
    LIQUIDATING,
    /** 终态：残值覆盖应收，收回结案。 */
    CLOSED_RECOVERED,
    /** 终态：认定损失结案。 */
    CLOSED_LOSS;

    /** 是否终态（终态不可再流转）。 */
    public boolean isTerminal() {
        return this == CLOSED_RECOVERED || this == CLOSED_LOSS;
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
                return target == REPOSSESSED || target == CLOSED_RECOVERED || target == CLOSED_LOSS;
            case REPOSSESSED:
                return target == LIQUIDATING || target == CLOSED_RECOVERED || target == CLOSED_LOSS;
            case LIQUIDATING:
                return target == CLOSED_RECOVERED || target == CLOSED_LOSS;
            default:
                return false; // 终态不可再流转
        }
    }
}
