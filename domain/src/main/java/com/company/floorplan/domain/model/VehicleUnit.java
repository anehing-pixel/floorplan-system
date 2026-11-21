package com.company.floorplan.domain.model;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 车辆单元（单台台账）—— 本系统的<b>核心聚合根</b>。
 *
 * <p><b>业务定位</b>：库存融资的本质是"单台独立核算"。每一台车（以车架号 VIN 为业务主键）
 * 都有一条独立账目：独立记录垫款、独立计息、独立结清。<b>绝不允许只做一个总额</b>。
 * 这是本系统区别于普通贷款系统的根本特征。</p>
 *
 * <p><b>守护的不变量（Invariants）</b>：
 * <ol>
 *   <li>状态机合法流转 —— 白名单见 {@link VehicleStatus#canTransitTo(VehicleStatus)}，
 *       非法流转一律抛 {@code VEHICLE_002}；</li>
 *   <li>一台车同一时刻只有唯一状态 —— 由数据库主键 + 行锁保证；</li>
 *   <li>售出须先结清才能进入终态 —— "售出即还"机制的落脚点；</li>
 *   <li><b>生命周期起点是 CREATED</b>（建账/已建账）→ 申请垫款转 PENDING_ADVANCE（待垫款）
 *       → 放款成功才转 FINANCED（已付款在途）—— "申请了没有""钱付了没有"
 *       必须由状态本身表达，不能靠垫款指令表反查。</li>
 *   <li><b>已赎回（REDEEMED）的车可以再次融资</b> —— 质押中提前还款后物权回归经销商，
 *       车还在库里，台账可退回 CREATED 重新申请。这条循环是库存融资的标准业务形态，
 *       因此 REDEEMED <b>不是终态</b>，且仍占用该 VIN 的唯一台账名额
 *       （禁止为同一台车新开第二条台账，二次融资必须复用本条）。</li>
 *   <li><b>一台车同一时刻至多一笔有效垫款</b> —— 由 {@code advances} 的部分唯一索引
 *       {@code uk_adv_vin_active} 保证；历史多笔只能以 VOIDED / REDEEMED 形态存在。</li>
 * </ol>
 *
 * <p><b>金额约定</b>：所有金额字段为<b>整数日元</b>（long），彻底消灭浮点误差；
 * 比率类（{@link #advanceRatio}、{@link #annualRate}）用 {@link BigDecimal} 保留精度，
 * 但落到金额字段时必须量化为整数日元。</p>
 *
 * <p><b>状态与风险标记分离</b>（设计文档 2.7）：
 * {@link #status} 表达生命周期；{@link #overdueDays} / {@link #riskLevel} / {@link #ootSuspected}
 * 等是独立的风险标记字段，不参与状态机，避免"逾期到底是状态还是标记"的歧义。</p>
 *
 * <p><b>本类不依赖任何框架</b>（无 Spring / JPA 注解）——领域层保持纯净，
 * 便于单测与未来迁移。这是分层铁律的第一条。</p>
 *
 * @see VehicleStatus 状态枚举与流转白名单
 * @see com.company.floorplan.domain.service.InterestCalculator 计息规则
 */
public class VehicleUnit {

    // ==================== 标识与关联 ====================

    /** 车架号（VIN）—— 业务主键，单台核算的唯一标识。 */
    private String vin;

    /** 所属经销商 ID。 */
    private Long dealerId;

    /** 占用的额度 ID（还款回补额度时使用）。 */
    private Long creditLineId;

    /** 关联的垫款记录 ID。 */
    private Long advanceId;

    // ==================== 车辆属性 ====================

    /** 品牌（新车渠道由厂家提供，字段标准化）。 */
    private String brand;

    /** 车型。 */
    private String model;

    /** 年款。 */
    private Integer modelYear;

    /** 里程数（新车通常较低）。 */
    private Integer mileage;

    /** 车身颜色。 */
    private String color;

    /**
     * 车辆类别：新车 / 二手车 / 商用车。
     * 本系统第一版聚焦<b>新车渠道</b>（车辆标准化、有厂家指导价、来源单一）。
     */
    private String vehicleType;

    /** 上游卖方名称（新车渠道即厂家）。垫款直付与对账的对象。 */
    private String supplierName;

    /**
     * 采购渠道：{@code OEM}(厂家) / {@code AUCTION}(拍卖) / {@code WHOLESALE}(批发商) / {@code TRADE_IN}(置换)。
     * 新车渠道主要为 OEM。
     */
    private String purchaseChannel;

    /**
     * 所属采购订单 ID（ADR-007 决策一）。
     *
     * <p>采购订单是融资放款的<b>前置主单</b>，车辆挂订单而非反向——
     * 一单多车天然由订单承载。打款对象、收款账户快照、供应商类型都从订单取，
     * 不在车辆上重复存一份。</p>
     */
    private Long purchaseOrderId;

    // ==================== 交易与垫款金额 ====================

    /** 厂家发票金额（整数日元）。 */
    private long invoiceAmount;

    /** 实际垫款金额 = 发票金额 × 垫款比例（整数日元）。 */
    private long advanceAmount;

    /** 垫款比例（如 0.90 表示垫 90%）。定点数，非金额。 */
    private BigDecimal advanceRatio;

    /** 垫款支付日期，同时作为起息日。 */
    private LocalDate advancedAt;

    // ==================== 账务与计息 ====================

    /** 未偿本金余额（整数日元）。结清后归零。 */
    private long principalBalance;

    /** 已偿还本金累计（整数日元）。 */
    private long paidPrincipal;

    /**
     * 年利率（如 0.03 表示 3%）。
     * <b>注意</b>：此为预设示例值，实际利率结构（基准 + 加点）需向客户确认。
     */
    private BigDecimal annualRate;

    /** 利率类型：{@code FIXED}(固定) / {@code FLOATING}(浮动：基准利率 + 加点)。 */
    private String rateType;

    /** 累计已计利息（整数日元）。结清时以计息流水汇总为准。 */
    private long accruedInterest;

    /**
     * <b>最后计息日</b>——计息幂等与补算的关键字段。
     *
     * <p>日终批处理从该日期的下一天补算到目标业务日，因此：
     * <ul>
     *   <li>该字段错误会导致<b>重复计息</b>或<b>漏计</b>；</li>
     *   <li>数据迁移时必须完整携带（见迁移 Runbook 的中间态清单）。</li>
     * </ul>
     */
    private LocalDate lastInterestDate;

    // ==================== 生命周期时间节点 ====================

    /** 车辆到货入库日期，自此开始计息、开始累计库龄。 */
    private LocalDate stockInDate;

    /** 零售成交日期（"售出即还"的触发点）。 */
    private LocalDate soldAt;

    /**
     * <b>结算到期日</b> = 售出日 + N 个营业日（预设 2 个营业日）。
     *
     * <p>超过该日期仍未还款，将被结算窗口扫描作业判定为逾期，
     * 并触发脱信销售（OOT）告警——这是最严重的违约信号。</p>
     */
    private LocalDate settleDueDate;

    /** 实际结清日期。 */
    private LocalDate settledAt;

    /** 库龄天数（在库未售出的天数），驱动库龄递减还款机制。 */
    private Integer agingDays;

    /** 周转天数（从入库到售出的总天数），车商经营健康度的第一指标。 */
    private Integer turnoverDays;

    // ==================== 状态与担保 ====================

    /** 生命周期状态，流转受白名单约束。 */
    private VehicleStatus status;

    /**
     * 担保方式：所有权保留 / 让与担保 / 其他 / 无。
     *
     * <p><b>待确认</b>：新车未上牌时所有权保留登记无登记对象，
     * 落地路径需客户与日本当地合规确认（设计文档 2.8）。
     * 该结论直接决定担保模块是否落地。</p>
     */
    private SecurityType securityType;

    /** 担保登记状态：NONE / PENDING / REGISTERED / RELEASED。 */
    private SecurityStatus securityStatus;

    // ==================== 风险标记（独立于状态） ====================

    /** 逾期天数。0 表示未逾期。 */
    private Integer overdueDays;

    /** 风险等级：LOW / MEDIUM / HIGH。 */
    private String riskLevel;

    /**
     * 脱信销售（Out Of Trust, OOT）嫌疑标记。
     *
     * <p>车已售却未按时归还垫款，等于把担保物私自变现，是行业<b>最严重违约</b>；
     * 该标记为 true 时应产生最高优先级告警（severity=HIGH）。</p>
     */
    private boolean ootSuspected;

    /** 最近一次实地盘点日期。 */
    private LocalDate lastAuditDate;

    /** 累计盘点差异次数（账实不符次数），达阈值应触发冻结评估。 */
    private Integer auditMismatchCount;

    // ==================== 并发控制 ====================

    /** 乐观锁版本号，防止并发状态流转互相覆盖。 */
    private int version;

    public VehicleUnit() {
    }

    // ==================================================================
    // 状态机行为 —— 业务闭环的推进动作
    // 每个动作都对应业务闭环中的一个环节（见设计文档 2.3）
    // ==================================================================

    /**
     * 状态流转的统一入口：先校验白名单，再切换。
     *
     * @param target 目标状态
     * @throws BusinessException 非法流转抛 {@code VEHICLE_002}，
     *                           details 携带 vin / 当前状态 / 目标状态，便于排查
     */
    private void transit(VehicleStatus target) {
        if (!this.status.canTransitTo(target)) {
            throw new BusinessException(ErrorCode.VEHICLE_002, Map.of(
                    "vin", vin, "currentStatus", String.valueOf(this.status), "targetStatus", String.valueOf(target)));
        }
        this.status = target;
    }

    /**
     * 建账：初始化台账生命周期起点，置为"已建账"（业务闭环第 ② 环节）。
     *
     * <p><b>为什么起点是"已建账"而不是"待垫款"</b>：建账之后、申请垫款之前，
     * 采购审核岗还要线下验车确权（核对 VIN、实车配置、随车凭证、权属）。
     * 这一关没过之前不该进入垫款申请，否则待放款工作台会把未确权的车一并列出，
     * 出纳可能对没验过的车付款。{@link VehicleStatus#PENDING_ADVANCE}
     * 由 {@link #markPendingAdvance()} 在申请垫款时才落。</p>
     *
     * @throws BusinessException 台账已初始化过（非 null 状态）抛 {@code VEHICLE_002}，
     *                           防止重复建账覆盖已有状态
     */
    public void registerLedger() {
        if (this.status != null) {
            throw new BusinessException(ErrorCode.VEHICLE_002, Map.of(
                    "vin", String.valueOf(vin), "currentStatus", String.valueOf(this.status),
                    "targetStatus", "CREATED", "reason", "台账已初始化，不可重复建账"));
        }
        this.status = VehicleStatus.CREATED;
    }

    /**
     * 申请垫款：把台账从"已建账"推进到"待垫款"（业务闭环第 ③ 环节的前半步）。
     *
     * <p>申请只是提出请求、占用额度，<b>钱没付</b>，因此不能落到
     * {@link VehicleStatus#FINANCED}。此后的放款确认由 {@link #finance(long, LocalDate)} 流转。</p>
     *
     * @throws BusinessException 非"已建账"状态（已申请过、已放款等）抛 {@code VEHICLE_002}
     */
    public void markPendingAdvance() {
        if (this.status == null) {
            throw new BusinessException(ErrorCode.VEHICLE_002, Map.of(
                    "vin", String.valueOf(vin), "reason", "台账状态未初始化，建账时应先置 CREATED"));
        }
        transit(VehicleStatus.PENDING_ADVANCE);
    }

    /**
     * 垫款申请失败/撤销：把台账从"待垫款"退回"已建账"，允许重新申请。
     *
     * <p><b>为什么必须回退</b>：申请垫款时台账已流转到 {@link VehicleStatus#PENDING_ADVANCE}。
     * 若支付失败只释放额度、不改台账，这台车就卡死在"待垫款"——
     * 而重新申请要求 {@link VehicleStatus#CREATED}，申请入口对它是关闭的，
     * 等于一台车一次放款失败就报废。</p>
     *
     * @throws BusinessException 非"待垫款"状态抛 {@code VEHICLE_002}
     */
    public void revertToCreated() {
        transit(VehicleStatus.CREATED);
    }

    /**
     * 垫款支付成功：把台账从"待垫款"推进到"已垫款"（业务闭环第 ③ 环节）。
     *
     * <p><b>此处走白名单 {@link #transit(VehicleStatus)}，不再直接赋值</b>——
     * 放款是一次真实的业务流转（PENDING_ADVANCE → FINANCED），
     * 必须受状态机约束。历史上的直接赋值是为了掩盖"建账即已垫款"导致的状态重复设置，
     * 引入 PENDING_ADVANCE 后该借口不再成立。</p>
     *
     * <p><b>幂等</b>：已是 FINANCED（存量车或重复回调）直接跳过流转，仅刷新金额与日期。</p>
     *
     * @param amount 垫款金额（整数日元）
     * @param date   垫款日，同时作为起息日
     * @throws BusinessException 状态未初始化、或非法流转（如已入库后再放款）抛 {@code VEHICLE_002}
     */
    public void finance(long amount, LocalDate date) {
        if (this.status == null) {
            throw new BusinessException(ErrorCode.VEHICLE_002, Map.of(
                    "vin", String.valueOf(vin), "reason", "台账状态未初始化，建账时应先置 CREATED"));
        }
        if (this.status != VehicleStatus.FINANCED) {
            transit(VehicleStatus.FINANCED);
        }
        this.advanceAmount = amount;
        this.principalBalance = amount;
        this.advancedAt = date;
    }

    /**
     * 车辆到货入库：进入"在库"（业务闭环第 ⑤ 环节起点）。
     *
     * <p>计息口径为"含当日"——入库当天即起息。日终批处理从
     * {@code lastInterestDate + 1} 逐日补算，因此这里把它初始化为
     * <b>入库日 − 1 天</b>，首轮补算恰好覆盖入库当天（第 0 天计入）。
     * 这样既不漏计第一天，也不会重复计。</p>
     *
     * @param date 入库日期
     */
    public void stockIn(LocalDate date) {
        transit(VehicleStatus.IN_STOCK);
        this.stockInDate = date;
        this.lastInterestDate = date.minusDays(1);
    }

    /**
     * 零售成交：进入"已售待还"，启动结算窗口倒计时（业务闭环第 ⑥ 环节）。
     *
     * @param soldAt        售出日期
     * @param settleDueDate 结算到期日（售出日 + N 个营业日，由 {@link com.company.floorplan.domain.service.BusinessCalendar} 计算）
     */
    public void markSold(LocalDate soldAt, LocalDate settleDueDate) {
        transit(VehicleStatus.SOLD_PENDING);
        this.soldAt = soldAt;
        this.settleDueDate = settleDueDate;
    }

    /**
     * T2-8（M11 终端客户支付确认）：判断该车是否可发起客户付款确认。
     *
     * <p>业务口径："客户付款 → 金融确认收款 → 下达出库指令"。
     * 只有已售待还（SOLD_PENDING）且本金未结清的车才需要金融确认；
     * 资金未到账不得下达出库指令（防脱信销售）。</p>
     */
    public boolean canConfirmCustomerPayment() {
        return this.status == VehicleStatus.SOLD_PENDING && this.principalBalance > 0;
    }

    /**
     * 结清：终止于"已结清"，本金归零（业务闭环第 ⑦ 环节）。
     *
     * <p><b>调用方责任</b>：结清用例还需完成"解除担保 + 额度回补"，
     * 三件事必须在同一事务内原子完成，不可只做还款而漏掉另外两件。</p>
     *
     * @param settledAt 结清日期
     */
    public void settle(LocalDate settledAt) {
        transit(VehicleStatus.SETTLED);
        this.settledAt = settledAt;
        this.principalBalance = 0L;
    }

    /**
     * 提前赎回：车辆仍在库时经销商提前还清，物权回归经销商（业务闭环第 ⑦ 环节的旁路）。
     *
     * <p><b>与 {@link #settle(LocalDate)} 的区别只在车辆去向</b>：
     * 资金动作完全一致（本金利息结清、担保解除、额度回补），但结清后
     * 车<b>还在经销商场地</b>，可再次质押融资——因此落在
     * {@link VehicleStatus#REDEEMED} 而非 {@link VehicleStatus#SETTLED}。</p>
     *
     * <p><b>为什么必须先有这一步</b>：原实现只允许 {@code SOLD_PENDING}
     * 或 {@code OVERDUE} 结清，等于"车没卖出去就不能还钱"。
     * 而经销商提前还款赎车是真实高频动作（资金宽裕、换车、转卖他处），
     * 走不通就只能线下处理，账实立刻分叉。</p>
     *
     * <p><b>调用方责任</b>：赎回用例还需在同一事务内完成
     * "垫款记录置 REDEEMED + 解除担保 + 额度回补"，缺一项都会留下账实不符。</p>
     *
     * @param redeemedAt 赎回日期
     * @throws BusinessException 当前非"在库"状态抛 {@code VEHICLE_002}
     */
    public void redeem(LocalDate redeemedAt) {
        if (this.status != VehicleStatus.IN_STOCK) {
            throw new BusinessException(ErrorCode.VEHICLE_002, Map.of(
                    "vin", String.valueOf(vin),
                    "currentStatus", String.valueOf(this.status),
                    "targetStatus", "REDEEMED",
                    "reason", "仅质押在库（IN_STOCK）车辆可提前赎回；已售或已处置的车辆请走结清/处置流程"));
        }
        transit(VehicleStatus.REDEEMED);
        this.settledAt = redeemedAt;
        this.principalBalance = 0L;
    }

    /**
     * 赎回后重新建账：把空台账从"已赎回"退回"已建账"，供下一次融资申请。
     *
     * <p>与 {@link #markPendingAdvance()} 配合构成二次融资的入口：
     * 经销商再次用同一台车申请垫款时调用。</p>
     *
     * @throws BusinessException 当前非"已赎回"状态抛 {@code VEHICLE_002}
     */
    public void reopenForRefinance() {
        transit(VehicleStatus.CREATED);
    }

    /**
     * 置为逾期：超结算窗口未还，或库龄递减连续逾期。
     *
     * @param overdueDays 逾期天数
     */
    public void toOverdue(int overdueDays) {
        transit(VehicleStatus.OVERDUE);
        this.overdueDays = overdueDays;
        this.riskLevel = "HIGH";
    }

    /**
     * T1-2（B4 盘点缺失改状态）：实地盘点"账有实无"时的升级动作。
     *
     * <p><b>为什么必须改状态而不只打标记</b>：修复前盘点缺失只置 {@code ootSuspected=true}
     * 与差异次数，车辆状态仍是 {@link VehicleStatus#IN_STOCK}——而违约处置扫描作业
     * {@code DEFAULT_DISPOSAL} 只扫 {@link VehicleStatus#OVERDUE}，
     * 导致最严重的脱信信号永远进不了处置闭环（核查报告接缝三）。
     * 本方法在库车直接转 OVERDUE，使其能被扫描作业捕获并自动开处置工单。</p>
     *
     * <p><b>边界</b>：仅 {@link VehicleStatus#IN_STOCK} 会被升级；
     * 已售待还、逾期、处置中、终态车辆只累加差异标记，不重复流转。
     * 重复调用在状态维度幂等，差异次数如实累加（每次盘点都是独立事实）。</p>
     *
     * @param auditDate 本次实地盘点日期
     */
    public void markInventoryMissing(LocalDate auditDate) {
        this.ootSuspected = true;
        this.auditMismatchCount = (this.auditMismatchCount == null ? 0 : this.auditMismatchCount) + 1;
        this.lastAuditDate = auditDate;
        if (this.status == VehicleStatus.IN_STOCK) {
            transit(VehicleStatus.OVERDUE);
            this.overdueDays = this.overdueDays == null ? 0 : this.overdueDays;
            this.riskLevel = "HIGH";
        }
    }

    /** 违约升级：进入处置流程（车辆回收、残值处置、损失核算）。 */
    public void dispose() {
        transit(VehicleStatus.DISPOSING);
        this.riskLevel = "HIGH";
    }

    /**
     * T2-6：处置撤销后把车辆从 DISPOSING 退回 OVERDUE。
     *
     * <p>撤销处置不等于车已恢复正常——客户可能只是达成和解或短期补救，
     * 因此退回 OVERDUE（仍可走正常还款/结清流程），而不是直接 IN_STOCK。</p>
     */
    public void cancelDisposal() {
        if (this.status == VehicleStatus.DISPOSING) {
            transit(VehicleStatus.OVERDUE);
        }
    }

    /**
     * 关单：处置完成或异常终止。
     *
     * @implNote 若已是终态（SETTLED / CLOSED）直接返回，保证幂等。
     */
    public void close() {
        if (this.status.isTerminal()) {
            return;
        }
        transit(VehicleStatus.CLOSED);
    }

    /**
     * 累计利息（日终批处理调用）。
     *
     * @param dailyInterest 当日利息（整数日元，已按舍入规则量化）
     * @param bizDate       计息业务日，写入 {@link #lastInterestDate} 作为下次补算的基准
     */
    public void accrueInterest(long dailyInterest, LocalDate bizDate) {
        this.accruedInterest += dailyInterest;
        this.lastInterestDate = bizDate;
    }

    /**
     * 偿还本金（部分还款 / 库龄递减还款场景）。
     *
     * @param amount 还款本金（整数日元）
     * @throws BusinessException 金额非正抛 {@code VALID_002}
     * @implNote 实还金额取 min(amount, principalBalance)，防止余额为负。
     */
    public void applyPrincipalPayment(long amount) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.VALID_002, "还款金额必须为正整数日元");
        }
        long actual = Math.min(amount, principalBalance);
        this.principalBalance -= actual;
        this.paidPrincipal += actual;
    }

    /**
     * 设定担保（业务闭环第 ④ 环节）。
     *
     * @param type 担保方式；{@code NONE} 表示无担保
     * @throws BusinessException 类型为空抛 {@code VEHICLE_004}
     */
    public void setSecurity(SecurityType type) {
        if (type == null) {
            throw new BusinessException(ErrorCode.VEHICLE_004, "担保类型不能为空");
        }
        this.securityType = type;
        // 无担保时状态保持 NONE；其余先置为"待登记"，等登记回执再转 REGISTERED
        this.securityStatus = type == SecurityType.NONE ? SecurityStatus.NONE : SecurityStatus.PENDING;
    }

    // ==================== T1-7（B1b）：额度回补判定（引入还款动因维度） ====================

    /**
     * 还款动因——区分"本金下降"背后的业务性质。
     *
     * <p>核查报告 §8.3 的关键结论：同样是在库车本金下降，
     * 库龄递减（强制）不回补额度，主动提前还本金（自愿）应回补。
     * 只看车辆状态会把两者混为一谈，故必须显式建模动因。</p>
     */
    public enum RepaymentCause {
        /** 在库车主动提前还本金（自愿，资信良好信号）→ 应回补 */
        VOLUNTARY_PREPAY,
        /** 库龄递减强制还款（超期被逼，目的是压降敞口）→ 不回补 */
        AGING_REDUCTION,
        /** 逾期补款 → 不回补 */
        OVERDUE_MAKEUP,
        /** 整台结清（由 settle 用例处理，不经过 partialRepay） */
        SETTLEMENT
    }

    /** 额度回补判定结果。 */
    public record ReleaseDecision(boolean release, long amount, String reason) {
        public static ReleaseDecision none(String reason) {
            return new ReleaseDecision(false, 0L, reason);
        }

        public static ReleaseDecision full(long amount, String reason) {
            return new ReleaseDecision(true, amount, reason);
        }

        public static ReleaseDecision partial(long amount, String reason) {
            return new ReleaseDecision(true, amount, reason);
        }
    }

    /**
     * T1-7（B1b）额度回补判定：本金敞口下降 + 质押物释放/动因一致。
     *
     * <p><b>不误触发担保消灭</b>：本方法只返回"是否回补额度"，
     * 绝不改变 securityStatus；担保的解除仍只由结清/赎回/处置完成用例驱动。</p>
     *
     * @param unit         还款车辆（判定时点，尚未扣本金）
     * @param principalPaid 本次偿还本金
     * @param cause        还款动因（必须由调用方显式传入，不得只按状态推断）
     */
    public static ReleaseDecision evaluateCreditRelease(VehicleUnit unit,
                                                        long principalPaid,
                                                        RepaymentCause cause) {
        // C1：本金敞口是否真的下降
        if (principalPaid <= 0) {
            return ReleaseDecision.none("仅结息，本金未降，不回补");
        }
        // 动因维度：强制还款与自愿还款结论相反，必须先判
        if (cause == RepaymentCause.AGING_REDUCTION) {
            return ReleaseDecision.none("库龄递减为强制还款，目的是压降敞口，不回补额度");
        }
        if (cause == RepaymentCause.OVERDUE_MAKEUP) {
            return ReleaseDecision.none("逾期补款不回补可用额度");
        }
        // C2：仅"处置完成"或"担保已解除"才代表质押物释放 → 全额回补。
        // B-3 修复：DISPOSING 是"处置进行中"（车辆回收/变现/损失核算尚未完成），
        // 担保并未消灭（releaseSecurity() 与车辆 close() 在此阶段都未发生），
        // 因此绝不能按"担保消灭"回补额度；否则一旦额度解冻，虚增的 available()
        // 立即变成可放款额度，形成实际资损（CORE_VERIFIER_REPORT B-3）。
        if (unit.getStatus() == VehicleStatus.CLOSED) {
            return ReleaseDecision.full(principalPaid, "车辆已处置完成，担保消灭，本金敞口清零");
        }
        if (unit.getStatus() == VehicleStatus.DISPOSING) {
            return ReleaseDecision.none("处置进行中，担保尚未消灭，不回补额度");
        }
        if (unit.getSecurityStatus() == SecurityStatus.RELEASED) {
            return ReleaseDecision.full(principalPaid, "担保已解除，质押物已释放");
        }
        // 在库车主动提前还本金：本金敞口真实下降，且是资信良好信号 → 回补对应额度
        if (unit.getStatus() == VehicleStatus.IN_STOCK && cause == RepaymentCause.VOLUNTARY_PREPAY) {
            return ReleaseDecision.partial(principalPaid,
                    "在库车主动提前还本金：本金敞口下降，回补对应额度");
        }
        // 已售未解押：质押物价值已归零且未解押，不回补
        return ReleaseDecision.none("已售未解押，质押物未释放，不回补额度");
    }

    /**
     * T1-3（M9 登记编号落库）：设定担保时回填线下登记编号。
     *
     * <p>老接口 {@code POST /{vin}/security} 收了 {@code regNo} 却直接丢弃，
     * 车辆担保状态永远停在 PENDING，使用方以为登记完成。本重载把"带登记编号"
     * 这一事实落到领域状态：有编号且担保方式非 NONE 时即为 REGISTERED
     * （登记是线下去法务省办理的，编号回填即代表该线下动作已完成）。</p>
     *
     * @param type           担保方式；NONE 表示无担保
     * @param registrationNo 法务省登记编号；为空/空白时维持 PENDING
     */
    public void setSecurity(SecurityType type, String registrationNo) {
        setSecurity(type);
        if (type != SecurityType.NONE && this.securityStatus == SecurityStatus.PENDING
                && registrationNo != null && !registrationNo.isBlank()) {
            this.securityStatus = SecurityStatus.REGISTERED;
        }
    }

    /**
     * 解除担保（业务闭环第 ⑦ 环节，结清时自动触发）。
     *
     * @implNote 本就无担保时直接返回，保证幂等（重复调用不报错）。
     */
    public void releaseSecurity() {
        if (this.securityStatus == SecurityStatus.NONE) {
            return;
        }
        this.securityStatus = SecurityStatus.RELEASED;
    }

    // ==================================================================
    // getter / setter —— 供基础设施层 RowMapper 装配使用
    // 金额字段类型为 long（整数日元）；比率字段为 BigDecimal
    // ==================================================================

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public Long getDealerId() {
        return dealerId;
    }

    public void setDealerId(Long dealerId) {
        this.dealerId = dealerId;
    }

    public Long getCreditLineId() {
        return creditLineId;
    }

    public void setCreditLineId(Long creditLineId) {
        this.creditLineId = creditLineId;
    }

    public Long getAdvanceId() {
        return advanceId;
    }

    public void setAdvanceId(Long advanceId) {
        this.advanceId = advanceId;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getModelYear() {
        return modelYear;
    }

    public void setModelYear(Integer modelYear) {
        this.modelYear = modelYear;
    }

    public Integer getMileage() {
        return mileage;
    }

    public void setMileage(Integer mileage) {
        this.mileage = mileage;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getVehicleType() {
        return vehicleType;
    }

    public void setVehicleType(String vehicleType) {
        this.vehicleType = vehicleType;
    }

    public String getSupplierName() {
        return supplierName;
    }

    public void setSupplierName(String supplierName) {
        this.supplierName = supplierName;
    }

    public String getPurchaseChannel() {
        return purchaseChannel;
    }

    public void setPurchaseChannel(String purchaseChannel) {
        this.purchaseChannel = purchaseChannel;
    }

    public Long getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public void setPurchaseOrderId(Long purchaseOrderId) {
        this.purchaseOrderId = purchaseOrderId;
    }

    public long getInvoiceAmount() {
        return invoiceAmount;
    }

    public void setInvoiceAmount(long invoiceAmount) {
        this.invoiceAmount = invoiceAmount;
    }

    public long getAdvanceAmount() {
        return advanceAmount;
    }

    public void setAdvanceAmount(long advanceAmount) {
        this.advanceAmount = advanceAmount;
    }

    public BigDecimal getAdvanceRatio() {
        return advanceRatio;
    }

    public void setAdvanceRatio(BigDecimal advanceRatio) {
        this.advanceRatio = advanceRatio;
    }

    public LocalDate getAdvancedAt() {
        return advancedAt;
    }

    public void setAdvancedAt(LocalDate advancedAt) {
        this.advancedAt = advancedAt;
    }

    public long getPrincipalBalance() {
        return principalBalance;
    }

    public void setPrincipalBalance(long principalBalance) {
        this.principalBalance = principalBalance;
    }

    public long getPaidPrincipal() {
        return paidPrincipal;
    }

    public void setPaidPrincipal(long paidPrincipal) {
        this.paidPrincipal = paidPrincipal;
    }

    public BigDecimal getAnnualRate() {
        return annualRate;
    }

    public void setAnnualRate(BigDecimal annualRate) {
        this.annualRate = annualRate;
    }

    public String getRateType() {
        return rateType;
    }

    public void setRateType(String rateType) {
        this.rateType = rateType;
    }

    public long getAccruedInterest() {
        return accruedInterest;
    }

    public void setAccruedInterest(long accruedInterest) {
        this.accruedInterest = accruedInterest;
    }

    public LocalDate getLastInterestDate() {
        return lastInterestDate;
    }

    public void setLastInterestDate(LocalDate lastInterestDate) {
        this.lastInterestDate = lastInterestDate;
    }

    public LocalDate getStockInDate() {
        return stockInDate;
    }

    public void setStockInDate(LocalDate stockInDate) {
        this.stockInDate = stockInDate;
    }

    public LocalDate getSoldAt() {
        return soldAt;
    }

    public void setSoldAt(LocalDate soldAt) {
        this.soldAt = soldAt;
    }

    public LocalDate getSettleDueDate() {
        return settleDueDate;
    }

    public void setSettleDueDate(LocalDate settleDueDate) {
        this.settleDueDate = settleDueDate;
    }

    public LocalDate getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(LocalDate settledAt) {
        this.settledAt = settledAt;
    }

    public Integer getAgingDays() {
        return agingDays;
    }

    public void setAgingDays(Integer agingDays) {
        this.agingDays = agingDays;
    }

    public Integer getTurnoverDays() {
        return turnoverDays;
    }

    public void setTurnoverDays(Integer turnoverDays) {
        this.turnoverDays = turnoverDays;
    }

    public VehicleStatus getStatus() {
        return status;
    }

    /**
     * 直接设置状态——<b>仅限两类场景</b>：① 基础设施层 RowMapper 从数据库回填；
     * ② 历史数据导入时按业务事实回填（如 Excel 里明确写"已质押"的车）。
     *
     * <p><b>业务流转一律走 {@link #transit(VehicleStatus)}</b>，不得调用本方法，
     * 否则绕过状态机白名单，破坏不变量。</p>
     */
    public void setStatus(VehicleStatus status) {
        this.status = status;
    }

    public SecurityType getSecurityType() {
        return securityType;
    }

    public void setSecurityType(SecurityType securityType) {
        this.securityType = securityType;
    }

    public SecurityStatus getSecurityStatus() {
        return securityStatus;
    }

    public void setSecurityStatus(SecurityStatus securityStatus) {
        this.securityStatus = securityStatus;
    }

    public Integer getOverdueDays() {
        return overdueDays;
    }

    public void setOverdueDays(Integer overdueDays) {
        this.overdueDays = overdueDays;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public boolean isOotSuspected() {
        return ootSuspected;
    }

    public void setOotSuspected(boolean ootSuspected) {
        this.ootSuspected = ootSuspected;
    }

    public LocalDate getLastAuditDate() {
        return lastAuditDate;
    }

    public void setLastAuditDate(LocalDate lastAuditDate) {
        this.lastAuditDate = lastAuditDate;
    }

    public Integer getAuditMismatchCount() {
        return auditMismatchCount;
    }

    public void setAuditMismatchCount(Integer auditMismatchCount) {
        this.auditMismatchCount = auditMismatchCount;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }
}
