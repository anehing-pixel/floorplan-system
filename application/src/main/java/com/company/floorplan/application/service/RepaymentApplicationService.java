package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.Advance;
import com.company.floorplan.domain.model.AdvanceStatus;
import com.company.floorplan.domain.model.CreditLine;
import com.company.floorplan.domain.model.Repayment;
import com.company.floorplan.domain.model.VehicleStatus;
import com.company.floorplan.domain.model.VehicleUnit;
import com.company.floorplan.domain.repository.AdvanceRepository;
import com.company.floorplan.domain.repository.CreditLineRepository;
import com.company.floorplan.domain.repository.InterestLedgerRepository;
import com.company.floorplan.domain.repository.RepaymentRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.domain.repository.VehicleUnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 还款结清用例（业务闭环第 ⑦ 环节）。
 * 原子完成三件事：还款核销 + 解除担保 + 额度回补。
 */
@Service
public class RepaymentApplicationService {

    private final VehicleUnitRepository vehicleUnitRepository;
    private final CreditLineRepository creditLineRepository;
    private final RepaymentRepository repaymentRepository;
    private final InterestLedgerRepository interestLedgerRepository;
    private final SupportRepository supportRepository;
    /** 担保物台账服务：回款核销与结清后的抹消待办。 */
    private final CollateralLedgerApplicationService collateralService;
    /** 垫款仓储：结清/赎回后必须把垫款记录推进到终态，否则该车无法二次融资。 */
    private final AdvanceRepository advanceRepository;

    public RepaymentApplicationService(VehicleUnitRepository vehicleUnitRepository,
                                       CreditLineRepository creditLineRepository,
                                       RepaymentRepository repaymentRepository,
                                       InterestLedgerRepository interestLedgerRepository,
                                       SupportRepository supportRepository,
                                       CollateralLedgerApplicationService collateralService,
                                       AdvanceRepository advanceRepository) {
        this.vehicleUnitRepository = vehicleUnitRepository;
        this.creditLineRepository = creditLineRepository;
        this.repaymentRepository = repaymentRepository;
        this.interestLedgerRepository = interestLedgerRepository;
        this.supportRepository = supportRepository;
        this.collateralService = collateralService;
        this.advanceRepository = advanceRepository;
    }

    /**
     * 还款结清（业务闭环第 ⑦ 环节）。
     *
     * <p><b>原子完成三件事，缺一不可</b>：
     * <ol>
     *   <li><b>还款核销</b>：本金清零，状态流转到 SETTLED；</li>
     *   <li><b>解除担保</b>：担保状态置 RELEASED（设计文档特别强调：
     *       不可只做还款而漏解除担保，否则车辆仍被登记在金融公司名下）；</li>
     *   <li><b>额度回补</b>：行锁下释放本金占用，额度立即可用于下一台垫款
     *       （这是"循环授信"与一次性贷款的本质区别）。</li>
     * </ol>
     *
     * <p><b>金额校验</b>：应收金额 = 未偿本金 + 累计利息。
     * 其中利息<b>以计息流水汇总为准</b>（而非重新计算），
     * 避免"重算值与流水值不一致"导致对账纠纷。金额不符抛 REPAY_001。</p>
     *
     * <p><b>幂等</b>：同一 {@code idempotencyKey} 返回首次结果；
     * 已结清车辆再次请求抛 REPAY_002。</p>
     *
     * @param vin              车架号
     * @param settlementAmount 实际还款金额（本金 + 利息，整数日元）
     * @param operatorId       操作人 ID
     * @param idempotencyKey   幂等键
     * @param actor            操作人（审计留痕）
     * @return 结清结果：还款号、本金、利息、回补额度、担保是否已解除
     */
    @Transactional
    public Map<String, Object> settle(String vin, long settlementAmount, Long operatorId,
                                      String idempotencyKey, String actor) {
        // 幂等
        var existing = repaymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Repayment r = existing.get();
            return buildResponse(r, 0L, true);
        }

