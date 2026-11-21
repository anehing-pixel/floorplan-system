package com.company.floorplan.web.controller;

import com.company.floorplan.application.service.InsuranceApplicationService;
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

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * 车辆保险接口（业务闭环 P1：损毁 / 盗抢保障）。
 *
 * <p>要求车商为在库车辆投保、以金融公司为受益人；出险后理赔款优先冲抵垫款。
 * 本接口只负责"投保 + 理赔登记"，是否直接冲抵由风控评估。</p>
 */
@RestController
@RequestMapping("/api/insurances")
public class InsuranceController {

    private final InsuranceApplicationService service;

    public InsuranceController(InsuranceApplicationService service) {
        this.service = service;
    }

    /** 投保：绑定保单到某台车。 —— 权限：增信业务岗。 */
    @PostMapping("/bind")
    @RequirePermission(Permission.INSURANCE_REPURCHASE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> bind(
            @RequestBody ApiRequests.InsuranceBindRequest req,
            HttpServletRequest request) {
        LocalDate start = req.startDate == null || req.startDate.isBlank() ? null : LocalDate.parse(req.startDate);
        LocalDate end = req.endDate == null || req.endDate.isBlank() ? null : LocalDate.parse(req.endDate);
        Map<String, Object> data = service.bind(req.vin, req.policyNo, req.insurer,
                req.insuredAmount == null ? 0L : req.insuredAmount, start, end, AuditActor.resolve(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data, UUID.randomUUID().toString()));
    }

    /** 理赔登记：标记保单已理赔并告警风控评估冲抵垫款。 —— 权限：增信业务岗。 */
    @PostMapping("/claim")
    @RequirePermission(Permission.INSURANCE_REPURCHASE)
    public ApiResponse<Map<String, Object>> claim(
            @RequestBody ApiRequests.InsuranceClaimRequest req,
            HttpServletRequest request) {
        return ApiResponse.ok(service.claim(req.policyNo, req.claimedAmount == null ? 0L : req.claimedAmount,
                AuditActor.resolve(request)), UUID.randomUUID().toString());
    }
}
