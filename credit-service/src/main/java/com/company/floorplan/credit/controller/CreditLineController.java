package com.company.floorplan.credit.controller;

import com.company.floorplan.application.service.CreditApplicationService;
import com.company.floorplan.common.config.AuditActor;
import com.company.floorplan.credit.dto.ApiRequests;
import com.company.floorplan.common.dto.ApiResponse;
import com.company.floorplan.common.security.RequirePermission;
import com.company.floorplan.domain.model.CreditLine;
import com.company.floorplan.domain.model.Permission;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 额度接口（业务闭环第 ① 环节）。路径与响应结构与原 web 模块一致。
 *
 * <p>额度的"占用"与"释放"不由本接口直接操作——它们分别由垫款与结清用例
 * 在行锁下原子完成，避免绕过业务闭环直接改额度。</p>
 */
@RestController
@RequestMapping("/api/credit-lines")
public class CreditLineController {

    private final CreditApplicationService service;

    public CreditLineController(CreditApplicationService service) {
        this.service = service;
    }

    @PostMapping
    @RequirePermission(Permission.CREDIT_MANAGE)
    public ResponseEntity<ApiResponse<CreditLine>> create(
            @RequestBody ApiRequests.CreateCreditLineRequest req,
            HttpServletRequest request) {
        if (req.approvedLimit == null) {
            throw new IllegalArgumentException("approvedLimit 不能为空");
        }
        CreditLine line = service.createCreditLine(req.dealerId, req.approvedLimit, AuditActor.resolve(request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(line, UUID.randomUUID().toString()));
    }

    @PostMapping("/{id}/freeze")
    @RequirePermission(Permission.ANNUAL_REVIEW)
    public ApiResponse<CreditLine> freeze(
            @PathVariable Long id,
            @RequestBody ApiRequests.FreezeRequest req,
            HttpServletRequest request) {
        CreditLine line = service.freeze(id, req.amount == null ? 0L : req.amount, req.reason,
                AuditActor.resolve(request));
        return ApiResponse.ok(line, UUID.randomUUID().toString());
    }

    @PostMapping("/{id}/unfreeze")
    @RequirePermission(Permission.ANNUAL_REVIEW)
    public ApiResponse<CreditLine> unfreeze(
            @PathVariable Long id,
            @RequestBody ApiRequests.FreezeRequest req,
            HttpServletRequest request) {
        CreditLine line = service.unfreeze(id, req.amount == null ? 0L : req.amount, AuditActor.resolve(request));
        return ApiResponse.ok(line, UUID.randomUUID().toString());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> summary(@PathVariable Long id) {
        return ApiResponse.ok(service.summary(id), UUID.randomUUID().toString());
    }

    /**
     * 可用额度（冻结契约 {@code CreditLineQueryClient}，契约文档 §6.4）。
     *
     * <p>2026-11-24 实现：此前该路径没有控制器，请求落到静态资源兜底，实测 500 SYS_001。
     * 返回整数日元的 {@code data}，额度不存在 → 404 {@code NOT_FOUND_001}，负数截 0。
     * 只读取额度线，不参与占用/回补——那两条都在各自用例的行锁事务里。</p>
     */
    @GetMapping("/{id}/available")
    @RequirePermission(Permission.CREDIT_MANAGE)
    public ApiResponse<Long> available(@PathVariable Long id) {
        return ApiResponse.ok(service.available(id), UUID.randomUUID().toString());
    }

    @GetMapping
    public ApiResponse<List<CreditLine>> listByDealer(@RequestParam Long dealerId) {
        return ApiResponse.ok(service.listByDealer(dealerId), UUID.randomUUID().toString());
    }
}
