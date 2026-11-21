package com.company.floorplan.release.controller;

import com.company.floorplan.common.config.AuditActor;
import com.company.floorplan.common.dto.ApiResponse;
import com.company.floorplan.common.security.RequirePermission;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.release.controller.dto.CreateReleaseRequest;
import com.company.floorplan.release.controller.dto.ReleaseActionRequest;
import com.company.floorplan.release.model.ReleaseOrder;
import com.company.floorplan.release.service.CreateReleaseCommand;
import com.company.floorplan.release.service.ReleaseApplicationService;
import com.company.floorplan.release.service.ReleaseDetail;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 出库指令接口（T2-9 五个端点）。
 *
 * <p>操作人一律走 {@link AuditActor#resolve}——以服务端验签后的令牌身份为准，
 * 不接受请求体自称的操作人，避免"代填他人名字放车"。</p>
 *
 * <p><b>权限点沿用既有 {@code SECURITY_MAINTAIN}</b>（担保设定与解除）：
 * 设计稿 §6 建议新增 RELEASE_ORDER_ISSUE，但新增权限点要改 common/domain，
 * 超出本 agent 的写入边界，见 HANDOFF.md 的 TODO。</p>
 */
@RestController
@RequestMapping("/api/releases")
public class ReleaseController {

    private final ReleaseApplicationService service;

    public ReleaseController(ReleaseApplicationService service) {
        this.service = service;
    }

    /** 创建出库指令（含下发，落单后推进到 SENT）。 */
    @PostMapping
    @RequirePermission(Permission.SECURITY_MAINTAIN)
    public ApiResponse<Map<String, Object>> create(@RequestBody CreateReleaseRequest req,
                                                   HttpServletRequest http) {
        String operator = AuditActor.resolve(http);
        ReleaseOrder order = service.create(new CreateReleaseCommand(
                req.redemptionId(), req.dealerId(), req.vin(), req.certificateNo(),
                req.custodianId(), req.releaseItems(), req.validUntil(), operator, req.deliveryChannel()));
        return ApiResponse.ok(Map.of("order", order), traceId());
    }

    /** 回执确认：SENT → CONFIRMED。 */
    @PostMapping("/{id}/confirm")
    @RequirePermission(Permission.SECURITY_MAINTAIN)
    public ApiResponse<Map<String, Object>> confirm(@PathVariable long id,
                                                    @RequestBody(required = false) ReleaseActionRequest body,
                                                    HttpServletRequest http) {
        ReleaseOrder order = service.confirm(id, AuditActor.resolve(http), reasonOf(body));
        return ApiResponse.ok(Map.of("order", order), traceId());
    }

    /** 放车：CONFIRMED → RELEASED。 */
    @PostMapping("/{id}/release")
    @RequirePermission(Permission.SECURITY_MAINTAIN)
    public ApiResponse<Map<String, Object>> release(@PathVariable long id,
                                                    @RequestBody(required = false) ReleaseActionRequest body,
                                                    HttpServletRequest http) {
        ReleaseOrder order = service.release(id, AuditActor.resolve(http), reasonOf(body));
        return ApiResponse.ok(Map.of("order", order), traceId());
    }

    /** 解抵：RELEASED → LIEN_DISCHARGED。 */
    @PostMapping("/{id}/discharge-lien")
    @RequirePermission(Permission.SECURITY_MAINTAIN)
    public ApiResponse<Map<String, Object>> dischargeLien(@PathVariable long id,
                                                          @RequestBody(required = false) ReleaseActionRequest body,
                                                          HttpServletRequest http) {
        ReleaseOrder order = service.dischargeLien(id, AuditActor.resolve(http), reasonOf(body));
        return ApiResponse.ok(Map.of("order", order), traceId());
    }

    /** 查指令 + 释放项明细 + 状态历史。 */
    @GetMapping("/{id}")
    public ApiResponse<ReleaseDetail> get(@PathVariable long id) {
        return ApiResponse.ok(service.get(id), traceId());
    }

    private static String reasonOf(ReleaseActionRequest body) {
        return body == null ? null : body.reason();
    }

    private static String traceId() {
        return UUID.randomUUID().toString();
    }
}
