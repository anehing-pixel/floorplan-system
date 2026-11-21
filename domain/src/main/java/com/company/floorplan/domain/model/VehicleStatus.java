package com.company.floorplan.domain.model;

import java.util.Set;

/**
 * 车辆单元生命周期状态（对应设计文档 2.7）。
 *
 * <p><b>与风险标记的区分（重要）</b>：
 * "逾期天数"是<b>风险标记</b>字段（见 {@link VehicleUnit#getOverdueDays()}），不是状态；
 * 此处的 {@link #OVERDUE} 表示<b>已进入逾期处置流程</b>。
 * 两者分离，避免"逾期到底是状态还是标记"的建模歧义。</p>
 *
 * <p><b>实现方式</b>：流转白名单内置在枚举中（{@link #canTransitTo(VehicleStatus)}），
 * <b>不使用框架回调或状态机库的等价物</b>——
 * 这是 ADR-002"禁止把业务规则藏在框架回调中"的直接落地
 * （Rails 时代的 callback 债不得在 Java 重现）。</p>
 */
public enum VehicleStatus {

    /**
     * 已建账：台账刚建立，<b>既未申请垫款、也未付款</b>。这是生命周期的<b>真正起点</b>。
     *
     * <p><b>为什么起点不是 {@link #PENDING_ADVANCE}</b>：建账（批量到货入库 / 采购订单导入）
     * 之后，采购审核岗还要<b>线下验车确权</b>——核对车架号、实车配置、随车凭证、
     * 权属是否干净。这一关没过之前，业务上<b>不该进入垫款申请</b>；
     * 而"申请垫款"这个动作本身（{@code applyAdvance}）才是
     * {@link #PENDING_ADVANCE} 的触发点。</p>
     *
     * <p>若把建账直接落成 {@link #PENDING_ADVANCE}，则在业务视角上
     * "已申请待放款"与"刚建完账还没申请"混为一谈——
     * 待放款工作台会把还没验车的车也列进去，出纳可能对一台没确权的车付款。</p>
     *
     * <p><b>不计息</b>：未建账放款则资金未占用，见 {@link #isInterestBearing()}。</p>
     */
    CREATED,

    /**
     * 待垫款：<b>垫款申请已提交</b>（{@code applyAdvance}），等待资金岗复核与放款，钱尚未付出。
     *
     * <p>与 {@link #CREATED} 的区别是"有没有提出申请"，与 {@link #FINANCED} 的区别是
     * "钱付了没有"。三者串起来才让付款这一刻成为一次<b>真实的流转</b>。</p>
     *
     * <p><b>不计息</b>：未放款则资金未占用，见 {@link #isInterestBearing()}。</p>
     */
    PENDING_ADVANCE,

    /** 已垫款：金融公司已付款给厂家，车辆尚未到货入库（业务侧称"在途"）。 */
    FINANCED,

    /** 在库：车辆停在经销商场地作为担保物，开始按日计息、累计库龄。 */
    IN_STOCK,

    /**
     * 已售待还：车辆已零售成交，进入结算窗口倒计时，
     * 必须在到期日前结清该台垫款（"售出即还"）。
     */
    SOLD_PENDING,

    /** 已结清：车辆售出后还款完成，担保解除、额度回补。（终态，车已离场） */
    SETTLED,

    /**
     * 已赎回：车辆<b>还在经销商场地</b>，经销商提前还款把物权赎了回去。
     *
     * <p><b>为什么不能复用 {@link #SETTLED}</b>：两者资金侧完全一样（本金利息结清、
     * 担保解除、额度回补），但车辆去向相反——{@code SETTLED} 是车已零售卖出、
     * 永远不会回来；本状态是车还在库里、经销商随时可以再次质押融资。
     * 若合并，系统就无法判断"这台车还能不能再借"，而"还清后再借"正是
     * 库存融资的标准业务循环。</p>
     *
     * <p><b>不是终态</b>：可流转回 {@link #CREATED}（重新建账）或直接
     * {@link #PENDING_ADVANCE}（再次申请垫款）。</p>
     */
    REDEEMED,

    /** 逾期：超结算窗口未还，或库龄递减连续逾期，已升级为违约处理。 */
    OVERDUE,

    /** 处置中：车辆回收、残值处置、损失核算流程进行中。 */
    DISPOSING,

    /** 已关闭：处置完成或异常关单。（终态） */
    CLOSED;

    /** 终态集合：进入终态后不再允许流转。 */
    private static final Set<VehicleStatus> TERMINAL = Set.of(SETTLED, CLOSED);

