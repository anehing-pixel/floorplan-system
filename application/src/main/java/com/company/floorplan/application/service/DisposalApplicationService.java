package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.DisposalCase;
import com.company.floorplan.domain.model.VehicleStatus;
import com.company.floorplan.domain.model.VehicleUnit;
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

    public DisposalApplicationService(DisposalCaseRepository disposalCaseRepository,
                                       VehicleUnitRepository vehicleUnitRepository,
                                       InterestLedgerRepository interestLedgerRepository,
                                       SupportRepository supportRepository,
                                       CollateralLedgerApplicationService collateralService) {
        this.disposalCaseRepository = disposalCaseRepository;
        this.vehicleUnitRepository = vehicleUnitRepository;
        this.interestLedgerRepository = interestLedgerRepository;
        this.supportRepository = supportRepository;
        this.collateralService = collateralService;
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
        disposalCaseRepository.save(c);

        // 车辆进入处置中（OVERDUE/DISPOSING 均可；状态机合法）
        if (unit.getStatus() != VehicleStatus.DISPOSING) {
            if (unit.getStatus() == VehicleStatus.OVERDUE || unit.getStatus() == VehicleStatus.IN_STOCK
                    || unit.getStatus() == VehicleStatus.SOLD_PENDING) {
                unit.dispose();
                vehicleUnitRepository.save(unit);
            }
        }

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

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("caseNo", caseNo);
        data.put("receivable", receivable);
        data.put("proceeds", proceeds);
        data.put("lossAmount", c.getLossAmount());
        data.put("status", c.getStatus().name());
        return data;
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
