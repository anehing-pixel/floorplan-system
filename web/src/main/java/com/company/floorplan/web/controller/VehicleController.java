package com.company.floorplan.web.controller;

import com.company.floorplan.application.dto.VehicleDraft;
import com.company.floorplan.application.service.AdvanceApplicationService;
import com.company.floorplan.application.service.SalesApplicationService;
import com.company.floorplan.domain.model.SecurityType;
import com.company.floorplan.domain.model.VehicleStatus;
import com.company.floorplan.domain.model.VehicleUnit;
import com.company.floorplan.domain.repository.InterestLedgerRepository;
import com.company.floorplan.domain.repository.VehicleUnitRepository;
import com.company.floorplan.web.config.AuditActor;
import com.company.floorplan.web.dto.ApiRequests;
import com.company.floorplan.web.dto.ApiResponse;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.web.security.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 车辆台账接口（系统查询与状态推进的主入口）。
 *
 * <p>覆盖业务闭环中的多个环节：
 * <ul>
 *   <li>第 ② 环节 {@code POST /batch-ingest} —— 批量到货建账；</li>
 *   <li>第 ④ 环节 {@code POST /{vin}/security} —— 设定担保；</li>
 *   <li>第 ⑥ 环节 {@code POST /{vin}/sold} —— 标记售出，进入结算窗口；</li>
 *   <li>第 ⑦ 环节 {@code POST /{vin}/security/release} —— 解除担保；</li>
 *   <li>{@code GET /{vin}} —— 单台台账查询（含风险标记与 nextAction 提示）。</li>
 * </ul>
 *
 * <p><b>分层纪律</b>：本层只做参数校验、组装响应与调用应用服务，
 * <b>不直接修改领域对象</b>，也不承载业务规则（规则在 domain / application）。</p>
 */
