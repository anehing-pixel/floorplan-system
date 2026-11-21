package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.AgingPlan;
import com.company.floorplan.domain.model.VehicleStatus;
import com.company.floorplan.domain.model.VehicleUnit;
import com.company.floorplan.domain.repository.AgingPlanRepository;
import com.company.floorplan.domain.repository.CreditLineRepository;
import com.company.floorplan.domain.repository.CurtailmentPaymentRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.domain.repository.VehicleUnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 库龄递减还款用例（业务闭环 P0-① 库龄递减还款）。
 *
 * <p><b>业务背景</b>：车辆在库超过约定天数（预设 90 天）仍未售出，金融公司强制车商
 * 分期偿还部分本金，防止车辆贬值到跌破垫款余额。计划由日终作业
 * {@code BatchApplicationService.runAgingReduction} 生成（规则版本化），
 * 本服务负责"每期实际核销"。</p>
 *
 * <p><b>三件事（与结清对称）</b>：
 * <ol>
 *   <li>扣减车辆未偿本金（{@code VehicleUnit.applyPrincipalPayment}）；</li>
 *   <li>推进递减计划（{@code paid_periods + 1}，顺延 {@code next_due_date}）；</li>
 *   <li>写核销流水（{@code curtailment_payments}，含幂等键，供审计）。</li>
 * </ol>
 * 本金减少后，<b>后续日息基数随之下降</b>——这正是"部分还款后利息基数变化"的边界用例落地。</p>
 *
 * <p><b>不释放额度</b>：库龄递减是部分本金偿还，额度仍被占用直到整台结清，
 * 因此此处不回补额度（区别于 {@code RepaymentApplicationService.settle}）。</p>
 */
@Service
public class CurtailmentApplicationService {

    private final VehicleUnitRepository vehicleUnitRepository;
    private final AgingPlanRepository agingPlanRepository;
    private final CreditLineRepository creditLineRepository;
    private final CurtailmentPaymentRepository curtailmentPaymentRepository;
    private final SupportRepository supportRepository;

    public CurtailmentApplicationService(VehicleUnitRepository vehicleUnitRepository,
                                         AgingPlanRepository agingPlanRepository,
                                         CreditLineRepository creditLineRepository,
                                         CurtailmentPaymentRepository curtailmentPaymentRepository,
                                         SupportRepository supportRepository) {
        this.vehicleUnitRepository = vehicleUnitRepository;
        this.agingPlanRepository = agingPlanRepository;
        this.creditLineRepository = creditLineRepository;
        this.curtailmentPaymentRepository = curtailmentPaymentRepository;
        this.supportRepository = supportRepository;
    }

    /**
     * 偿付一期库龄递减还款。
     *
     * @param vin            车架号
     * @param idempotencyKey 幂等键（必填，防重复扣款）
     * @param actor          操作人（审计留痕）
     * @return 核销结果：期号、本期金额、剩余本金、计划状态
     */
    @Transactional
    public Map<String, Object> payPeriod(String vin, String idempotencyKey, String actor) {
        VehicleUnit unit = vehicleUnitRepository.findByVinForUpdate(vin)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_001, "车辆不存在：" + vin));
        AgingPlan plan = agingPlanRepository.findByVin(vin)
                .orElseThrow(() -> new BusinessException(ErrorCode.CURTAIL_001, "无进行中的递减计划：" + vin));
        if (!"ACTIVE".equals(plan.getStatus())) {
            throw new BusinessException(ErrorCode.CURTAIL_001, "递减计划非进行中：" + plan.getStatus());
        }

        // 幂等：同一幂等键已入账过 → 直接重放首次结果，绝不重复扣本金。
        // 必须"先查后扣"：若先扣本金再靠唯一约束拦截，重放时本金已扣、
        // 流水被 ON CONFLICT DO NOTHING 吞掉，造成账实不符（资损）。
        java.util.Optional<long[]> existing = curtailmentPaymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            long replayPeriod = existing.get()[0];
            long replayAmount = existing.get()[1];
            Map<String, Object> replay = new LinkedHashMap<>();
            replay.put("vin", vin);
            replay.put("periodNo", (int) replayPeriod);
            replay.put("periodAmount", replayAmount);
            replay.put("principalBalance", unit.getPrincipalBalance());
            replay.put("idempotentReplay", true);
            return replay;
        }

        // T2-4（M6/G6）：车已售出、结清或进入处置时停止催款——
        // 原实现对已不在库的车仍按计划扣款，会造成账实不符。
        if (unit.getStatus() != VehicleStatus.IN_STOCK) {
            throw new BusinessException(ErrorCode.CURTAIL_001,
                    "车辆已不在库（" + unit.getStatus() + "），递减计划停止催款");
        }

        int period = plan.getPaidPeriods() + 1;
        // T2-4（M6/G2）：应还额动态重算——按当前库龄分层比例 × 当前未偿本金，
        // 不再使用计划生成时算死的 reduceAmount（旧计划回退到记录金额，保持兼容）。
        long amount = AgingPlan.currentAmount(unit.getPrincipalBalance(), unit.getAgingDays(), plan.getReduceAmount());
        amount = Math.min(amount, unit.getPrincipalBalance()); // 不超过剩余本金

        unit.applyPrincipalPayment(amount);
        vehicleUnitRepository.save(unit);

        plan.setPaidPeriods(period);
        LocalDate next = plan.getNextDueDate() == null ? LocalDate.now() : plan.getNextDueDate();
        plan.setNextDueDate(next.plusDays(plan.getPeriodDays() == null ? 30 : plan.getPeriodDays()));

        // 总期数 = 向上取整(1 / 每期比例)，如每期 10% → 共 10 期
        BigDecimal ratio = plan.getReduceRatio() == null ? BigDecimal.valueOf(0.10) : plan.getReduceRatio();
        int totalPeriods = (int) Math.ceil(BigDecimal.ONE.divide(ratio, 4, RoundingMode.HALF_UP).doubleValue());
        if (period >= totalPeriods) {
            plan.setStatus("COMPLETED");
        }
        agingPlanRepository.save(plan);

        curtailmentPaymentRepository.save(vin, period, amount, idempotencyKey, actor);

        supportRepository.writeAudit(actor, "CURTAIL_PAY", "VEHICLE", vin,
                "period=" + (period - 1), "period=" + period + ",amount=" + amount + ",balance=" + unit.getPrincipalBalance(), null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vin", vin);
        data.put("periodNo", period);
        data.put("totalPeriods", totalPeriods);
        data.put("periodAmount", amount);
        data.put("principalBalance", unit.getPrincipalBalance());
        data.put("planStatus", plan.getStatus());
        return data;
    }
}
