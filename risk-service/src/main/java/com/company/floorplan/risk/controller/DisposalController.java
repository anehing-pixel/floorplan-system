package com.company.floorplan.risk.controller;

import com.company.floorplan.application.service.DisposalApplicationService;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.common.config.AuditActor;
import com.company.floorplan.risk.dto.ApiRequests;
import com.company.floorplan.common.dto.ApiResponse;
import com.company.floorplan.common.security.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** 违约处置接口：开单 → 收车 → 变现 → 结案。路径与权限注解与原 web 版一致。 */
@RestController
@RequestMapping("/api/disposals")
public class DisposalController {

    private final DisposalApplicationService service;

    public DisposalController(DisposalApplicationService service) {
        this.service = service;
    }

    @PostMapping("/open")
    @RequirePermission(Permission.DISPOSAL_EXECUTE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> open(
            @RequestBody ApiRequests.DisposalOpenRequest req,
            HttpServletRequest request) {
        Map<String, Object> data = service.open(req.vin, req.reason, AuditActor.resolve(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data, UUID.randomUUID().toString()));
    }

    @PostMapping("/{caseNo}/repossess")
    @RequirePermission(Permission.DISPOSAL_EXECUTE)
    public ApiResponse<Map<String, Object>> repossess(
            @PathVariable String caseNo, HttpServletRequest request) {
        return ApiResponse.ok(service.repossess(caseNo, AuditActor.resolve(request)), UUID.randomUUID().toString());
    }

    @PostMapping("/{caseNo}/liquidate")
    @RequirePermission(Permission.DISPOSAL_EXECUTE)
    public ApiResponse<Map<String, Object>> liquidate(
            @PathVariable String caseNo, HttpServletRequest request) {
        return ApiResponse.ok(service.liquidate(caseNo, AuditActor.resolve(request)), UUID.randomUUID().toString());
    }

    @PostMapping("/{caseNo}/close")
    @RequirePermission(Permission.DISPOSAL_EXECUTE)
    public ApiResponse<Map<String, Object>> close(
            @PathVariable String caseNo,
            @RequestBody(required = false) ApiRequests.DisposalCloseRequest req,
            HttpServletRequest request) {
        long proceeds = req != null && req.proceeds != null ? req.proceeds : 0L;
        return ApiResponse.ok(service.close(caseNo, proceeds, AuditActor.resolve(request)),
                UUID.randomUUID().toString());
    }

    /**
     * T2-6 / B-2 撤销通道：客户在补救期内还清或和解 → CANCELLED，
     * 并按本单冻结增量配对解冻额度线（恢复该经销商正常放款能力）。
     */
    @PostMapping("/{caseNo}/cancel")
    @RequirePermission(Permission.DISPOSAL_EXECUTE)
    public ApiResponse<Map<String, Object>> cancel(
            @PathVariable String caseNo,
            @RequestBody(required = false) ApiRequests.DisposalCancelRequest req,
            HttpServletRequest request) {
        String reason = req != null && req.reason != null && !req.reason.isBlank()
                ? req.reason : "客户补救期内还清或达成和解";
        return ApiResponse.ok(service.cancel(caseNo, reason, AuditActor.resolve(request)),
                UUID.randomUUID().toString());
    }
}
