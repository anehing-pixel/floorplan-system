package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.ExtensionRequest;
import com.company.floorplan.domain.model.VehicleStatus;
import com.company.floorplan.domain.model.VehicleUnit;
import com.company.floorplan.domain.repository.ExtensionRequestRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.domain.repository.VehicleUnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 展期续贷用例（业务闭环 P1：延长结算窗口）。
 *
 * <p><b>业务背景</b>：车商零售成交后，因客户贷款审批、过户等客观原因无法在结算窗口内结清，
 * 可申请展期，将 {@code settle_due_date} 顺延 {@code extDays} 天。
 * 展期后即视为"新的到期日"，不再触发超窗口告警（除非再次超过新到期日）。</p>
 *
 * <p><b>合法前提</b>：仅"已售待还"（{@code SOLD_PENDING}）车辆可展期；
 * 展期同时清除此前可能已产生的 OOT/逾期标记（属合法顺延）。</p>
 */
@Service
public class ExtensionApplicationService {

    private final ExtensionRequestRepository extensionRequestRepository;
    private final VehicleUnitRepository vehicleUnitRepository;
    private final SupportRepository supportRepository;

    public ExtensionApplicationService(ExtensionRequestRepository extensionRequestRepository,
                                       VehicleUnitRepository vehicleUnitRepository,
                                       SupportRepository supportRepository) {
        this.extensionRequestRepository = extensionRequestRepository;
        this.vehicleUnitRepository = vehicleUnitRepository;
        this.supportRepository = supportRepository;
    }

    /** 申请展期。 */
    @Transactional
    public Map<String, Object> apply(String vin, int extDays, String reason, String actor) {
        if (extDays <= 0) {
            throw new BusinessException(ErrorCode.VALID_002, "展期天数必须为正整数");
        }
        VehicleUnit unit = vehicleUnitRepository.findByVinForUpdate(vin)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_001, "车辆不存在：" + vin));
        if (unit.getStatus() != VehicleStatus.SOLD_PENDING) {
            throw new BusinessException(ErrorCode.VEHICLE_002, Map.of(
                    "vin", vin, "currentStatus", String.valueOf(unit.getStatus()),
                    "hint", "仅已售待还(SOLD_PENDING)车辆可展期"));
        }
        LocalDate originalDue = unit.getSettleDueDate();
        LocalDate newDue = originalDue == null ? LocalDate.now().plusDays(extDays) : originalDue.plusDays(extDays);
        unit.setSettleDueDate(newDue);
        // 合法顺延：清除此前超窗口产生的 OOT/逾期标记
        unit.setOotSuspected(false);
        unit.setOverdueDays(0);
        unit.setRiskLevel("LOW");
        vehicleUnitRepository.save(unit);

        ExtensionRequest e = new ExtensionRequest();
        e.setRequestNo("EXT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        e.setVin(vin);
        e.setExtDays(extDays);
        e.setReason(reason);
        e.setStatus("APPROVED");
        e.setOriginalDue(originalDue);
        e.setNewDue(newDue);
        extensionRequestRepository.save(e);

        supportRepository.writeAudit(actor, "EXTENSION", "VEHICLE", vin,
                "due=" + originalDue, "newDue=" + newDue, null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestNo", e.getRequestNo());
        data.put("vin", vin);
        data.put("originalDue", originalDue == null ? null : originalDue.toString());
        data.put("newDue", newDue.toString());
        data.put("status", "APPROVED");
        return data;
    }
}
