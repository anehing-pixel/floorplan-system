package com.company.floorplan.risk.controller;

import com.company.floorplan.application.service.InventoryAuditApplicationService;
import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.Alert;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.common.config.AuditActor;
import com.company.floorplan.risk.dto.ApiRequests;
import com.company.floorplan.common.dto.ApiResponse;
import com.company.floorplan.common.security.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 风控：实地盘点与风险预警。路径与权限注解与原 web 版一致。 */
@RestController
@RequestMapping("/api")
public class RiskController {

    private final InventoryAuditApplicationService auditService;
    private final SupportRepository supportRepository;

    public RiskController(InventoryAuditApplicationService auditService, SupportRepository supportRepository) {
        this.auditService = auditService;
        this.supportRepository = supportRepository;
    }

    @PostMapping("/inventory/audit")
    @RequirePermission(Permission.INVENTORY_AUDIT)
    public ApiResponse<Map<String, Object>> submitAudit(
            @RequestBody ApiRequests.AuditRequest req,
            HttpServletRequest request) {
        LocalDate auditDate = (req.auditDate == null || req.auditDate.isBlank())
                ? LocalDate.now() : LocalDate.parse(req.auditDate);
        Map<String, Object> data = auditService.submitAudit(req.dealerId, auditDate, req.scannedVins,
                AuditActor.resolve(request));
        return ApiResponse.ok(data, UUID.randomUUID().toString());
    }

    @GetMapping("/alerts")
    public ApiResponse<List<Alert>> alerts(@RequestParam(required = false) String type,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(supportRepository.findAlerts(type, status, limit), UUID.randomUUID().toString());
    }

    /** 处置风险告警：降级/升级/关闭都要留痕，避免告警池只积不看。 */
    @PostMapping("/alerts/{alertId}/handle")
    @RequirePermission(Permission.RISK_HANDLE)
    public ApiResponse<Map<String, Object>> handleAlert(@PathVariable Long alertId,
                                                         @RequestBody ApiRequests.AlertHandleRequest body,
                                                         HttpServletRequest request) {
        int n = supportRepository.handleAlert(alertId, body.status,
                AuditActor.resolve(request), body.note);
        if (n == 0) {
            throw new BusinessException(ErrorCode.VALID_001,
                    Map.of("alertId", alertId, "reason", "ALERT_NOT_HANDLEABLE"));
        }
        supportRepository.writeAudit(AuditActor.resolve(request), AuditActor.resolveRole(request),
                "HANDLE_ALERT", "ALERT", String.valueOf(alertId),
                null, "status=" + body.status + " note=" + (body.note == null ? "" : body.note),
                String.valueOf(request.getAttribute("traceId")));
        return ApiResponse.ok(Map.of("alertId", alertId, "status", body.status), UUID.randomUUID().toString());
    }
}
