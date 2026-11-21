package com.company.floorplan.risk.controller;

import com.company.floorplan.application.service.LienCheckApplicationService;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.common.config.AuditActor;
import com.company.floorplan.risk.dto.ApiRequests;
import com.company.floorplan.common.dto.ApiResponse;
import com.company.floorplan.common.security.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 权属核对录入（从 VehicleController 摘出）。
 *
 * <p>请求路径保持原样 {@code POST /api/vehicles/{vin}/lien-check}，调用方无需改动。
 * 权属核对是"重复融资拦截"的数据来源，属风控上下文，故随 risk-service 拆出。</p>
 */
@RestController
@RequestMapping("/api/vehicles")
public class LienCheckController {

    private final LienCheckApplicationService lienCheckService;

    public LienCheckController(LienCheckApplicationService lienCheckService) {
        this.lienCheckService = lienCheckService;
    }

    @PostMapping("/{vin}/lien-check")
    @RequirePermission(Permission.VEHICLE_VERIFY)
    public ApiResponse<Map<String, Object>> recordLienCheck(
            @PathVariable String vin,
            @RequestBody ApiRequests.LienCheckRequest req,
            HttpServletRequest request) {
        lienCheckService.record(vin, req.checkSource, req.encumbered != null && req.encumbered,
                req.note, AuditActor.resolve(request));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vin", vin);
        data.put("encumbered", req.encumbered != null && req.encumbered);
        data.put("recorded", true);
        return ApiResponse.ok(data, UUID.randomUUID().toString());
    }
}
