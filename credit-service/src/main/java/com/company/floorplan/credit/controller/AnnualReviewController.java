package com.company.floorplan.credit.controller;

import com.company.floorplan.application.service.AnnualReviewApplicationService;
import com.company.floorplan.common.config.AuditActor;
import com.company.floorplan.credit.dto.ApiRequests;
import com.company.floorplan.common.dto.ApiResponse;
import com.company.floorplan.common.security.RequirePermission;
import com.company.floorplan.domain.model.Permission;
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

/**
 * 额度年审接口（业务闭环 ⑪ 额度年审与冻结恢复）。路径与响应结构与原 web 模块一致。
 *
 * <p>启动年审冻结当前占用额度；通过则解冻恢复，不通过则维持冻结。</p>
 */
@RestController
@RequestMapping("/api/annual-reviews")
public class AnnualReviewController {

    private final AnnualReviewApplicationService service;

    public AnnualReviewController(AnnualReviewApplicationService service) {
        this.service = service;
    }

    @PostMapping("/initiate")
    @RequirePermission(Permission.ANNUAL_REVIEW)
    public ResponseEntity<ApiResponse<Map<String, Object>>> initiate(
            @RequestBody ApiRequests.AnnualReviewInitiateRequest req,
            HttpServletRequest request) {
        Map<String, Object> data = service.initiate(req.dealerId, req.creditLineId, req.cycle,
                AuditActor.resolve(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data, UUID.randomUUID().toString()));
    }

    @PostMapping("/{reviewNo}/complete")
    @RequirePermission(Permission.ANNUAL_REVIEW)
    public ApiResponse<Map<String, Object>> complete(
            @PathVariable String reviewNo,
            @RequestBody ApiRequests.AnnualReviewCompleteRequest req,
            HttpServletRequest request) {
        return ApiResponse.ok(service.complete(reviewNo, req.passed, req.resultNote, AuditActor.resolve(request)),
                UUID.randomUUID().toString());
    }
}