@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final AdvanceApplicationService advanceService;
    private final SalesApplicationService salesService;
    private final VehicleUnitRepository vehicleUnitRepository;
    private final InterestLedgerRepository interestLedgerRepository;

    public VehicleController(AdvanceApplicationService advanceService,
                             SalesApplicationService salesService,
                             VehicleUnitRepository vehicleUnitRepository,
                             InterestLedgerRepository interestLedgerRepository) {
        this.advanceService = advanceService;
        this.salesService = salesService;
        this.vehicleUnitRepository = vehicleUnitRepository;
        this.interestLedgerRepository = interestLedgerRepository;
    }

    /** 批量到货建账（部分成功语义）。 —— 权限：采购审核岗（验车确权）。 */
    @PostMapping("/batch-ingest")
    @RequirePermission(Permission.VEHICLE_VERIFY)
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchIngest(
            @RequestBody ApiRequests.BatchIngestRequest req,
            HttpServletRequest request) {
        List<VehicleDraft> drafts = new ArrayList<>();
        if (req.vehicles != null) {
            for (ApiRequests.VehicleItem item : req.vehicles) {
                VehicleDraft d = new VehicleDraft();
                d.setVin(item.vin);
                d.setBrand(item.brand);
                d.setModel(item.model);
                d.setModelYear(item.modelYear);
                d.setMileage(item.mileage);
                d.setColor(item.color);
                d.setVehicleType(item.vehicleType);
                d.setInvoiceAmount(item.invoiceAmount == null ? 0L : item.invoiceAmount);
                d.setAdvanceRatio(item.advanceRatio == null ? new BigDecimal("0.90") : item.advanceRatio);
                drafts.add(d);
            }
        }
        Map<String, Object> data = advanceService.ingestVehicles(req.dealerId, req.creditLineId,
                req.purchaseChannel == null ? "OEM" : req.purchaseChannel, req.supplierName, drafts,
                AuditActor.resolve(request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(data, UUID.randomUUID().toString()));
    }

    /** 单台台账查询。 —— 权限：车辆台账读（2026-11-24 读矩阵）。 */
    @GetMapping("/{vin}")
    @RequirePermission(Permission.VEHICLE_READ)
    public ApiResponse<Map<String, Object>> get(@PathVariable String vin) {
        VehicleUnit unit = vehicleUnitRepository.findByVin(vin)
                .orElseThrow(() -> new com.company.floorplan.domain.exception.BusinessException(
                        com.company.floorplan.domain.exception.ErrorCode.VEHICLE_001, "车辆不存在：" + vin));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vin", unit.getVin());
        data.put("status", unit.getStatus().name());
        data.put("dealerId", unit.getDealerId());
        data.put("invoiceAmount", unit.getInvoiceAmount());
        data.put("advanceAmount", unit.getAdvanceAmount());
        data.put("advanceRatio", unit.getAdvanceRatio());
        data.put("principalBalance", unit.getPrincipalBalance());
        data.put("annualRate", unit.getAnnualRate());
        data.put("accruedInterest", interestLedgerRepository.sumInterestByVin(vin));
        data.put("agingDays", unit.getAgingDays());
        data.put("soldAt", str(unit.getSoldAt()));
        data.put("settleDueDate", str(unit.getSettleDueDate()));
        data.put("settledAt", str(unit.getSettledAt()));
        data.put("securityType", unit.getSecurityType() == null ? null : unit.getSecurityType().name());
        data.put("securityStatus", unit.getSecurityStatus() == null ? null : unit.getSecurityStatus().name());
        // 用 LinkedHashMap 而非 Map.of：Map.of 不接受 null 值（如未盘点时 lastAuditDate 为空）
        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("overdueDays", unit.getOverdueDays() == null ? 0 : unit.getOverdueDays());
        risk.put("riskLevel", unit.getRiskLevel() == null ? "LOW" : unit.getRiskLevel());
        risk.put("ootSuspected", unit.isOotSuspected());
        risk.put("lastAuditDate", str(unit.getLastAuditDate()));
        risk.put("auditMismatchCount", unit.getAuditMismatchCount() == null ? 0 : unit.getAuditMismatchCount());
        data.put("risk", risk);
        data.put("nextAction", resolveNextAction(unit));
        return ApiResponse.ok(data, UUID.randomUUID().toString());
    }

    @GetMapping
    @RequirePermission(Permission.VEHICLE_READ)
    public ApiResponse<List<VehicleUnit>> listByDealer(@RequestParam Long dealerId) {
        return ApiResponse.ok(vehicleUnitRepository.findByDealerId(dealerId), UUID.randomUUID().toString());
    }

    /** 标记售出（进入结算窗口）——"售出即还"机制的起点。 —— 权限：结算岗。 */
    @PostMapping("/{vin}/sold")
    @RequirePermission(Permission.VEHICLE_SOLD)
    public ApiResponse<Map<String, Object>> markSold(
            @PathVariable String vin,
            @RequestBody(required = false) ApiRequests.SoldRequest req,
            HttpServletRequest request) {
        LocalDate soldAt = (req == null || req.soldAt == null || req.soldAt.isBlank())
                ? LocalDate.now() : LocalDate.parse(req.soldAt);
        Map<String, Object> data = salesService.markSold(vin, soldAt, AuditActor.resolve(request));
        return ApiResponse.ok(data, UUID.randomUUID().toString());
    }

    /**
     * T2-8（M11）：终端客户支付确认（第 ⑦ 环节"客户付款 → 金融确认 → 出库指令"）。
     * —— 权限：还款核销岗。
     */
    @PostMapping("/{vin}/customer-payment/confirm")
    @RequirePermission(Permission.REPAYMENT_EXECUTE)
    public ApiResponse<Map<String, Object>> confirmCustomerPayment(
            @PathVariable String vin,
            @RequestBody(required = false) ApiRequests.CustomerPaymentConfirmRequest req,
            HttpServletRequest request) {
        long amount = (req == null || req.amount == null) ? 0L : req.amount;
        String payer = req == null ? null : req.payerName;
        Map<String, Object> data = salesService.confirmCustomerPayment(vin, amount, payer,
                AuditActor.resolve(request));
        return ApiResponse.ok(data, UUID.randomUUID().toString());
    }

    /** 设定担保（第 ④ 环节；担保路径待确认）。 —— 权限：担保管理岗。 */
    @PostMapping("/{vin}/security")
    @RequirePermission(Permission.SECURITY_MAINTAIN)
    public ApiResponse<Map<String, Object>> setSecurity(
            @PathVariable String vin,
            @RequestBody ApiRequests.SecurityRequest req,
            HttpServletRequest request) {
        SecurityType type = req.securityType == null ? SecurityType.NONE : SecurityType.valueOf(req.securityType);
        Map<String, Object> data = salesService.setSecurity(vin, type, req.regNo, req.ownerName,
                AuditActor.resolve(request));
        return ApiResponse.ok(data, UUID.randomUUID().toString());
    }

    /** 解除担保（第 ⑦ 环节）。 —— 权限：担保管理岗。 */
    @PostMapping("/{vin}/security/release")
    @RequirePermission(Permission.SECURITY_MAINTAIN)
    public ApiResponse<Map<String, Object>> releaseSecurity(
            @PathVariable String vin,
            HttpServletRequest request) {
        Map<String, Object> data = salesService.releaseSecurity(vin, AuditActor.resolve(request));
        return ApiResponse.ok(data, UUID.randomUUID().toString());
    }

    /**
     * 权属 / 抵押 / 查封核对录入（{@code POST /api/vehicles/{vin}/lien-check}）已迁移至
     * risk-service。网关按该精确路径路由到风控服务；本Controller不再承载该端点。
     */

    private String resolveNextAction(VehicleUnit unit) {
        if (unit.isOotSuspected()) {
            return "OOT_REVIEW";
        }
        if (unit.getStatus() == VehicleStatus.SOLD_PENDING
                && unit.getSettleDueDate() != null
                && unit.getSettleDueDate().isBefore(LocalDate.now())) {
            return "SETTLE_OVERDUE";
        }
        if (unit.getAgingDays() != null && unit.getAgingDays() >= 90) {
            return "AGING_REDUCTION";
        }
        if (unit.getLastAuditDate() == null
                || unit.getLastAuditDate().isBefore(LocalDate.now().minusDays(30))) {
            return "AUDIT_DUE";
        }
        return "NONE";
    }

    private String str(LocalDate d) {
        return d == null ? null : d.toString();
    }
}
