package com.company.floorplan.application.service;

import com.company.floorplan.domain.model.VehicleStatus;
import com.company.floorplan.domain.model.VehicleUnit;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.domain.repository.VehicleUnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实地盘点用例（风控的核心执行手段）。
 *
 * <p><b>为什么重要</b>：库存融资的全部风控逻辑，本质上都是在和
 * "车商卖了车却不上报"（脱信销售 OOT）对抗。实地盘点是最直接的验证手段：
 * 派人到车商场地逐台扫描 VIN，核对"台账上有融资的车"是否真的还在场。</p>
 *
 * <p><b>差异分类</b>：
 * <ul>
 *   <li><b>账有实无（missing）</b>：高风险——车可能已被私自出售，
 *       标记 ootSuspected 并产生 HIGH 级告警；</li>
 *   <li><b>账无实有（unexpected）</b>：现场有台账外车辆，产生 MEDIUM 级告警，
 *       需核实是否为未纳入融资的库存。</li>
 * </ul>
 *
 * <p><b>留痕</b>：每次盘点更新车辆的最后盘点日与差异次数，
 * 并写入审计事件，供风控复核与审计追溯。</p>
 */
@Service
public class InventoryAuditApplicationService {

    private final VehicleUnitRepository vehicleUnitRepository;
    private final SupportRepository supportRepository;

    /** 担保物台账服务：盘点痕迹与证据链。 */
    private final CollateralLedgerApplicationService collateralService;

    public InventoryAuditApplicationService(VehicleUnitRepository vehicleUnitRepository,
                                            SupportRepository supportRepository, CollateralLedgerApplicationService collateralService) {
        this.vehicleUnitRepository = vehicleUnitRepository;
        this.supportRepository = supportRepository;
        this.collateralService = collateralService;
    }

    @Transactional
    public Map<String, Object> submitAudit(Long dealerId, LocalDate auditDate, List<String> scannedVins, String actor) {
        Set<String> scanned = new LinkedHashSet<>(scannedVins == null ? List.of() : scannedVins);

        List<VehicleUnit> ledgerUnits = vehicleUnitRepository.findByDealerId(dealerId).stream()
                .filter(u -> u.getStatus() == VehicleStatus.IN_STOCK)
                .toList();

        List<String> missing = new ArrayList<>();   // 台账有、现场无（高风险：可能已脱信销售）
        List<String> unexpected = new ArrayList<>(); // 现场有、台账无
        int matched = 0;

        Set<String> ledgerVins = new LinkedHashSet<>();
        for (VehicleUnit unit : ledgerUnits) {
            ledgerVins.add(unit.getVin());
            if (scanned.contains(unit.getVin())) {
                matched++;
                unit.setLastAuditDate(auditDate);
            } else {
                missing.add(unit.getVin());
                int mismatch = unit.getAuditMismatchCount() == null ? 0 : unit.getAuditMismatchCount();
                unit.setAuditMismatchCount(mismatch + 1);
                unit.setOotSuspected(true);
                unit.setLastAuditDate(auditDate);
            }
            vehicleUnitRepository.save(unit);
            // 担保物台账：实地盘点是日本渠道的主要监管手段，盘点痕迹必须落在担保物台账上
            // （否则"这台车最近盘点过没有"在担保维度查不到）
            collateralService.recordAudit(unit.getVin(), auditDate,
                    !scanned.contains(unit.getVin()), actor);
        }
        for (String vin : scanned) {
            if (!ledgerVins.contains(vin)) {
                unexpected.add(vin);
            }
        }

        int alerts = 0;
        if (!missing.isEmpty()) {
            supportRepository.createAlert(dealerId, null, "AUDIT_MISMATCH", "HIGH",
                    "盘点缺失 " + missing.size() + " 台：" + String.join(",", missing));
            alerts++;
        }
        if (!unexpected.isEmpty()) {
            supportRepository.createAlert(dealerId, null, "AUDIT_UNEXPECTED", "MEDIUM",
                    "现场发现台账外车辆 " + unexpected.size() + " 台：" + String.join(",", unexpected));
            alerts++;
        }

        supportRepository.writeAudit(actor, "INVENTORY_AUDIT", "DEALER", String.valueOf(dealerId),
                null, "matched=" + matched + ",missing=" + missing.size() + ",unexpected=" + unexpected.size(), null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dealerId", dealerId);
        data.put("auditDate", auditDate.toString());
        data.put("matched", matched);
        data.put("missingVins", missing);
        data.put("unexpectedVins", unexpected);
        data.put("alertsCreated", alerts);
        return data;
    }
}
