package com.company.floorplan.risk.controller;

import com.company.floorplan.application.service.CollateralLedgerApplicationService;
import com.company.floorplan.domain.model.PerfectionMethod;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.domain.model.SecurityType;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 担保物台账接口。路径与权限注解与原 web 版一致。 */
@RestController
@RequestMapping("/api/collateral")
public class CollateralController {

    private final CollateralLedgerApplicationService service;

    public CollateralController(CollateralLedgerApplicationService service) {
        this.service = service;
    }

    @GetMapping("/ledger")
    @RequirePermission(Permission.COLLATERAL_READ)
    public ApiResponse<List<Map<String, Object>>> ledger(
            @RequestParam(required = false) Long dealerId) {
        return ApiResponse.ok(service.listLedger(dealerId), UUID.randomUUID().toString());
    }

    @GetMapping("/ledger/{vin}")
    @RequirePermission(Permission.COLLATERAL_READ)
    public ApiResponse<Map<String, Object>> detail(@PathVariable String vin) {
        return ApiResponse.ok(service.detail(vin), UUID.randomUUID().toString());
    }

    @GetMapping("/ledger/sold-unpaid")
    @RequirePermission(Permission.COLLATERAL_READ)
    public ApiResponse<List<Map<String, Object>>> soldUnpaid() {
        return ApiResponse.ok(service.listSoldUnpaid(), UUID.randomUUID().toString());
    }

    @GetMapping("/registration-batches/expiring")
    @RequirePermission(Permission.COLLATERAL_READ)
    public ApiResponse<List<Map<String, Object>>> expiring() {
        return ApiResponse.ok(service.listExpiringRegistrations(), UUID.randomUUID().toString());
    }

    @GetMapping("/registration-batches/cancellation-pending")
    @RequirePermission(Permission.COLLATERAL_READ)
    public ApiResponse<List<Map<String, Object>>> cancellationPending() {
        return ApiResponse.ok(service.listCancellationPending(), UUID.randomUUID().toString());
    }

    @GetMapping("/evidence-gaps")
    @RequirePermission(Permission.COLLATERAL_READ)
    public ApiResponse<List<Map<String, Object>>> evidenceGaps() {
        return ApiResponse.ok(service.listEvidenceGaps(), UUID.randomUUID().toString());
    }

    @PostMapping("/registration-batches")
    @RequirePermission(Permission.COLLATERAL_MAINTAIN)
    public ApiResponse<Map<String, Object>> registerBatch(
            @RequestBody ApiRequests.CollateralBatchRegistrationRequest body,
            HttpServletRequest request) {
        SecurityType type = body.registrationType == null ? SecurityType.ASSIGNMENT
                : SecurityType.valueOf(body.registrationType);
        PerfectionMethod method = body.perfectionMethod == null ? PerfectionMethod.REGISTRATION
                : PerfectionMethod.valueOf(body.perfectionMethod);
        LocalDateTime registeredAt = body.registeredAt == null ? LocalDateTime.now()
                : LocalDateTime.parse(body.registeredAt);
        LocalDateTime expiresAt = body.expiresAt == null ? null : LocalDateTime.parse(body.expiresAt);
        LocalDateTime signedAt = body.contractSignedAt == null ? null
                : LocalDateTime.parse(body.contractSignedAt);
        Map<String, Object> data = service.registerCollateralBatch(body.vins, body.registrationNo,
                type, method, body.registryOffice, registeredAt, expiresAt,
                body.contractAttachmentId, body.contractNo, signedAt,
                body.certificateAttachmentId, body.certificateNo, AuditActor.resolve(request));
        return ApiResponse.ok(data, UUID.randomUUID().toString());
    }

    @GetMapping("/registration-batches")
    @RequirePermission(Permission.COLLATERAL_READ)
    public ApiResponse<List<Map<String, Object>>> batches() {
        return ApiResponse.ok(service.listBatches(), UUID.randomUUID().toString());
    }

    @PostMapping("/registration-batches/{batchId}/cancellation/request")
    @RequirePermission(Permission.COLLATERAL_MAINTAIN)
    public ApiResponse<Map<String, Object>> requestCancellation(
            @PathVariable Long batchId,
            @RequestBody(required = false) ApiRequests.CancellationRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(
                service.requestCancellation(batchId, body == null ? null : body.note,
                        AuditActor.resolve(request)),
                UUID.randomUUID().toString());
    }

    @PostMapping("/registration-batches/{batchId}/cancellation/apply")
    @RequirePermission(Permission.COLLATERAL_MAINTAIN)
    public ApiResponse<Map<String, Object>> applyCancellation(
            @PathVariable Long batchId, HttpServletRequest request) {
        return ApiResponse.ok(service.applyCancellation(batchId, AuditActor.resolve(request)),
                UUID.randomUUID().toString());
    }

    @PostMapping("/registration-batches/{batchId}/cancellation/complete")
    @RequirePermission(Permission.COLLATERAL_MAINTAIN)
    public ApiResponse<Map<String, Object>> completeCancellation(
            @PathVariable Long batchId,
            @RequestBody ApiRequests.CancellationCompleteRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(service.completeCancellation(batchId, body.cancellationNo,
                body.certificateAttachmentId, AuditActor.resolve(request)),
                UUID.randomUUID().toString());
    }

    @PostMapping("/ledger/{vin}/required-account")
    @RequirePermission(Permission.COLLATERAL_RELEASE_RULE)
    public ApiResponse<Map<String, Object>> requireAccount(
            @PathVariable String vin,
            @RequestBody ApiRequests.RequiredAccountRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(service.requireAccount(vin, body.accountRef, AuditActor.resolve(request)),
                UUID.randomUUID().toString());
    }
}
