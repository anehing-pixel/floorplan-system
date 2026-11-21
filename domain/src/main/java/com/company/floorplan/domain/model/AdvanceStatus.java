package com.company.floorplan.domain.model;

/**
 * 垫款状态枚举。
 *
 * <p><b>为什么从字符串收成枚举</b>：原实现里 {@code PENDING/PAID/FAILED/REVERSED}
 * 是散落的魔法字符串，拼错不会报错只是"静默失效"。收成枚举后取值收敛到一处，
 * 编译期即可发现遗漏；并配合应用层的流转校验，杜绝非法状态。</p>
 *
 * <p><b>生命周期（生产异步模型，ADR-006 修订）</b>：</p>
 * <ul>
 *   <li>{@code PENDING} 待放款：指令已落库、额度已占用，等待资金岗放款（applyAdvance 阶段一）；</li>
 *   <li>{@code PROCESSING} 放款处理中：资金岗已提交支付通道，等待银行<b>异步回调</b>确认/失败
 *       （disburseAdvance 阶段二，不再内联确认）；</li>
 *   <li>{@code PAID} 已付款：银行回调确认成功、台账已流转到 IN_STOCK（confirmAdvance）；</li>
 *   <li>{@code FAILED} 失败：银行回调失败或通道拒收指令，额度已释放（markFailed）；</li>
 *   <li>{@code VOIDED} 已作废：<b>钱一分未付</b>，经销商主动撤销或风控被动驳回申请，
 *       额度已释放、车辆退回 {@code CREATED}（voidAdvance）；</li>
 *   <li>{@code REDEEMED} 已清偿：<b>钱付过、后又还清</b>，债权消灭，该车可再次融资
 *       （售出结清或质押中提前赎回）；</li>
 *   <li>{@code REVERSED} 已冲正：后续冲正/退款（终态，预留，对账作业使用）。</li>
 * </ul>
 *
 * <p><b>一车一债的落点</b>：{@link #occupiesVehicle()} 为 true 的状态
 * （PENDING / PROCESSING / PAID）在数据库上由部分唯一索引
 * {@code uk_adv_vin_active} 保证<b>同一 VIN 至多一条</b>。
 * VOIDED / REDEEMED / REVERSED / FAILED 不占名额，因此同一台车可以留下
 * <b>多条历史记录</b>——这正是"还清后再次融资"与"撤销后重新申请"两种业务现实的表达。</p>
 *
 * <p><b>VOIDED 与 REDEEMED 必须分开（不可合并为一个"作废"）</b>：
 * 前者没有任何资金流，后者有真实的付款与还款流水。若合并，财务对账时无法区分
 * "从没付过钱"与"付了又还清"，垫款累计发生额会凭空少掉一整笔。</p>
 *
 * <p><b>状态机约束</b>：PENDING/PROCESSING 为非终态；
 * PAID 可由结清/赎回推进到 REDEEMED；FAILED/VOIDED/REDEEMED/REVERSED 为终态，
 * 确认/失败幂等返回。确认与失败<b>只能由银行 webhook
 * （{@code /confirm}、{@code /fail}，HMAC 鉴权）驱动</b>，内部用户（含 ADMIN）无法伪造付款结果。</p>
 */
public enum AdvanceStatus {

    /** 待放款：指令已落库、额度已占用，等待资金岗放款。 */
    PENDING,

    /** 放款处理中：已提交支付通道，等待银行异步回调确认/失败。 */
    PROCESSING,

    /** 已付款：银行回调确认成功，车辆台账已流转到 IN_STOCK。 */
    PAID,

    /** 失败：银行回调失败或通道拒收指令，额度已释放，车辆未流转。 */
    FAILED,

    /**
     * 已作废：<b>钱一分未付</b>，申请被撤销（经销商主动撤销 / 风控被动驳回 / 超时未放款）。
     *
     * <p><b>为什么不是物理删除</b>：删除后"谁撤的、何时撤的、这笔额度被占用了多久"
     * 全部丢失，季度对账时账与审计日志对不上就无从追查。
     * 而保留记录才能回答"这台车被尝试融资过几次、失败原因是什么"——
     * 这正是重复融资风控要看的信号。</p>
     *
     * <p><b>只能从 PENDING 进入</b>：PROCESSING 的钱已提交给银行，
     * 直接作废会造成"系统里指令没了、银行却把钱打出去了"，
     * 必须先撤单或等回调落定。PAID 更不允许——那是带资金流的冲正，见 {@link #REVERSED}。</p>
     */
    VOIDED,

    /**
     * 已清偿：<b>钱付过、后来又还清了</b>，债权消灭，该车可再次申请垫款。
     *
     * <p>两种触发路径共用本状态，靠 {@code closed_reason} 字段区分：</p>
     * <ul>
     *   <li>{@code SOLD_SETTLED}：售出后窗口内结清（车已卖掉，不会再融资）；</li>
     *   <li>{@code EARLY_REDEEMED}：质押中经销商提前还款赎车（车还在，可再融资）。</li>
     * </ul>
     *
     * <p><b>为什么不复用 PAID</b>：PAID 的语义是"债权存续中"，
     * 它占用该车唯一的垫款名额。若结清后仍停在 PAID，
     * 这台车就永远无法二次融资——而"还清后再借"是库存融资的标准业务循环。</p>
     */
    REDEEMED,

    /** 已冲正：垫款被冲正/退款（终态，预留）。 */
    REVERSED;

    /**
     * 该状态是否仍占用"一车一笔垫款"的名额。
     *
     * <p><b>与数据库强绑定（重要）</b>：部分唯一索引 {@code uk_adv_vin_active}
     * 的 WHERE 条件必须与本方法完全一致，否则约束会静默失效或误伤。
     * 见 {@code V14__advance_vin_unique_and_redeem.sql}。</p>
     *
     * <p>PENDING/PROCESSING 的钱在路上，PAID 的债权存续——三者都占名额；
     * FAILED / VOIDED / REDEEMED / REVERSED 均已终结，可以再借。</p>
     *
     * @return true 表示该笔垫款仍占该 VIN 的唯一名额
     */
    public boolean occupiesVehicle() {
        return this == PENDING || this == PROCESSING || this == PAID;
    }

    /**
     * 是否为终态（不再发生状态流转）。
     *
     * @return true 表示终态
     */
    public boolean isTerminal() {
        return this == FAILED || this == VOIDED || this == REDEEMED || this == REVERSED;
    }
}
