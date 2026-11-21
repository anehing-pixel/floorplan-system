package com.company.floorplan.credit.controller;

import com.company.floorplan.application.service.DealerApplicationService;
import com.company.floorplan.common.config.AuditActor;
import com.company.floorplan.credit.dto.ApiRequests;
import com.company.floorplan.common.dto.ApiResponse;
import com.company.floorplan.common.security.RequirePermission;
import com.company.floorplan.domain.model.Dealer;
import com.company.floorplan.domain.model.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 经销商接口（业务闭环第 ① 环节的载体）。路径与响应结构与原 web 模块一致。
 *
 * <p>读接口所有已登录用户可访问；写接口按权限点分级（DEALER_MAINTAIN / RISK_GRADE_ADJUST）。</p>
 */
@RestController
@RequestMapping("/api/dealers")
public class DealerController {

    private final DealerApplicationService service;

    public DealerController(DealerApplicationService service) {
        this.service = service;
    }

    @PostMapping
    @RequirePermission(Permission.DEALER_MAINTAIN)
    public ResponseEntity<ApiResponse<Dealer>> create(
            @RequestBody ApiRequests.CreateDealerRequest req,
            HttpServletRequest request) {
        Dealer dealer = service.createDealer(req.dealerCode, req.name, req.riskGrade, req.businessYears,
                req.contactPerson, req.contactPhone, req.address, req.remark,
                AuditActor.resolve(request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(dealer, UUID.randomUUID().toString()));
    }

    @GetMapping("/{id}")
    public ApiResponse<Dealer> get(@PathVariable Long id) {
        return ApiResponse.ok(service.getDealer(id), UUID.randomUUID().toString());
    }

    @GetMapping
    public ApiResponse<List<Dealer>> list() {
        return ApiResponse.ok(service.listDealers(), UUID.randomUUID().toString());
    }

    /** 经销商名下车辆清单（聚合视图，一次返回档案 + 车辆 + 汇总统计）。 */
    @GetMapping("/{id}/vehicles")
    public ApiResponse<Map<String, Object>> vehicles(@PathVariable Long id) {
        return ApiResponse.ok(service.dealerVehicleOverview(id), UUID.randomUUID().toString());
    }

    @PutMapping("/{id}")
    @RequirePermission(Permission.DEALER_MAINTAIN)
    public ApiResponse<Dealer> update(
            @PathVariable Long id,
            @RequestBody ApiRequests.UpdateDealerRequest req,
            HttpServletRequest request) {
        Dealer dealer = service.updateDealer(id, req.dealerCode, req.name, req.riskGrade,
                req.businessYears, req.status, req.contactPerson, req.contactPhone,
                req.address, req.remark, AuditActor.resolve(request));
        return ApiResponse.ok(dealer, UUID.randomUUID().toString());
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permission.DEALER_MAINTAIN)
    public ApiResponse<Map<String, Object>> delete(
            @PathVariable Long id,
            HttpServletRequest request) {
        service.deleteDealer(id, AuditActor.resolve(request));
        return ApiResponse.ok(Map.of("deletedId", id), UUID.randomUUID().toString());
    }

    @PutMapping("/{id}/risk-grade")
    @RequirePermission(Permission.RISK_GRADE_ADJUST)
    public ApiResponse<Dealer> updateRiskGrade(
            @PathVariable Long id,
            @RequestBody ApiRequests.DealerGradeRequest req,
            HttpServletRequest request) {
        Dealer dealer = service.updateRiskGrade(id, req.riskGrade, AuditActor.resolve(request));
        return ApiResponse.ok(dealer, UUID.randomUUID().toString());
    }
}
