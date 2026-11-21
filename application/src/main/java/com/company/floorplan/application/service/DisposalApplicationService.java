package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.CreditLine;
import com.company.floorplan.domain.model.DisposalCase;
import com.company.floorplan.domain.model.VehicleStatus;
import com.company.floorplan.domain.model.VehicleUnit;
import com.company.floorplan.domain.repository.CreditLineRepository;
import com.company.floorplan.domain.repository.DisposalCaseRepository;
import com.company.floorplan.domain.repository.InterestLedgerRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.domain.repository.VehicleUnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 违约处置用例（业务闭环 P0-② 违约处置闭环）。
 *
 * <p><b>这是本次重点补全的闭环</b>：旧版只有 {@code alerts} 告警、没有处置动作。
 * 本服务把"识别 → 开单 → 收车 → 变现 → 损失认定"串成完整流程，
 * 关键不变量：<b>损失 = 应收 − 变现所得</b>，且结案时车辆进入终态 CLOSED。</p>
 *
 * <p><b>应收口径</b>：结案时从车辆实算
 * 应收 = 未偿本金 + 计息流水汇总（与结清一致，利息以流水汇总为准）。</p>
 */
@Service
public class DisposalApplicationService {

    private final DisposalCaseRepository disposalCaseRepository;
    private final VehicleUnitRepository vehicleUnitRepository;
    private final InterestLedgerRepository interestLedgerRepository;
    private final SupportRepository supportRepository;
    /** T1-1（B3）：处置开单冻结额度，需行锁读写 credit_lines。 */
    private final CreditLineRepository creditLineRepository;

    public DisposalApplicationService(DisposalCaseRepository disposalCaseRepository,
                                       VehicleUnitRepository vehicleUnitRepository,
                                       InterestLedgerRepository interestLedgerRepository,
                                       SupportRepository supportRepository,
                                       CollateralLedgerApplicationService collateralService,
                                       CreditLineRepository creditLineRepository) {
        this.disposalCaseRepository = disposalCaseRepository;
        this.vehicleUnitRepository = vehicleUnitRepository;
        this.interestLedgerRepository = interestLedgerRepository;
        this.supportRepository = supportRepository;
        this.collateralService = collateralService;
        this.creditLineRepository = creditLineRepository;
    }

    /** 担保物台账服务：处置痕迹与抹消待办。 */
    private final CollateralLedgerApplicationService collateralService;

