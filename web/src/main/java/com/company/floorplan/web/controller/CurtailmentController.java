package com.company.floorplan.web.controller;

import com.company.floorplan.application.service.CurtailmentApplicationService;
import com.company.floorplan.web.config.AuditActor;
import com.company.floorplan.web.dto.ApiRequests;
import com.company.floorplan.web.dto.ApiResponse;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.web.security.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 库龄递减还款接口（业务闭环 P0-①）。
 *
 * <p>每期实际核销由本接口触发；计划由日终作业 {@code BatchApplicationService.runAgingReduction}
 * 生成（规则版本化）。扣本金后<b>利息基数随之下降</b>，且<b>不回补额度</b>（区别于整台结清）。</p>
 */
@RestController
@RequestMapping("/api/curtailments")
public class CurtailmentController {

    private final CurtailmentApplicationService service;

    public CurtailmentController(CurtailmentApplicationService service) {
        this.service = service;
    }

    /** 偿付一期库龄递减还款。 —— 权限：催收岗。 */
    @PostMapping("/{vin}/pay")
    @RequirePermission(Permission.CURTAILMENT_EXECUTE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> pay(
            @PathVariable String vin,
            @RequestBody(required = false) ApiRequests.CurtailmentRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        String key = idempotencyKey != null ? idempotencyKey
                : (req != null && req.idempotencyKey != null ? req.idempotencyKey : UUID.randomUUID().toString());
        Map<String, Object> data = service.payPeriod(vin, key, AuditActor.resolve(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data, UUID.randomUUID().toString()));
    }
}
