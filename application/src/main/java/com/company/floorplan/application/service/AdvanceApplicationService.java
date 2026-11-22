package com.company.floorplan.application.service;

import com.company.floorplan.application.dto.VehicleDraft;
import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.Advance;
import com.company.floorplan.domain.model.AdvanceStatus;
import com.company.floorplan.domain.model.BankAccount;
import com.company.floorplan.domain.model.CreditLine;
import com.company.floorplan.domain.model.PurchaseOrder;
import com.company.floorplan.domain.model.VehicleStatus;
import com.company.floorplan.domain.model.VehicleUnit;
import com.company.floorplan.domain.repository.AdvanceRepository;
import com.company.floorplan.domain.repository.BankAccountRepository;
import com.company.floorplan.domain.repository.CreditLineRepository;
import com.company.floorplan.domain.repository.PurchaseOrderRepository;
import com.company.floorplan.domain.repository.FinancingLienCheckRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.domain.repository.VehicleUnitRepository;
import com.company.floorplan.domain.service.PaymentGateway;
import com.company.floorplan.domain.service.PaymentInstruction;
import com.company.floorplan.domain.service.PaymentResult;
import com.company.floorplan.domain.service.RoleSeparationPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 垫款用例（系统核心）。
 * <p>
 * <b>职责分离（SOD，ADR-006 修订）</b>：垫款的"申请"与"支付（放款）"是两个
 * 不相容的职责，必须由不同角色触发，绝不能塞进同一个方法：
 * <ul>
 *   <li><b>申请</b> {@link #applyAdvance}：占额度、落 {@code PENDING} 指令——申请人（运营操作员，USER）。
 *       本方法<b>绝不调支付网关</b>，只提交一条待放款指令；</li>
 *   <li><b>放款</b> {@link #disburseAdvance}：把指令提交给支付通道、推到 {@code PROCESSING}
 *       ——支付人（资金岗，ADMIN）。受 {@code @RequireAdmin} 保护，申请人无法自己把钱放出去；
 *       <b>本方法绝不内联确认付款</b>；</li>
 *   <li><b>银行异步回调</b> {@link #confirmByAdvanceNo} / {@link #failByAdvanceNo}
 *       （HMAC 鉴权，仅支付通道可调用）：真实通道下由银行回写结果，
 *       把 {@code PROCESSING} 收敛为 PAID / FAILED。</li>
 * </ul>
 * 三者最终都收敛到 {@code PENDING → PROCESSING → PAID / FAILED} 的同一状态机，
 * 申请人 ≠ 支付人 ≠ 银行回调，付款终态只能由银行回写。</p>
 * <p>
 * <b>事务语义（Outbox + 异步）</b>：{@link #stageAdvance} 先落 PENDING 并占用额度（独立事务），
 * {@link #disburseAdvance} 于事务<b>之外</b>提交支付指令并推到 {@code PROCESSING}，
 * <b>绝不在此内联确认</b>；终态由银行<b>异步回调</b> {@link #confirmAdvance}/{@link #markFailed}
 * （独立事务，仅 webhook 可驱动）收敛，彻底避免"付款成功但落库失败"的资损，
 * 也杜绝资金岗"自行确认放款"。结果未知（UNKNOWN）的指令保留在 PROCESSING，由查单/对账作业收敛。</p>
 */
@Service
public class AdvanceApplicationService {

    private final VehicleUnitRepository vehicleUnitRepository;
    private final CreditLineRepository creditLineRepository;
    private final AdvanceRepository advanceRepository;
    private final SupportRepository supportRepository;
    private final PaymentGateway paymentGateway;
    private final FinancingLienCheckRepository financingLienCheckRepository;
    /** 经销商仓储：垫款建账时按经销商评级解析利率加点。 */
    private final com.company.floorplan.domain.repository.DealerRepository dealerRepository;
    /** 评级口径动态配置服务：解析加点 + 准入检查。 */
    private final RatingConfigApplicationService ratingConfigService;
    /** 采购订单仓储：放款时从订单取收款方（ADR-007）。 */
    private final PurchaseOrderRepository purchaseOrderRepository;
    /** 收款账户仓储：解析账户引用码。 */
    private final BankAccountRepository bankAccountRepository;
    /** 担保物台账服务：放款确认时建立台账行（证据链起点）。 */
    private final CollateralLedgerApplicationService collateralService;
    /**
     * T1-5（B1）担保门禁查询端口：由 web 层 CollateralClient Feign 适配器实现。
     * ADR-Q27：判定源已定为 V10 登记批次表，由 collateral-reg-service 直读后返回。
     */
    private final RoleSeparationPolicy.GuaranteeLookup guaranteeLookup;

    /**
     * T1-5（B1）：担保门禁总开关，默认 false 仅为本地原型/冒烟保留；
     * 置 true 后按 ADR-Q27 判定（Q21 硬阻断，不放宽）。
     */
    @Value("${floorplan.core.guarantee-gate.enabled:false}")
    private boolean guaranteeGateEnabled;
    /** Q21 已定「硬阻断」：不设按经销商放宽白名单，避免门禁被配置绕过。 */

    /** 垫款比例上限（预设 100%，需客户确认）。 */
    @Value("${floorplan.advance.max-ratio:1.0}")
    private BigDecimal maxAdvanceRatio;

    /** 默认垫款比例（预设 90%，需客户确认）。 */
    @Value("${floorplan.advance.default-ratio:0.90}")
    private BigDecimal defaultAdvanceRatio;

    /** 默认年利率（预设 3%，示例非报价，需客户确认）。 */
    @Value("${floorplan.interest.default-annual-rate:0.03}")
    private BigDecimal defaultAnnualRate;

    /** 自注入代理（@Lazy 打破自引用循环）：非事务编排方法里调用带 @Transactional 的子步骤，确保各自独立提交（避免自调用绕过代理）。 */
    @Autowired
    @Lazy
    private AdvanceApplicationService self;

    public AdvanceApplicationService(VehicleUnitRepository vehicleUnitRepository,
                                     CreditLineRepository creditLineRepository,
                                     AdvanceRepository advanceRepository,
                                     SupportRepository supportRepository,
                                     PaymentGateway paymentGateway,
                                     FinancingLienCheckRepository financingLienCheckRepository,
                                     com.company.floorplan.domain.repository.DealerRepository dealerRepository,
                                     RatingConfigApplicationService ratingConfigService,
                                     PurchaseOrderRepository purchaseOrderRepository,
                                     BankAccountRepository bankAccountRepository,
                                     CollateralLedgerApplicationService collateralService,
                                     RoleSeparationPolicy.GuaranteeLookup guaranteeLookup) {
        this.vehicleUnitRepository = vehicleUnitRepository;
        this.creditLineRepository = creditLineRepository;
        this.advanceRepository = advanceRepository;
        this.supportRepository = supportRepository;
        this.paymentGateway = paymentGateway;
        this.financingLienCheckRepository = financingLienCheckRepository;
        this.dealerRepository = dealerRepository;
        this.ratingConfigService = ratingConfigService;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.collateralService = collateralService;
        this.guaranteeLookup = guaranteeLookup;
    }

    /** 批量到货建账：部分成功语义（逐台处理，一台脏数据不阻塞整批）。 */
    @Transactional
    public Map<String, Object> ingestVehicles(Long dealerId, Long creditLineId, String purchaseChannel,
                                              String supplierName, List<VehicleDraft> drafts, String actor) {
        List<Map<String, Object>> results = new ArrayList<>();
        int accepted = 0;
        int rejected = 0;

        // ---- 评级口径（后台动态配置）整批前置检查 ----
        // 经销商当前评级若已被停用（allowed=false），整批拒绝：
        // 这是"经销商级"的准入条件，不属于单台脏数据，不做部分成功。
        var dealer = dealerRepository.findById(dealerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "经销商不存在：" + dealerId));
        String riskGrade = dealer.getRiskGrade();
        ratingConfigService.requireAllowed(riskGrade);
        // 执行利率 = 基准年利率 + 评级加点（口径变更即时生效，无需发版）
        BigDecimal effectiveAnnualRate = defaultAnnualRate.add(ratingConfigService.resolveSurcharge(riskGrade));

        for (VehicleDraft draft : drafts) {
            try {
                if (draft.getVin() == null || draft.getVin().isBlank()) {
                    throw new BusinessException(ErrorCode.VALID_001, "VIN 不能为空");
                }
                if (vehicleUnitRepository.existsByVin(draft.getVin())) {
                    throw new BusinessException(ErrorCode.VEHICLE_003, "VIN 已存在：" + draft.getVin());
                }
                // P0-③ 重复融资拦截：该车若在<b>他处</b>已存在权属负担（抵押/查封），
                // 不得再次融资（日本本地多数无开放接口，由人工/厂家门户核对后录入 financing_lien_checks）。
                var lien = financingLienCheckRepository.findLatestByVin(draft.getVin());
                if (lien.isPresent() && lien.get().isEncumbered()) {
                    throw new BusinessException(ErrorCode.VEHICLE_005, Map.of(
                            "vin", draft.getVin(), "note", lien.get().getNote(), "source", lien.get().getCheckSource()));
                }
                BigDecimal ratio = draft.getAdvanceRatio() == null ? defaultAdvanceRatio : draft.getAdvanceRatio();
                if (ratio.compareTo(maxAdvanceRatio) > 0) {
                    throw new BusinessException(ErrorCode.ADVANCE_001, Map.of("ratio", ratio, "max", maxAdvanceRatio));
                }
                long advanceAmount = BigDecimal.valueOf(draft.getInvoiceAmount())
                        .multiply(ratio).setScale(0, RoundingMode.HALF_UP).longValueExact();

                VehicleUnit unit = new VehicleUnit();
                unit.setVin(draft.getVin());
                unit.setDealerId(dealerId);
                unit.setCreditLineId(creditLineId);
                unit.setBrand(draft.getBrand());
                unit.setModel(draft.getModel());
                unit.setModelYear(draft.getModelYear());
                unit.setMileage(draft.getMileage());
                unit.setColor(draft.getColor());
                unit.setVehicleType(draft.getVehicleType());
                unit.setSupplierName(supplierName);
                unit.setPurchaseChannel(purchaseChannel);
                unit.setInvoiceAmount(draft.getInvoiceAmount());
                unit.setAdvanceRatio(ratio);
                unit.setAdvanceAmount(advanceAmount);
                // 建账起点是"已建账"——此时既没申请垫款、也没付款。
                // 申请垫款（applyAdvance）才流转到 PENDING_ADVANCE，
                // 放款确认时 finance() 再流转到 FINANCED（见 confirmAdvance）。
                unit.registerLedger();
                // 利率 = 基准 + 评级加点（后台动态配置，见 rating_configs 表）
                unit.setAnnualRate(effectiveAnnualRate);
                unit.setRateType("FLOATING");
                unit.setAgingDays(0);
                unit.setOverdueDays(0);
                unit.setAuditMismatchCount(0);
                vehicleUnitRepository.save(unit);
                accepted++;
                results.add(Map.of("vin", draft.getVin(), "status", "CREATED", "advanceAmount", advanceAmount));
            } catch (BusinessException e) {
                rejected++;
                results.add(Map.of("vin", String.valueOf(draft.getVin()), "status", "REJECTED",
                        "code", e.getErrorCode().getCode(), "message", e.getMessage()));
            }
        }
        supportRepository.writeAudit(actor, "BATCH_INGEST", "DEALER", String.valueOf(dealerId),
                null, "accepted=" + accepted + ",rejected=" + rejected, null);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("accepted", accepted);
        resp.put("rejected", rejected);
        resp.put("riskGrade", riskGrade);
        resp.put("annualRate", effectiveAnnualRate);
        resp.put("results", results);
        return resp;
    }

    /**
     * 申请垫款（业务闭环第 ③ 环节，申请人＝业务经办岗）。
     *
     * <p><b>本方法只做"申请"，不做"支付"</b>：落一条 {@code PENDING} 指令、占用额度、
     * 写审计，然后立即返回。<b>绝不调支付网关、绝不流转车辆台账</b>——
     * 钱什么时候付、由谁付，是独立的 {@link #disburseAdvance}（资金出纳岗）。
     * 把"申请"与"支付"分开，申请人无法自己把钱放出去，满足
     * 不相容职责分离（SOD）。</p>
     *
     * <p><b>幂等</b>：同一 {@code idempotencyKey} 重复请求直接返回首次结果，
     * 不会重复占用额度（数据库唯一索引再做一层兜底）。</p>
     *
     * @param creditLineId   额度 ID
     * @param vin            车架号
     * @param idempotencyKey 幂等键（必填，防重复垫款）
     * @param actor          操作人（审计留痕，同时作为"申请人"记入责任链）
     * @return 垫款指令：垫款号、金额、剩余可用额度、车辆状态（此时为 PENDING_ADVANCE，已申请未付款）
     */
    public Map<String, Object> applyAdvance(Long creditLineId, String vin, String idempotencyKey, String actor) {
        // 幂等：同一 Key 返回首次结果（放款/回调最终会把 PENDING 翻成终态）
        var existing = advanceRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Advance a = existing.get();
            CreditLine line = creditLineRepository.findById(a.getCreditLineId()).orElse(null);
            return buildAdvanceResponse(a, line == null ? 0L : line.available(), true);
        }

        // 仅落 PENDING 指令 + 占用额度（不调网关、不流转台账）。
        // 支付（放款）是独立、受控的步骤（disburseAdvance，资金出纳岗专属）。
        Advance pending = self.stageAdvance(creditLineId, vin, idempotencyKey, actor);
        pending.setApplicant(actor);   // 责任链第一环：申请人
        advanceRepository.save(pending);
        CreditLine line = creditLineRepository.findById(pending.getCreditLineId()).orElse(null);
        return buildAdvanceResponse(pending, line == null ? 0L : line.available(), false);
    }

    /**
     * 复核垫款（复核人＝资金复核岗）。
     *
     * <p><b>复核的是"能不能付"，不是"付不付"</b>：本方法只核对放款要素
     * （收款方、账户有效性、金额与比例、委托付款协议），并留下复核痕迹，
     * <b>不调用支付通道、不改垫款状态</b>。状态仍为 {@code PENDING}，
     * 等待出纳岗支付。</p>
     *
     * <p><b>自然人级职责分离</b>：复核人不得与申请人同一人
     * （{@code AUTH_003}）。这一条不能靠角色区分保证——一人多岗时
     * 申请人与复核人可能持有同一个角色，但必须是两个人。</p>
     *
     * @param advanceNo 垫款业务单号
     * @param actor     复核人（自然人，来自令牌身份）
     * @return 垫款指令摘要 + 复核结论
     */
    @Transactional
    public Map<String, Object> reviewAdvance(String advanceNo, String actor) {
        Advance pending = advanceRepository.findByAdvanceNo(advanceNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "垫款不存在：" + advanceNo));
        if (pending.getStatus() != AdvanceStatus.PENDING) {
            throw new BusinessException(ErrorCode.ADVANCE_003,
                    "仅 PENDING 可复核，当前状态：" + pending.getStatus());
        }
        // 自然人级 SOD：复核 ≠ 申请（角色可能相同，人必须不同）
        RoleSeparationPolicy.assertNoSelfApproval(pending.getApplicant(), actor, "垫款复核");

        // 复核要素：收款方必须已确定（ADR-007：打款对象由采购订单决定）
        PurchaseOrder order = pending.getPurchaseOrderId() == null ? null
                : purchaseOrderRepository.findById(pending.getPurchaseOrderId()).orElse(null);
        List<String> checks = new ArrayList<>();
        boolean pass = true;
        if (order == null) {
            checks.add("未关联采购订单：收款方无法确认，需补单后复核");
            pass = false;
        } else {
            checks.add("收款方类型：" + (order.getSupplierType() == null ? "未确定" : order.getSupplierType().name())
                    + "（打款模式：" + order.derivePayeeMode().name() + "）");
            BankAccount account = order.getPayeeAccountId() == null ? null
                    : bankAccountRepository.findById(order.getPayeeAccountId()).orElse(null);
            if (account == null) {
                checks.add("收款账户未绑定或已失效：无法放款");
                pass = false;
            } else {
                checks.add("收款账户有效（掩码 " + account.getAccountNoMask() + "）");
            }
        }

        // 留痕：复核人 + 复核时间
        advanceRepository.saveReview(pending.getId(), actor, LocalDateTime.now());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("advanceId", pending.getAdvanceNo());
        data.put("status", pending.getStatus().name());
        data.put("applicant", pending.getApplicant());
        data.put("reviewer", actor);
        data.put("reviewResult", pass ? "PASS" : "REJECTED");
        data.put("reviewChecks", checks);
        return data;
    }

    /**
     * 放款（支付人=资金岗，ADMIN，见 {@code AdvanceController#disburse} 的 {@code @RequireAdmin}）。
     *
     * <p><b>职责分离（SOD）</b>：本方法与 {@link #applyAdvance} 必须由不同角色触发——
     * 申请人（操作员）只能提交 {@code PENDING} 指令，真正的资金划出只能由资金岗执行，
     * 防止"自己申请、自己放款"的内控漏洞。</p>
     *
     * <p><b>异步（发起即返回，ADR-006 修订）</b>：本方法<b>只把指令提交给支付通道</b>，
     * 事务<b>之外</b>调用 {@code paymentGateway.pay()} 仅是"提交"，<b>绝不在此内联确认付款结果</b>：
     * <ul>
     *   <li>通道<b>受理</b>（SUCCEEDED / UNKNOWN）→ 落 {@code PROCESSING}，记录渠道受理号，
     *       立即返回，等待银行异步回调 {@code /confirm} 把终态翻 PAID；</li>
     *   <li>通道<b>明确拒收</b>（FAILED）→ {@link #markFailed} 立即翻 FAILED、释放额度。</li>
     * </ul>
     * 把"提交"与"确认"分开，终态只能由银行 webhook（{@code /confirm}、{@code /fail}，
     * HMAC 鉴权）驱动——资金岗提交后也<b>无法自行把垫款翻成 PAID</b>，杜绝虚构放款。
     * 申请阶段（PENDING）已落库，提交动作绝不会因"付款成功但落库失败"产生资损。</p>
     *
     * @param advanceNo 垫款业务单号
     * @param actor     操作人（资金岗，审计留痕）
     * @return 垫款指令：垫款号、金额、状态（PROCESSING 或 FAILED）、剩余可用额度、车辆状态
     */
    public Map<String, Object> disburseAdvance(String advanceNo, String actor) {
        Advance pending = advanceRepository.findByAdvanceNo(advanceNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "垫款不存在：" + advanceNo));
        if (pending.getStatus() != AdvanceStatus.PENDING) {
            throw new BusinessException(ErrorCode.ADVANCE_003,
                    "仅 PENDING 可放款，当前状态：" + pending.getStatus());
        }
        // 自然人级职责分离：支付人 ≠ 申请人，且 ≠ 复核人。
        // 角色层的"经办/复核/出纳"三岗互斥挡不住一人多岗的情况，
        // 这里按自然人身份再挡一次——这是防资损的最后一道闸。
        RoleSeparationPolicy.assertNoSelfApproval(pending.getApplicant(), actor, "垫款支付");
        RoleSeparationPolicy.assertNoSelfApproval(pending.getReviewer(), actor, "垫款支付");
        // T1-5（B1）：放款前担保门禁——默认关闭，不参与判定；开启时无 REGISTERED 登记即硬阻断。
        enforceGuaranteeGate(pending);
        // 责任链第三环：记下支付人
        advanceRepository.saveDisburser(pending.getId(), actor);
        // 事务外：提交支付指令（此时 PENDING 已提交，绝不会因后续落库失败而"钱付账没记"）
        // 收款方从关联的采购订单取（ADR-007）：垫款单挂 purchase_order_id，
        // 订单带 supplier_type 与收款账户引用——打款对象由订单决定，不由系统规则决定。
        PaymentInstruction instruction = buildPaymentInstruction(pending);
        PaymentResult result = paymentGateway.pay(instruction);
        if (result.isFailed()) {
            // 通道明确拒收：立即翻 FAILED、释放额度（同步拒绝，非异步终态）
            self.markFailed(pending.getId(), result.getMessage(), actor);
            Advance failed = advanceRepository.findById(pending.getId()).orElse(pending);
            CreditLine line = creditLineRepository.findById(pending.getCreditLineId()).orElse(null);
            return buildAdvanceResponse(failed, line == null ? 0L : line.available(), false);
        }
        // 受理或结果未知：落 PROCESSING，等待银行异步回调确认/失败
        Advance processing = self.submitAdvance(pending.getId(), result.getReference(), actor);
        CreditLine line = creditLineRepository.findById(pending.getCreditLineId()).orElse(null);
        return buildAdvanceResponse(processing, line == null ? 0L : line.available(), false);
    }

    /**
     * T1-5（B1）放款前担保门禁。
     *
     * <p><b>ADR-Q27 已裁决</b>：判定源是 V10 {@code collateral_registration_batches + items}
     * （唯一权威源），由 {@code collateral-reg-service} 直读、按「登记编号非空且抹消未完成」
     * 返回 {@code REGISTERED} / {@code NONE}。本门禁只通过
     * {@link RoleSeparationPolicy.GuaranteeLookup} 消费该视图，不再自定判定源。</p>
     *
     * <p>开关 {@code floorplan.core.guarantee-gate.enabled} 默认 false，
     * 默认关闭时<b>连权属服务都不查询</b>，绝不改变本地原型/冒烟行为。</p>
     *
     * <p><b>Q21 已定源（2026-11-22）：硬阻断</b>。开关开启后，查不到 REGISTERED 登记
     * （含服务不可达）一律拒付，<b>不存在按经销商放宽的白名单</b>；拒付的同时生成 HIGH 级
     * 「担保待登记」工单催办，但那是追补动作，不是放行。</p>
     */
    private void enforceGuaranteeGate(Advance pending) {
        if (!guaranteeGateEnabled) {
            return;
        }
        Long dealerId = vehicleUnitRepository.findByVin(pending.getVin())
                .map(VehicleUnit::getDealerId)
                .orElse(null);
        RoleSeparationPolicy.GuaranteeLookup.GuaranteeView view = guaranteeLookup.findByVin(pending.getVin());
        RoleSeparationPolicy.GuaranteeDecision decision =
                RoleSeparationPolicy.evaluateGuarantee(view, guaranteeGateEnabled);
        if (decision.blocked()) {
            supportRepository.createAlert(dealerId, pending.getVin(), "GUARANTEE_PENDING", "HIGH",
                    "B1 担保门禁硬阻断放款：" + decision.reason()
                            + "（ADR-Q27：判定源为 V10 登记批次表，工单催办补登）");
            throw new BusinessException(ErrorCode.GUARANTEE_001, decision.reason());
        }
    }

    /**
     * 构造支付指令（收款方来自关联采购订单）。
     *
     * <p><b>没有关联采购订单时</b>（历史垫款或直接建账路径）：收款方留空，
     * 退化为按 VIN 付的旧行为——不改变既有链路的语义。</p>
     *
     * @param pending 待放款垫款单
     * @return 支付指令
     */
    private PaymentInstruction buildPaymentInstruction(Advance pending) {
        Long orderId = pending.getPurchaseOrderId();
        if (orderId == null) {
            return new PaymentInstruction(pending.getVin(), pending.getAmount(), pending.getVin(),
                    null, null, null);
        }
        PurchaseOrder order = purchaseOrderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return new PaymentInstruction(pending.getVin(), pending.getAmount(), pending.getVin(),
                    null, null, null);
        }
        // 收款账户引用：从订单的账户快照反查账户引用码
        String accountRef = null;
        BankAccount account = order.getPayeeAccountId() == null ? null
                : bankAccountRepository.findById(order.getPayeeAccountId()).orElse(null);
        if (account != null) {
            accountRef = account.getAccountRef();
        }
        return new PaymentInstruction(pending.getVin(), pending.getAmount(), pending.getVin(),
                order.getSupplierType() == null ? null : order.getSupplierType().name(),
                order.getSupplierId(), accountRef);
    }

    /**
     * 阶段一：落 PENDING 垫款指令并占用额度（独立事务，提交后才调网关）。
     * 不做车辆台账流转、不调用外部支付，确保"钱未付先落指令"。
     */
    @Transactional
    public Advance stageAdvance(Long creditLineId, String vin, String idempotencyKey, String actor) {
        CreditLine line = creditLineRepository.findByIdForUpdate(creditLineId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "额度不存在：" + creditLineId));
        VehicleUnit unit = vehicleUnitRepository.findByVinForUpdate(vin)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_001, "车辆不存在：" + vin));
        // 一车一笔垫款：该 VIN 上只要还有仍在<b>占用名额</b>的垫款就不能再申请。
        //   PENDING / PROCESSING → 钱在路上，重复申请会双重占额度 + 重复付款；
        //   PAID                 → 债权存续中，没还清就想再借是重复融资。
        // 作废（VOIDED）与已清偿（REDEEMED）的记录<b>不占名额</b>，
        // 因此同一台车可以留下多条历史记录后重新申请——这正是
        // "申请撤销后重提"与"还清后再借"两种业务现实的表达。
        advanceRepository.findOccupyingByVin(vin).ifPresent(a -> {
            throw new BusinessException(ErrorCode.ADVANCE_003, Map.of(
                    "vin", vin,
                    "advanceNo", String.valueOf(a.getAdvanceNo()),
                    "advanceStatus", String.valueOf(a.getStatus()),
                    "reason", "该车已有未终结的垫款（" + a.getStatus()
                            + "），须先完成放款、作废申请或结清赎回后才能再次申请"));
        });

        // 能申请垫款的车辆状态只有两种：
        //   CREATED  → 首次（或赎回后重新建账）申请，可以申请；
        //   REDEEMED → 上一笔已还清、车还在库，直接再次融资，无需人工改台账；
        // 其余状态（PENDING_ADVANCE 已申请、FINANCED/IN_STOCK/SOLD_PENDING 钱已付）
        // 一律拦掉，避免重复占用额度。
        VehicleStatus current = unit.getStatus();
        if (current != VehicleStatus.CREATED && current != VehicleStatus.REDEEMED) {
            throw new BusinessException(ErrorCode.VEHICLE_002, Map.of(
                    "vin", vin, "currentStatus", String.valueOf(current),
                    "targetStatus", "PENDING_ADVANCE",
                    "reason", "仅已建账（CREATED）或已赎回（REDEEMED）的车辆可申请垫款；"
                            + "申请在途或钱已付出的车辆不得重复申请"));
        }

        // 申请即流转：CREATED → PENDING_ADVANCE（"已提出申请、钱未付"）。
        // 必须落库，否则垫款指令状态与台账状态会打架（本方法是独立事务）。
        unit.markPendingAdvance();
        vehicleUnitRepository.save(unit);

        long amount = unit.getAdvanceAmount();
        line.occupy(amount);
        creditLineRepository.save(line);

        Advance advance = new Advance();
        advance.setVin(vin);
        advance.setCreditLineId(creditLineId);
        advance.setAmount(amount);
        advance.setStatus(AdvanceStatus.PENDING);
        advance.setIdempotencyKey(idempotencyKey);
        Advance saved = advanceRepository.save(advance);

        supportRepository.writeAudit(actor, "ADVANCE", "VEHICLE", vin,
                "status=PENDING", "reserved=" + amount, null);
        return saved;
    }

    /**
     * 阶段二（提交通道）：放款提交成功后，把指令推进到 PROCESSING 并记录渠道受理号。
     * 独立事务。终态只能由此后的银行异步回调（/confirm、/fail）驱动。
     */
    @Transactional
    public Advance submitAdvance(Long advanceId, String channelRef, String actor) {
        Advance a = advanceRepository.findById(advanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "垫款不存在：" + advanceId));
        if (a.getStatus() != AdvanceStatus.PENDING) {
            throw new BusinessException(ErrorCode.ADVANCE_003,
                    "仅 PENDING 可提交通道，当前状态：" + a.getStatus());
        }
        a.setStatus(AdvanceStatus.PROCESSING);
        a.setPaymentRef(channelRef);   // 先记渠道受理号；确认回调可覆盖为清算流水号
        advanceRepository.save(a);
        supportRepository.writeAudit(actor, "ADVANCE", "VEHICLE", a.getVin(),
                "status=PENDING", "status=PROCESSING,channelRef=" + channelRef, null);
        return a;
    }

    /**
     * 阶段三（确认，仅银行 webhook 驱动）：支付通道异步确认成功后回填。
     * 把车辆台账流转到 IN_STOCK、垫款翻 PAID。
     * 幂等：已是终态直接返回；仅 PENDING/PROCESSING 可确认（PROCESSING 为正常回调路径）。
     */
    @Transactional
    public Advance confirmAdvance(Long advanceId, String paymentRef, String actor) {
        Advance a = advanceRepository.findById(advanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "垫款不存在：" + advanceId));
        if (a.getStatus() == AdvanceStatus.PAID
                || a.getStatus() == AdvanceStatus.FAILED
                || a.getStatus() == AdvanceStatus.REVERSED) {
            return a; // 幂等：已终态，重复回调直接返回
        }
        if (a.getStatus() != AdvanceStatus.PENDING && a.getStatus() != AdvanceStatus.PROCESSING) {
            throw new BusinessException(ErrorCode.ADVANCE_003,
                    "仅 PENDING/PROCESSING 可确认支付，当前状态：" + a.getStatus());
        }

        VehicleUnit unit = vehicleUnitRepository.findByVinForUpdate(a.getVin())
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_001, "车辆不存在：" + a.getVin()));
        LocalDate today = LocalDate.now();
        unit.finance(a.getAmount(), today);
        unit.stockIn(today);
        vehicleUnitRepository.save(unit);

        a.setStatus(AdvanceStatus.PAID);
        a.setPaidAt(java.time.LocalDateTime.now());
        a.setPaymentRef(paymentRef);
        advanceRepository.save(a);

        supportRepository.writeAudit(actor, "ADVANCE", "VEHICLE", a.getVin(),
                "status=" + a.getStatus().name(), "status=IN_STOCK,amount=" + a.getAmount(), null);

        // 担保物台账建档：放款确认是台账起点（车辆进入担保物清单）
        collateralService.markFinanced(a.getVin(), unit.getDealerId(), a.getAdvanceNo(),
                a.getAmount(), actor);
        return a;
    }

    /**
     * 阶段三（失败，仅银行 webhook 驱动）：支付通道异步失败后回滚占用、翻 FAILED。
     * 车辆台账未流转（仍 FINANCED），可重新发起垫款或拒绝。
     * 幂等：已是终态直接返回；仅 PENDING/PROCESSING 可失败。
     */
    @Transactional
    public Advance markFailed(Long advanceId, String message, String actor) {
        Advance a = advanceRepository.findById(advanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "垫款不存在：" + advanceId));
        if (a.getStatus() == AdvanceStatus.PAID
                || a.getStatus() == AdvanceStatus.FAILED
                || a.getStatus() == AdvanceStatus.VOIDED
                || a.getStatus() == AdvanceStatus.REDEEMED
                || a.getStatus() == AdvanceStatus.REVERSED) {
            return a; // 幂等：已终态不重复处理
        }
        CreditLine line = creditLineRepository.findByIdForUpdate(a.getCreditLineId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "额度不存在：" + a.getCreditLineId()));
        line.release(a.getAmount());
        creditLineRepository.save(line);

        a.setStatus(AdvanceStatus.FAILED);
        a.setClosedAt(LocalDateTime.now());
        a.setClosedReason("CHANNEL_FAILED");
        advanceRepository.save(a);

        // 台账退回"已建账"：额度已释放，申请入口必须同步重新打开。
        // 否则车卡在 PENDING_ADVANCE，而申请垫款要求 CREATED，这台车再也申请不了。
        // 仅回退仍停留在"待垫款"的车——已放款的车不会走到这里（上面已幂等返回）。
        vehicleUnitRepository.findByVinForUpdate(a.getVin()).ifPresent(v -> {
            if (v.getStatus() == VehicleStatus.PENDING_ADVANCE) {
                v.revertToCreated();
                vehicleUnitRepository.save(v);
            }
        });

        supportRepository.writeAudit(actor, "ADVANCE", "VEHICLE", a.getVin(),
                "status=PENDING/PROCESSING", "status=FAILED,released=" + a.getAmount(), message);
        return a;
    }

    /**
     * 作废垫款申请（经销商主动撤销 / 风控驳回 / 超时未放款）。
     *
     * <p><b>为什么是作废而不是删除</b>：物理删除会丢掉"谁撤的、何时撤的、
     * 这笔额度被占用了多久"。而这三个问题恰恰是重复融资风控要看的信号——
     * 一台车反复申请又反复撤销，本身就是异常行为。
     * 保留记录后，同一台车可以留下<b>任意多条 VOIDED 记录</b>，
     * 它们不占用"一车一笔垫款"的唯一名额，随时可以重新申请。</p>
     *
     * <p><b>只允许 PENDING 作废</b>：
     * <ul>
     *   <li>{@code PROCESSING} 不行——钱已提交给银行，直接作废会造成
     *       "系统里指令没了、银行却把钱打出去了"。必须先撤单或等回调落定；</li>
     *   <li>{@code PAID} 不行——那是带资金流的冲正，走 {@code REVERSED} 那条路。</li>
     * </ul>
     *
     * <p><b>原子完成四件事</b>：释放额度占用 → 垫款置 VOIDED（记原因与时间）
     * → 车辆退回 CREATED（重新打开申请入口）→ 写审计。</p>
     *
     * <p><b>幂等</b>：已是 VOIDED 直接返回。</p>
     *
     * @param advanceId  垫款主键
     * @param reasonCode 作废原因：{@code DEALER_CANCELLED} / {@code REVIEW_REJECTED} / {@code TIMEOUT}
     * @param remark     备注（可选，进审计）
     * @param actor      操作人（自然人口径）
     * @return 作废后的垫款记录
     */
    @Transactional
    public Advance voidAdvance(Long advanceId, String reasonCode, String remark, String actor) {
        Advance a = advanceRepository.findById(advanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "垫款不存在：" + advanceId));

        if (a.getStatus() == AdvanceStatus.VOIDED) {
            return a; // 幂等：已作废
        }
        if (a.getStatus() == AdvanceStatus.PROCESSING) {
            throw new BusinessException(ErrorCode.ADVANCE_003, Map.of(
                    "advanceNo", String.valueOf(a.getAdvanceNo()),
                    "currentStatus", "PROCESSING",
                    "reason", "已提交通道的垫款不可直接作废：资金可能已经打出。"
                            + "请先撤单或等银行回调落定（成功走结清/赎回，失败走 FAILED）后再处理"));
        }
        if (a.getStatus() != AdvanceStatus.PENDING) {
            throw new BusinessException(ErrorCode.ADVANCE_003, Map.of(
                    "advanceNo", String.valueOf(a.getAdvanceNo()),
                    "currentStatus", String.valueOf(a.getStatus()),
                    "reason", "仅待放款（PENDING）的垫款可作废；已付款的请走冲正/结清流程"));
        }

        String normalized = (reasonCode == null || reasonCode.isBlank())
                ? "DEALER_CANCELLED" : reasonCode.trim().toUpperCase();
        if (!normalized.equals("DEALER_CANCELLED")
                && !normalized.equals("REVIEW_REJECTED")
                && !normalized.equals("TIMEOUT")) {
            throw new BusinessException(ErrorCode.VALID_001, Map.of(
                    "reasonCode", normalized,
                    "allowed", "DEALER_CANCELLED / REVIEW_REJECTED / TIMEOUT"));
        }

        // 1) 释放额度占用（行锁，避免与并发申请互相覆盖）
        CreditLine line = creditLineRepository.findByIdForUpdate(a.getCreditLineId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001,
                        "额度不存在：" + a.getCreditLineId()));
        line.release(a.getAmount());
        creditLineRepository.save(line);

        // 2) 垫款置 VOIDED，记录终结时间与原因
        a.setStatus(AdvanceStatus.VOIDED);
        a.setClosedAt(LocalDateTime.now());
        a.setClosedReason(normalized);
        advanceRepository.save(a);

        // 3) 车辆退回"已建账"：申请入口必须同步重新打开，
        //    否则车卡在 PENDING_ADVANCE，而申请要求 CREATED/REDEEMED，这台车就再也申请不了。
        vehicleUnitRepository.findByVinForUpdate(a.getVin()).ifPresent(v -> {
            if (v.getStatus() == VehicleStatus.PENDING_ADVANCE) {
                v.revertToCreated();
                vehicleUnitRepository.save(v);
            }
        });

        supportRepository.writeAudit(actor, "ADVANCE_VOID", "ADVANCE", a.getAdvanceNo(),
                "status=PENDING", "status=VOIDED,reason=" + normalized + ",released=" + a.getAmount(), remark);
        return a;
    }

    /** 按业务单号查询垫款（控制器做存在性校验与作废前的定位）。 */
    public java.util.Optional<Advance> findByAdvanceNo(String advanceNo) {
        return advanceRepository.findByAdvanceNo(advanceNo);
    }

    /** 支付网关回调入口：按业务单号确认支付（走自注入代理，确保独立事务）。 */
    public Advance confirmByAdvanceNo(String advanceNo, String paymentRef, String actor) {
        Advance a = advanceRepository.findByAdvanceNo(advanceNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "垫款不存在：" + advanceNo));
        return self.confirmAdvance(a.getId(), paymentRef, actor);
    }

    /** 支付网关回调入口：按业务单号标记支付失败（走自注入代理，确保独立事务）。 */
    public Advance failByAdvanceNo(String advanceNo, String message, String actor) {
        Advance a = advanceRepository.findByAdvanceNo(advanceNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "垫款不存在：" + advanceNo));
        return self.markFailed(a.getId(), message, actor);
    }

    private Map<String, Object> buildAdvanceResponse(Advance a, long available, boolean idempotentReplay) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("advanceId", a.getAdvanceNo());
        data.put("status", a.getStatus() == null ? null : a.getStatus().name());
        data.put("advancedAmount", a.getAmount());
        data.put("remainingCredit", available);
        // 车辆真实状态：申请阶段为 PENDING_ADVANCE（已申请、钱未付），
        // 银行确认后才流转到 FINANCED（已付款在途）→ IN_STOCK（到货入库）。
        // 校验 CREATED/REDEEMED 时可申请，见 stageAdvance。
        VehicleUnit unit = vehicleUnitRepository.findByVin(a.getVin()).orElse(null);
        data.put("vehicleStatus", unit == null ? null : unit.getStatus().name());
        data.put("idempotentReplay", idempotentReplay);
        // 责任链三环（申请人 / 复核人 / 支付人）：前端据此展示"这笔走到哪一步、谁经手"
        data.put("applicant", a.getApplicant());
        data.put("reviewer", a.getReviewer());
        data.put("disburser", a.getDisburser());
        return data;
    }
}