        VehicleUnit unit = vehicleUnitRepository.findByVinForUpdate(vin)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_001, "车辆不存在：" + vin));
        if (unit.getStatus() == VehicleStatus.SETTLED || unit.getStatus() == VehicleStatus.CLOSED) {
            throw new BusinessException(ErrorCode.REPAY_002, "该台已结清：" + vin);
        }
        if (unit.getStatus() != VehicleStatus.SOLD_PENDING && unit.getStatus() != VehicleStatus.OVERDUE) {
            throw new BusinessException(ErrorCode.VEHICLE_002, Map.of(
                    "vin", vin, "currentStatus", String.valueOf(unit.getStatus()),
                    "hint", "需先标记售出（SOLD_PENDING）或已进入逾期"));
        }

        // 应收利息以计息流水汇总为准（避免重算与流水不一致）
        long interest = interestLedgerRepository.sumInterestByVin(vin);
        long principal = unit.getPrincipalBalance();
        long expected = principal + interest;
        if (settlementAmount != expected) {
            throw new BusinessException(ErrorCode.REPAY_001, Map.of(
                    "expected", expected, "principal", principal, "interest", interest, "actual", settlementAmount));
        }

        // 1) 核销本金
        unit.applyPrincipalPayment(principal);
        // 2) 结清（状态流转）
        unit.settle(LocalDate.now());
        // 3) 解除担保
        unit.releaseSecurity();
        vehicleUnitRepository.save(unit);

        // 4) 额度回补（行锁）
        long released = 0L;
        if (unit.getCreditLineId() != null) {
            CreditLine line = creditLineRepository.findByIdForUpdate(unit.getCreditLineId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "额度不存在"));
            released = principal;
            line.release(principal);
            creditLineRepository.save(line);
        }

        Repayment repayment = new Repayment();
        repayment.setVin(vin);
        repayment.setPrincipal(principal);
        repayment.setInterest(interest);
        repayment.setTotalAmount(settlementAmount);
        repayment.setRepaymentType("FULL");
        repayment.setSettledAt(LocalDateTime.now());
        repayment.setOperatorId(operatorId);
        repayment.setIdempotencyKey(idempotencyKey);
        Repayment saved = repaymentRepository.save(repayment);

        // 垫款记录终止：PAID → REDEEMED（原因 SOLD_SETTLED）。
        // 不做这一步，垫款记录会永久停在 PAID —— 而 PAID 仍然占用该 VIN 的
        // “一车一笔垫款”名额，等于这台车结清后再也借不出来。
        closeAdvanceAsRedeemed(vin, "SOLD_SETTLED", actor);

        supportRepository.writeAudit(actor, "REPAYMENT", "VEHICLE", vin,
                "principal=" + principal, "settled,interest=" + interest + ",released=" + released, null);

        // 担保物台账：①回款核销（库存释放规则的落点）②担保实体权利消灭 → 生成抹消登记待办。
        // 注意：结清 ≠ 抹消完成。日本让渡担保的抹消登记需权利人与登记义务人共同申请，
        // 债务还清只是让担保消灭的实体条件成立，登记簿上要让渡担保还挂着。
        collateralService.markRepaid(vin, settlementAmount, null, true, actor);
        collateralService.markReleased(vin, "SETTLED", actor);

        return buildResponse(saved, released, false);
    }

    /**
     * 质押中提前赎回：经销商在车辆<b>尚未售出</b>时提前还清，把物权赎回去。
     *
     * <p><b>为什么需要独立用例，而不是复用 {@link #settle}</b>：{@code settle} 要求车辆
     * 处于 {@code SOLD_PENDING} 或 {@code OVERDUE}，即"车已经卖掉/已经违约"。
     * 而提前还款赎车时车<b>还在库里</b>（{@code IN_STOCK}），
     * 原实现会把这类请求一律拒掉，经销商只能线下处理，账实立刻分叉。</p>
     *
     * <p><b>原子完成五件事</b>：
     * <ol>
     *   <li>本金核销（本金 + 累计利息一次结清，金额不符抛 REPAY_001）；</li>
     *   <li>车辆台账 {@code IN_STOCK → REDEEMED}（<b>不是 SETTLED</b>——车还在，可再融资）；</li>
     *   <li>垫款记录 {@code PAID → REDEEMED}，原因记 {@code EARLY_REDEEMED}，
     *       释放该 VIN 的唯一垫款名额；</li>
     *   <li>解除担保（生成抹消登记待办）；</li>
     *   <li>额度回补，立即可用于下一台垫款。</li>
     * </ol>
     *
     * <p><b>赎回后再次融资</b>：车辆处于 REDEEMED 时 {@code applyAdvance} 直接放行，
     * 无需先人工改台账。多笔历史垫款会以多条 {@code REDEEMED} 记录并存——
     * 这正是"同一台车循环融资"的正常形态，不是脏数据。</p>
     *
     * <p><b>幂等</b>：同一 {@code idempotencyKey} 返回首次结果。</p>
     *
     * @param vin              车架号
     * @param settlementAmount 实际还款金额（本金 + 利息，整数日元）
     * @param operatorId       操作人 ID
     * @param idempotencyKey   幂等键
     * @param actor            操作人（审计留痕）
     * @return 赎回结果：还款号、本金、利息、回补额度、车辆状态（REDEEMED）
     */
    @Transactional
    public Map<String, Object> redeem(String vin, long settlementAmount, Long operatorId,
                                      String idempotencyKey, String actor) {
        var existing = repaymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Repayment r = existing.get();
            Map<String, Object> replay = buildResponse(r, 0L, true);
            replay.put("vehicleStatus", "REDEEMED");
            return replay;
        }

        VehicleUnit unit = vehicleUnitRepository.findByVinForUpdate(vin)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_001, "车辆不存在：" + vin));

        if (unit.getStatus() != VehicleStatus.IN_STOCK) {
            throw new BusinessException(ErrorCode.VEHICLE_002, Map.of(
                    "vin", vin, "currentStatus", String.valueOf(unit.getStatus()),
                    "reason", "仅质押在库（IN_STOCK）车辆可提前赎回；"
                            + "已售出的车请走结清流程，逾期/处置中的车请走违约处置流程"));
        }
        long principal = unit.getPrincipalBalance();
        if (principal <= 0L) {
            throw new BusinessException(ErrorCode.REPAY_002, Map.of(
                    "vin", vin, "principalBalance", principal,
                    "reason", "该车未偿本金为 0，无可赎回的垫款"));
        }

        long interest = interestLedgerRepository.sumInterestByVin(vin);
        long expected = principal + interest;
        if (settlementAmount != expected) {
            throw new BusinessException(ErrorCode.REPAY_001, Map.of(
                    "expected", expected, "principal", principal, "interest", interest,
                    "actual", settlementAmount));
        }

        // 1) 核销本金 → 2) 车辆转 REDEEMED（车还在库，可再次融资）→ 3) 解除担保
        unit.applyPrincipalPayment(principal);
        unit.redeem(LocalDate.now());
        unit.releaseSecurity();
        vehicleUnitRepository.save(unit);

        // 4) 垫款记录 PAID → REDEEMED，释放该 VIN 的唯一垫款名额
        closeAdvanceAsRedeemed(vin, "EARLY_REDEEMED", actor);

        // 5) 额度回补（行锁）
        long released = 0L;
        if (unit.getCreditLineId() != null) {
            CreditLine line = creditLineRepository.findByIdForUpdate(unit.getCreditLineId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "额度不存在"));
            released = principal;
            line.release(principal);
            creditLineRepository.save(line);
        }

        Repayment repayment = new Repayment();
        repayment.setVin(vin);
        repayment.setPrincipal(principal);
        repayment.setInterest(interest);
        repayment.setTotalAmount(settlementAmount);
        repayment.setRepaymentType("REDEEM");
        repayment.setSettledAt(LocalDateTime.now());
        repayment.setOperatorId(operatorId);
        repayment.setIdempotencyKey(idempotencyKey);
        Repayment saved = repaymentRepository.save(repayment);

        supportRepository.writeAudit(actor, "REPAYMENT", "VEHICLE", vin,
                "principal=" + principal, "redeemed,interest=" + interest + ",released=" + released, null);

        collateralService.markRepaid(vin, settlementAmount, null, true, actor);
        collateralService.markReleased(vin, "REDEEMED", actor);

        Map<String, Object> data = buildResponse(saved, released, false);
        data.put("vehicleStatus", "REDEEMED");
        data.put("refinanceable", true);
        return data;
    }

    /**
     * 把该 VIN 上仍在占用名额的垫款记录推进到 {@code REDEEMED}（终态）。
     *
     * <p><b>为什么必须做</b>：{@code PAID} 的语义是"债权存续中"，它占用
     * "一车一笔垫款"的唯一名额（见 {@code uk_adv_vin_active}）。
     * 结清后若仍停在 PAID，这台车就永远无法二次融资——
     * 而"还清后再借"是库存融资的标准业务循环。</p>
     *
     * <p>找不到占用中的垫款时<b>不报错</b>：历史数据可能只建了台账、没有垫款记录
     * （V13 迁移已把这类车拆回 CREATED），此时还款仍需继续。</p>
     *
     * @param vin    车架号
     * @param reason 终结原因：{@code SOLD_SETTLED}（售出结清）/ {@code EARLY_REDEEMED}（提前赎回）
     * @param actor  操作人（审计留痕）
     */
    private void closeAdvanceAsRedeemed(String vin, String reason, String actor) {
        advanceRepository.findOccupyingByVin(vin).ifPresent(a -> {
            if (a.getStatus() == AdvanceStatus.REDEEMED) {
                return; // 幂等：已终结过就不重复写
            }
            String from = String.valueOf(a.getStatus());
            a.setStatus(AdvanceStatus.REDEEMED);
            a.setClosedAt(LocalDateTime.now());
            a.setClosedReason(reason);
            advanceRepository.save(a);
            supportRepository.writeAudit(actor, "ADVANCE_CLOSE", "ADVANCE", a.getAdvanceNo(),
                    "status=" + from, "status=REDEEMED,reason=" + reason, null);
        });
    }

    /**
     * 提前部分还款（业务闭环 P1：区别于整台结清）。
     *
     * <p><b>与整台结清 {@link #settle} 的关键区别</b>（三件事只做一件）：
     * <ul>
     *   <li>只扣减未偿本金，<b>不解除担保</b>；</li>
     *   <li><b>不回补额度</b>（车辆仍在库/在途，占用关系未解除，直到整台结清才释放）；</li>
     *   <li>利息照常逐日累计，直到整台结清才一次性清算（利息以流水汇总为准）；</li>
     *   <li>还款类型记 {@code PARTIAL}，对账时与 {@code FULL} 明确区分。</li>
     * </ul>
     * 典型场景：车商资金宽裕时先还一部分本金，<b>后续日息基数随之下降</b>
     * （部分还款后利息基数变化这一边界用例的落地）。</p>
     *
     * <p><b>幂等</b>：同一 {@code idempotencyKey} 直接返回首次结果。</p>
     *
     * @param vin            车架号
     * @param principalAmount 部分还款本金（整数日元，必须 ≤ 未偿本金）
     * @param idempotencyKey 幂等键
     * @param actor          操作人（审计留痕）
     * @return 部分还款结果：还款号、本金、剩余余额、是否解除担保
     */
    @Transactional
    public Map<String, Object> partialRepay(String vin, long principalAmount,
                                            String idempotencyKey, String actor) {
        // 幂等
        var existing = repaymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Repayment r = existing.get();
            return buildPartialResponse(r, true, 0L);
        }

        VehicleUnit unit = vehicleUnitRepository.findByVinForUpdate(vin)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_001, "车辆不存在：" + vin));
        if (unit.getStatus() == VehicleStatus.SETTLED || unit.getStatus() == VehicleStatus.CLOSED) {
            throw new BusinessException(ErrorCode.REPAY_002, "该台已结清/关闭，不可部分还款：" + vin);
        }
        if (principalAmount <= 0) {
            throw new BusinessException(ErrorCode.VALID_002, "部分还款本金必须为正整数日元");
        }
        if (principalAmount > unit.getPrincipalBalance()) {
            throw new BusinessException(ErrorCode.REPAY_001, Map.of(
                    "principalBalance", unit.getPrincipalBalance(), "requested", principalAmount));
        }

        // T1-7（B1b）：先按"还款动因 + 本金下降 + 质押物状态"判定是否回补额度。
        // 动因必须显式判定——库龄递减（强制）虽也走本金下降，但它走独立的 payPeriod 用例，
        // 这里绝不因"本金下降"就回补，避免把强制压降的敞口又放回去。
        VehicleUnit.RepaymentCause cause = resolveRepaymentCause(unit.getStatus());
        VehicleUnit.ReleaseDecision decision = VehicleUnit.evaluateCreditRelease(unit, principalAmount, cause);

        // 只扣本金；绝不调用 releaseSecurity / markReleased——
        // 提前部分还款不触发担保消灭，担保解除仍只由结清/赎回/处置完成用例驱动。
        unit.applyPrincipalPayment(principalAmount);
        vehicleUnitRepository.save(unit);

        // 仅在判定允许时回补额度（行锁保护，与结清回补同样的并发约束）
        long released = 0L;
        if (decision.release() && unit.getCreditLineId() != null) {
            CreditLine line = creditLineRepository.findByIdForUpdate(unit.getCreditLineId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "额度不存在"));
            released = decision.amount();
            line.release(released);
            creditLineRepository.save(line);
        }

        Repayment repayment = new Repayment();
        repayment.setVin(vin);
        repayment.setPrincipal(principalAmount);
        repayment.setInterest(0L);
        repayment.setTotalAmount(principalAmount);
        repayment.setSettledAt(LocalDateTime.now());
        repayment.setIdempotencyKey(idempotencyKey);
        repayment.setRepaymentType("PARTIAL");
        repaymentRepository.save(repayment);

        supportRepository.writeAudit(actor, "PARTIAL_REPAY", "VEHICLE", vin,
                "balance_before=?", "paid=" + principalAmount + ",balance=" + unit.getPrincipalBalance()
                        + ",released=" + released + ",decision=" + decision.reason(), null);

        return buildPartialResponse(repayment, false, released);
    }

    /**
     * 从车辆状态推断还款动因。
     *
     * <p>在库车视为主动提前还本金（自愿）；已逾期视为逾期补款；
     * 其余状态（如已售未解押）虽传 VOLUNTARY_PREPAY，判定策略仍会按状态兜底拒绝回补。</p>
     */
    private VehicleUnit.RepaymentCause resolveRepaymentCause(VehicleStatus status) {
        if (status == VehicleStatus.OVERDUE) {
            return VehicleUnit.RepaymentCause.OVERDUE_MAKEUP;
        }
        return VehicleUnit.RepaymentCause.VOLUNTARY_PREPAY;
    }

    private Map<String, Object> buildPartialResponse(Repayment r, boolean replay, long released) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("repaymentId", r.getRepaymentNo());
        data.put("repaymentType", r.getRepaymentType());
        data.put("principal", r.getPrincipal());
        data.put("releasedCredit", released);
        data.put("securityReleased", false);
        data.put("idempotentReplay", replay);
        return data;
    }

    private Map<String, Object> buildResponse(Repayment r, long released, boolean replay) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("repaymentId", r.getRepaymentNo());
        data.put("vehicleStatus", "SETTLED");
        data.put("principal", r.getPrincipal());
        data.put("interest", r.getInterest());
        data.put("releasedCredit", released);
        data.put("securityReleased", true);
        data.put("idempotentReplay", replay);
        return data;
    }
}
