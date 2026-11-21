package com.company.floorplan.web.controller;

import com.company.floorplan.application.service.ExtensionApplicationService;
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
 * 展期续贷接口（业务闭环 P1：延长结算窗口）。
 *
 * <p>车商零售成交后，因客户贷款审批、过户等客观原因无法在结算窗口内结清，
 * 可申请展期，将 {@code settle_due_date} 顺延若干天；展期即视为新的到期日，
 * 并清除此前超窗口产生的 OOT/逾期标记（属合法顺延）。</p>
 */
@RestController
@RequestMapping("/api/extensions")
public class ExtensionController {

    private final ExtensionApplicationService service;

    public ExtensionController(ExtensionApplicationService service) {
        this.service = service;
    }

    /** 申请展期。 —— 权限：增信业务岗。 */
    @PostMapping("/apply")
    @RequirePermission(Permission.INSURANCE_REPURCHASE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> apply(
            @RequestBody ApiRequests.ExtensionApplyRequest req,
            HttpServletRequest request) {
        Map<String, Object> data = service.apply(req.vin, req.extDays == null ? 0 : req.extDays, req.reason,
                AuditActor.resolve(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data, UUID.randomUUID().toString()));
    }
}
