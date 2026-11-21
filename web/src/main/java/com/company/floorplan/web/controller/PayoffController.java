package com.company.floorplan.web.controller;

import com.company.floorplan.application.service.PayoffApplicationService;
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
 * 结清证明接口（业务闭环 P1：出具给车商的书面凭证）。
 *
 * <p>整台结清（还款核销 + 解除担保 + 额度回补）后，金融公司出具结清证明，
 * 载明本金、利息、合计与结清日，供车商办理解押、过户。仅整台结清车辆可出具。</p>
 */
@RestController
@RequestMapping("/api/payoffs")
public class PayoffController {

    private final PayoffApplicationService service;

    public PayoffController(PayoffApplicationService service) {
        this.service = service;
    }

    /** 出具结清证明。 —— 权限：结算岗。 */
    @PostMapping("/issue")
    @RequirePermission(Permission.PAYOFF_ISSUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> issue(
            @RequestBody ApiRequests.PayoffRequest req,
            HttpServletRequest request) {
        Map<String, Object> data = service.issue(req.vin, AuditActor.resolve(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data, UUID.randomUUID().toString()));
    }
}
