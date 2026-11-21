package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.RepurchaseRequest;
import com.company.floorplan.domain.model.VehicleUnit;
import com.company.floorplan.domain.repository.RepurchaseRequestRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.domain.repository.VehicleUnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 厂家回购用例（业务闭环 P1：日本新车渠道核心信用增强 メーカー買取約束）。
 *
 * <p><b>业务定位</b>：区别于违约处置——回购是<b>正常合同履约</b>（厂家事先承诺买回），
 * 回款直接冲抵该台垫款本金。这是日本渠道"不依赖强物理监管"的关键防线。</p>
 *
 * <p><b>兑现动作</b>：扣减车辆未偿本金（{@code applyPrincipalPayment}），
 * 若本金降至 0 仍需结清利息（由常规结清流程处理）。本服务只冲抵本金，不自动结清。</p>
 */
@Service
public class RepurchaseApplicationService {

    private final RepurchaseRequestRepository repurchaseRequestRepository;
    private final VehicleUnitRepository vehicleUnitRepository;
    private final SupportRepository supportRepository;

    public RepurchaseApplicationService(RepurchaseRequestRepository repurchaseRequestRepository,
                                        VehicleUnitRepository vehicleUnitRepository,
                                        SupportRepository supportRepository) {
        this.repurchaseRequestRepository = repurchaseRequestRepository;
        this.vehicleUnitRepository = vehicleUnitRepository;
        this.supportRepository = supportRepository;
    }

    /** 申请厂家回购。 */
    @Transactional
    public Map<String, Object> request(String vin, String reason, String actor) {
        VehicleUnit unit = vehicleUnitRepository.findByVin(vin)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_001, "车辆不存在：" + vin));
        RepurchaseRequest r = new RepurchaseRequest();
        r.setRequestNo("RPR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        r.setVin(vin);
        r.setDealerId(unit.getDealerId());
        r.setCreditLineId(unit.getCreditLineId());
        r.setReason(reason);
        r.setStatus("REQUESTED");
        repurchaseRequestRepository.save(r);
        supportRepository.writeAudit(actor, "REPURCHASE_REQ", "VEHICLE", vin,
                null, "requestNo=" + r.getRequestNo(), null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestNo", r.getRequestNo());
        data.put("vin", vin);
        data.put("status", "REQUESTED");
        return data;
    }

    /**
     * 兑现回购：厂家回款冲抵垫款本金。
     *
     * @param requestNo 回购单号
     * @param amount    厂家回购金额（整数日元）
     * @param actor     操作人
     */
    @Transactional
    public Map<String, Object> fulfill(String requestNo, long amount, String actor) {
        RepurchaseRequest r = repurchaseRequestRepository.findByRequestNo(requestNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPURCHASE_001, "回购请求不存在：" + requestNo));
        if (!"REQUESTED".equals(r.getStatus())) {
            throw new BusinessException(ErrorCode.REPURCHASE_001, "回购请求状态非待处理：" + r.getStatus());
        }
        VehicleUnit unit = vehicleUnitRepository.findByVinForUpdate(r.getVin())
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_001, "车辆不存在：" + r.getVin()));
        unit.applyPrincipalPayment(amount);
        vehicleUnitRepository.save(unit);

        r.setStatus("FULFILLED");
        r.setRepurchaseAmount(amount);
        r.setFulfilledAt(LocalDateTime.now());
        repurchaseRequestRepository.save(r);

        supportRepository.writeAudit(actor, "REPURCHASE_FULFILL", "VEHICLE", r.getVin(),
                "principal=" + unit.getPrincipalBalance(), "repaid=" + amount + ",balance=" + unit.getPrincipalBalance(), null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestNo", requestNo);
        data.put("vin", r.getVin());
        data.put("repurchasedAmount", amount);
        data.put("principalBalance", unit.getPrincipalBalance());
        data.put("status", "FULFILLED");
        return data;
    }
}