    /**
     * 未终结状态集合：该 VIN 的<b>当前有效台账</b>，占用 VIN 幂等名额。
     *
     * <p><b>与数据库强绑定（重要）</b>：部分唯一索引 {@code uk_po_vin_active}
     * 的 WHERE 条件必须与本集合完全一致，否则同一 VIN 可重复进入两个未终结订单，
     * 重复融资防线静默失效。见 {@code V14__advance_vin_unique_and_redeem.sql}。</p>
     *
     * <p>{@link #REDEEMED}（已赎回）<b>属于本集合</b>：车还在经销商场地，
     * 台账就是这台车的唯一档案。若把它排除，同一 VIN 可以再建一条台账，
     * 历史查询立刻出现两台"同一辆车"，且两次融资的债权归属无法对齐。
     * 再次融资应当复用本条台账，而不是新开一条。</p>
     *
     * @see #isActive()
     */
    private static final Set<VehicleStatus> ACTIVE = Set.of(
            CREATED, PENDING_ADVANCE, FINANCED, IN_STOCK, SOLD_PENDING, REDEEMED);

    /**
     * 判断是否允许从当前状态流转到目标状态（状态机白名单）。
     *
     * @param target 目标状态
     * @return true 表示允许流转
     *
     * <p><b>白名单（与业务闭环一致）</b>：
     * <pre>
     *   CREATED      → PENDING_ADVANCE（申请垫款） / CLOSED（验车不通过、关单）
     *   PENDING_ADVANCE → FINANCED（放款成功） / CREATED（申请撤销，退回未申请） / CLOSED（关单）
     *   FINANCED     → IN_STOCK（到货入库） / CLOSED（异常关单）
     *   IN_STOCK     → SOLD_PENDING（售出） / OVERDUE（递减连续逾期） / DISPOSING（违约升级）
     *                   / REDEEMED（质押中提前还款赎回）
     *   SOLD_PENDING → SETTLED（结清） / OVERDUE（超结算窗口未还）
     *   OVERDUE      → SETTLED（补还结清） / DISPOSING（进入处置）
     *   DISPOSING    → CLOSED（处置完成） / OVERDUE（T2-6：处置撤销后退回逾期）
     *   REDEEMED     → CREATED（重新建账） / PENDING_ADVANCE（直接再次申请垫款）
     *   SETTLED / CLOSED → 不可再流转
     * </pre>
     *
     * <p><b>注意</b>：本白名单是唯一合法入口，任何绕过它的直接赋值
     * （如 {@code setStatus}）都属于破坏不变量，仅允许 RowMapper 回填使用。</p>
     */
    public boolean canTransitTo(VehicleStatus target) {
        if (target == null || this == target) {
            return false;
        }
        return switch (this) {
            case CREATED -> target == PENDING_ADVANCE || target == CLOSED;
            case PENDING_ADVANCE -> target == FINANCED || target == CREATED || target == CLOSED;
            case FINANCED -> target == IN_STOCK || target == CLOSED;
            case IN_STOCK -> target == SOLD_PENDING || target == OVERDUE || target == DISPOSING
                    || target == REDEEMED;
            case SOLD_PENDING -> target == SETTLED || target == OVERDUE;
            case OVERDUE -> target == SETTLED || target == DISPOSING;
            // T2-6：撤销处置时车辆从 DISPOSING 退回 OVERDUE，恢复正常还款流程
            case DISPOSING -> target == CLOSED || target == OVERDUE;
            case REDEEMED -> target == CREATED || target == PENDING_ADVANCE;
            case SETTLED, CLOSED -> false;
        };
    }

    /**
     * 是否为终态。
     *
     * @return true 表示终态，终态不参与后续流转
     */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /**
     * 是否为"未终结"状态（仍在融资流程中，占用 VIN 幂等名额）。
     *
     * @return true 表示未终结
     * @see #ACTIVE
     */
    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    /**
     * 该状态是否仍需要计息。
     *
     * <p><b>计息范围说明</b>：车辆"在库"期间持续计息；
     * 售出后至结清前（SOLD_PENDING）资金仍被占用，因此<b>继续计息</b>，
     * 直到结清（SETTLED）才停止。
     * 这正是"售出即还"机制要督促车商尽快还款的成本动因。</p>
     *
     * <p>{@link #CREATED}（已建账）、{@link #PENDING_ADVANCE}（待垫款）
     * 与 {@link #FINANCED}（已放款但在途）<b>均不计息</b>——
     * 前两者尚未付款，后者尚未入库起息。</p>
     *
     * <p>{@link #REDEEMED}（已赎回）同样<b>不计息</b>：本金已结清、债权已消灭，
     * 只剩一条待再次融资的空台账。</p>
     *
     * @return true 表示需要按日计息
     */
    public boolean isInterestBearing() {
        return this == IN_STOCK || this == SOLD_PENDING;
    }
}