    /** 开单：识别严重违约 → 建处置工单 → 车辆进入处置中（DISPOSING）。 */
    @Transactional
    public Map<String, Object> open(String vin, String reason, String actor) {
        VehicleUnit unit = vehicleUnitRepository.findByVinForUpdate(vin)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_001, "车辆不存在：" + vin));
        disposalCaseRepository.findLatestByVin(vin).ifPresent(existing -> {
            if (!existing.getStatus().isTerminal()) {
                throw new BusinessException(ErrorCode.DISPOSAL_001, "该车已有未结案处置工单：" + existing.getCaseNo());
            }
        });

        DisposalCase c = new DisposalCase();
        c.setCaseNo("DSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        c.setVin(vin);
        c.setDealerId(unit.getDealerId());
        c.setCreditLineId(unit.getCreditLineId());
        c.setReason(reason == null ? "OOT" : reason);
        c.open();

        // 车辆进入处置中（OVERDUE/DISPOSING 均可；状态机合法）
        if (unit.getStatus() != VehicleStatus.DISPOSING) {
            if (unit.getStatus() == VehicleStatus.OVERDUE || unit.getStatus() == VehicleStatus.IN_STOCK
                    || unit.getStatus() == VehicleStatus.SOLD_PENDING) {
                unit.dispose();
                vehicleUnitRepository.save(unit);
            }
        }

        // T1-1（B3 处置冻结额度）：开单即冻结该车未偿本金敞口，可用额度同步下降。
        // 违约经销商不得再占用新额度放款——沿用 CreditLine 的行锁读写与冻结保护。
        // B-2：必须记录【本次真实增量】而非累计冻结额/敞口——freeze() 内部按
        // min(exposure, available()) 封顶，freezeForDisposal(0) 更是只置状态不加金额；
        // 只有前后差值才能在结案/撤销时精确配对回退（多单叠加时不会误解冻）。
        if (unit.getCreditLineId() != null) {
            CreditLine line = creditLineRepository.findByIdForUpdate(unit.getCreditLineId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "额度不存在"));
            long exposure = unit.getPrincipalBalance();
            long frozenBefore = line.getFrozenAmount();
            long frozenTotal = line.freezeForDisposal(exposure);
            long frozenIncrement = frozenTotal - frozenBefore;
            c.setFrozenIncrement(frozenIncrement);
            creditLineRepository.save(line);
            supportRepository.writeAudit(actor, "DISPOSAL_FREEZE", "CREDIT_LINE",
                    String.valueOf(line.getId()), null,
                    "caseNo=" + c.getCaseNo() + ",exposure=" + exposure
                            + ",frozenIncrement=" + frozenIncrement
                            + ",frozenTotal=" + frozenTotal, null);
        }
        // 单次落库：带上本次冻结增量（V22），供结案/撤销时精确配对解冻
        disposalCaseRepository.save(c);

        supportRepository.createAlert(unit.getDealerId(), vin, "DISPOSAL_OPEN", "HIGH",
                "开违约处置工单，原因=" + c.getReason());
        supportRepository.writeAudit(actor, "DISPOSAL_OPEN", "VEHICLE", vin,
                null, "caseNo=" + c.getCaseNo() + ",reason=" + c.getReason(), null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("caseNo", c.getCaseNo());
        data.put("vin", vin);
        data.put("status", c.getStatus().name());
        return data;
    }

    /** 收车：OPEN → REPOSSESSED。 */
    @Transactional
    public Map<String, Object> repossess(String caseNo, String actor) {
        DisposalCase c = requireCase(caseNo);
        c.repossess();
        disposalCaseRepository.save(c);
        supportRepository.writeAudit(actor, "DISPOSAL_REPO", "DISPOSAL", caseNo, null, "REPOSSESSED", null);
        return result(c);
    }

    /** 进入变现：REPOSSESSED → LIQUIDATING。 */
    @Transactional
    public Map<String, Object> liquidate(String caseNo, String actor) {
        DisposalCase c = requireCase(caseNo);
        c.startLiquidating();
        disposalCaseRepository.save(c);
        supportRepository.writeAudit(actor, "DISPOSAL_LIQ", "DISPOSAL", caseNo, null, "LIQUIDATING", null);
        return result(c);
    }

    /** T2-6：违约通知已送达（含送达证明），OPEN → NOTIFIED。 */
    @Transactional
    public Map<String, Object> notifyParty(String caseNo, String actor) {
        DisposalCase c = requireCase(caseNo);
        c.notifyParty();
        disposalCaseRepository.save(c);
        supportRepository.writeAudit(actor, "DISPOSAL_NOTIFY", "DISPOSAL", caseNo, null, "NOTIFIED", null);
        return result(c);
    }

    /** T2-6：进入法律程序（需法务确认），NOTIFIED → LEGAL_ACTION。 */
    @Transactional
    public Map<String, Object> startLegalAction(String caseNo, String actor) {
        DisposalCase c = requireCase(caseNo);
        c.startLegalAction();
        disposalCaseRepository.save(c);
        supportRepository.writeAudit(actor, "DISPOSAL_LEGAL", "DISPOSAL", caseNo, null, "LEGAL_ACTION", null);
        return result(c);
    }

    /**
     * T2-6 撤销通道：客户补救期内还清或和解 → CANCELLED，车辆退回 OVERDUE。
     *
     * <p>TODO(T2-6)：risk-service 的 /api/disposals 需暴露对应端点并做审批留痕；
     * 本 core 用例只负责状态机与台账联动。</p>
     */
    @Transactional
    public Map<String, Object> cancel(String caseNo, String reason, String actor) {
        DisposalCase c = requireCase(caseNo);
        c.cancel();
        disposalCaseRepository.save(c);
        VehicleUnit unit = vehicleUnitRepository.findByVinForUpdate(c.getVin()).orElse(null);
        if (unit != null && unit.getStatus() == VehicleStatus.DISPOSING) {
            unit.cancelDisposal();
            vehicleUnitRepository.save(unit);
        }
        // B-2：撤销处置 → 配对解冻额度线，恢复该经销商正常放款能力
        unfreezeForCase(c, actor, "DISPOSAL_CANCEL");
        supportRepository.writeAudit(actor, "DISPOSAL_CANCEL", "DISPOSAL", caseNo,
                "status=DISPOSING", "status=CANCELLED,reason=" + reason, null);
        return result(c);
    }

    /**
     * 结案：根据变现所得是否满足应收，形成"收回"或"损失"终态。
     *
     * @param caseNo   处置单号
     * @param proceeds 实际变现所得（整数日元）
     * @param actor    操作人
     */
    @Transactional
    public Map<String, Object> close(String caseNo, long proceeds, String actor) {
        DisposalCase c = requireCase(caseNo);
        VehicleUnit unit = vehicleUnitRepository.findByVinForUpdate(c.getVin()).orElse(null);

        long receivable = 0L;
        if (unit != null) {
            long principal = unit.getPrincipalBalance();
            long interest = interestLedgerRepository.sumInterestByVin(c.getVin());
            receivable = principal + interest;
        }
        c.close(receivable, proceeds);
        disposalCaseRepository.save(c);

        if (unit != null) {
            unit.close();
            vehicleUnitRepository.save(unit);
        }
        supportRepository.writeAudit(actor, "DISPOSAL_CLOSE", "DISPOSAL", caseNo,
                null, "receivable=" + receivable + ",proceeds=" + proceeds + ",loss=" + c.getLossAmount()
                        + ",status=" + c.getStatus().name(), null);

        // 担保物台账：处置完成 → 记录处置痕迹 + 担保实体权利消灭（生成抹消登记待办）。
        // 处置后车辆已变现/收回，担保随之消灭，但登记簿上仍需办抹消——同样需双方共同申请。
        if (unit != null) {
            collateralService.markDisposed(c.getVin(), caseNo, actor);
            collateralService.markReleased(c.getVin(), "DISPOSED", actor);
        }

        // B-2：结案 → 配对解冻额度线。必须计算在 markReleased 之后、方法返回之前，
        // 与 cancel() 共用同一段逻辑；否则额度线永久 FROZEN，其他正常车放款被阻断。
        unfreezeForCase(c, actor, "DISPOSAL_CLOSE");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("caseNo", caseNo);
        data.put("receivable", receivable);
        data.put("proceeds", proceeds);
        data.put("lossAmount", c.getLossAmount());
        data.put("status", c.getStatus().name());
        return data;
    }

    /**
     * B-2：处置单终态化（撤销 / 结案）后的<b>配对解冻</b>。
     *
     * <p>按开单时落库的 {@code frozenIncrement} 精确回退「本单」对额度线的冻结量，
     * 不能用敞口（会被 {@code min(exposure, available())} 封顶），也不能用累计冻结额
     * （多单叠加会重复回退，形成资损）。只有本额度线已无其他未结案处置单
     * <b>且</b>冻结额归零时才恢复 ACTIVE，否则保持 FROZEN——资金安全红线不因
     * 单笔结案而被削弱。</p>
     *
     * <p><b>幂等</b>：调用方 {@code cancel()} / {@code close()} 均先做状态机流转，
     * 终态单重复调用会在 {@code transit()} 抛 {@code DISPOSAL_002} 并整体回滚，
     * 本方法不会二次执行、也不会重复解冻。</p>
     *
     * @param c         已置终态的处置单（携带开单时记录的冻结增量）
     * @param actor     操作人
     * @param eventType 触发来源（DISPOSAL_CANCEL / DISPOSAL_CLOSE），仅用于审计留痕
     */
    private void unfreezeForCase(DisposalCase c, String actor, String eventType) {
        if (c.getCreditLineId() == null) {
            return; // 未挂额度线的车（如未放款）开单时本就没有冻结
        }
        CreditLine line = creditLineRepository.findByIdForUpdate(c.getCreditLineId()).orElse(null);
        if (line == null) {
            // 额度线已不存在：无需解冻，也不该阻断处置闭环（历史单/数据清理场景）
            return;
        }
        // 本单已置终态并被 excludeCaseNo 排除；此处统计的是"其他仍未结案"的单
        long otherOpen = disposalCaseRepository
                .countOtherOpenByCreditLine(c.getCreditLineId(), c.getCaseNo());
        boolean lastOpenCase = otherOpen == 0L;
        long frozenBefore = line.getFrozenAmount();
        line.unfreezeForDisposal(c.getFrozenIncrement(), lastOpenCase);
        creditLineRepository.save(line);
        supportRepository.writeAudit(actor, "DISPOSAL_UNFREEZE", "CREDIT_LINE",
                String.valueOf(line.getId()), null,
                "caseNo=" + c.getCaseNo() + ",event=" + eventType
                        + ",increment=" + c.getFrozenIncrement()
                        + ",frozenBefore=" + frozenBefore + ",frozenAfter=" + line.getFrozenAmount()
                        + ",otherOpen=" + otherOpen + ",status=" + line.getStatus(), null);
    }

    private DisposalCase requireCase(String caseNo) {
        return disposalCaseRepository.findByCaseNo(caseNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.DISPOSAL_003, "处置工单不存在：" + caseNo));
    }

    private Map<String, Object> result(DisposalCase c) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("caseNo", c.getCaseNo());
        data.put("status", c.getStatus().name());
        return data;
    }
}
