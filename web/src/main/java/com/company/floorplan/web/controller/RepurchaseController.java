package com.company.floorplan.web.controller;

import com.company.floorplan.application.service.RepurchaseApplicationService;
import com.company.floorplan.web.config.AuditActor;
import com.company.floorplan.web.dto.ApiRequests;
import com.company.floorplan.web.dto.ApiResponse;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.web.security.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 厂家回购接口（业务闭环 P1：日本新车渠道核心信用增强 メーカー買取約束）。
 *
 * <p>区别于违约处置：回购是<b>正常合同履约</b>（厂家事先承诺买回），
 * 回款直接冲抵该台垫款本金。本接口只冲抵本金，不自动结清（利息仍走常规结清）。</p>
 */
@RestController
@RequestMapping("/api/repurchases")
public class RepurchaseController {

    private final RepurchaseApplicationService service;

    public RepurchaseController(RepurchaseApplicationService service) {
        this.service = service;
    }

    /** 申请厂家回购。 —— 权限：增信业务岗。 */
    @PostMapping("/request")
    @RequirePermission(Permission.INSURANCE_REPURCHASE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> request(
            @RequestBody ApiRequests.RepurchaseRequestDto req,
            HttpServletRequest request) {
        Map<String, Object> data = service.request(req.vin, req.reason, AuditActor.resolve(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data, UUID.randomUUID().toString()));
    }

    /** 兑现回购：厂家回款冲抵垫款本金。 —— 权限：增信业务岗。 */
    @PostMapping("/fulfill")
    @RequirePermission(Permission.INSURANCE_REPURCHASE)
    public ApiResponse<Map<String, Object>> fulfill(
            @RequestBody ApiRequests.RepurchaseFulfillRequest req,
            HttpServletRequest request) {
        return ApiResponse.ok(service.fulfill(req.requestNo, req.amount == null ? 0L : req.amount,
                AuditActor.resolve(request)), UUID.randomUUID().toString());
    }
}
