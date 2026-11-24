package com.company.floorplan.bank.controller;

import com.company.floorplan.bank.controller.dto.IgnoreRequest;
import com.company.floorplan.bank.controller.dto.ManualMatchRequest;
import com.company.floorplan.bank.model.MatchStatus;
import com.company.floorplan.bank.model.PaymentMatch;
import com.company.floorplan.bank.service.MatchRunResult;
import com.company.floorplan.bank.service.MatchRunSummary;
import com.company.floorplan.bank.service.PaymentMatchApplicationService;
import com.company.floorplan.common.config.AuditActor;
import com.company.floorplan.common.dto.ApiResponse;
import com.company.floorplan.common.security.RequirePermission;
import com.company.floorplan.domain.model.Permission;
import jakarta.servlet.http.HttpServletRequest;
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
 * 回款匹配接口（PAYMENT_MATCH / 人工工作台）。
 *
 * <p><b>操作人一律 {@link AuditActor#resolve(HttpServletRequest)}</b>——以服务端验签后的
 * 令牌身份为准，请求体不带也不接受 operator 字段，避免"代填他人名字回款/忽略"。</p>
 *
 * <p><b>权限点沿用 REPAYMENT_EXECUTE</b>（还款核销与额度回补）：设计稿建议新增
 * PAYMENT_MATCH_EXECUTE，但那要改 domain.Permission，超出本 agent 写入边界，见 HANDOFF TODO。
 * 读接口遵循全项目口径：对所有已登录用户开放，不设权限点。</p>
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentMatchController {

    private final PaymentMatchApplicationService service;

    public PaymentMatchController(PaymentMatchApplicationService service) {
        this.service = service;
    }

    /** 触发全量未匹配流水的匹配（PAYMENT_MATCH 作业手工入口）。 */
    @PostMapping("/match")
    @RequirePermission(Permission.REPAYMENT_EXECUTE)
    public ApiResponse<MatchRunSummary> matchAll(HttpServletRequest http) {
        return ApiResponse.ok(service.matchAll(AuditActor.resolve(http), traceId(http)), traceId());
    }

    /** 触发单笔流水匹配。 */
    @PostMapping("/transactions/{transactionId}/match")
    @RequirePermission(Permission.REPAYMENT_EXECUTE)
    public ApiResponse<MatchRunResult> matchOne(@PathVariable String transactionId,
                                                @RequestParam String accountRef,
                                                HttpServletRequest http) {
        return ApiResponse.ok(service.matchOne(accountRef, transactionId,
                AuditActor.resolve(http), traceId(http)), traceId());
    }

    /** 查询匹配结论（可按状态过滤）。 */
    @GetMapping("/matches")
    public ApiResponse<List<PaymentMatch>> query(@RequestParam(required = false) String status) {
        MatchStatus parsed = status == null || status.isBlank() ? null : MatchStatus.valueOf(status.toUpperCase());
        return ApiResponse.ok(service.query(parsed), traceId());
    }

    /** 挂起待人工的清单（贷后工作台直接消费）。 */
    @GetMapping("/unmatched")
    public ApiResponse<List<PaymentMatch>> unmatched() {
        return ApiResponse.ok(service.unmatched(), traceId());
    }

    /** 人工绑定 VIN → 借据，并触发释放。 */
    @PostMapping("/transactions/{transactionId}/manual-match")
    @RequirePermission(Permission.REPAYMENT_EXECUTE)
    public ApiResponse<PaymentMatch> manualMatch(@PathVariable String transactionId,
                                                 @RequestParam String accountRef,
                                                 @RequestBody ManualMatchRequest body,
                                                 HttpServletRequest http) {
        PaymentMatch match = service.manualMatch(accountRef, transactionId,
                body == null ? null : body.vin(), AuditActor.resolve(http), traceId(http));
        return ApiResponse.ok(match, traceId());
    }

    /** 人工判定为非本业务。 */
    @PostMapping("/transactions/{transactionId}/ignore")
    @RequirePermission(Permission.REPAYMENT_EXECUTE)
    public ApiResponse<PaymentMatch> ignore(@PathVariable String transactionId,
                                            @RequestParam String accountRef,
                                            @RequestBody(required = false) IgnoreRequest body,
                                            HttpServletRequest http) {
        PaymentMatch match = service.ignore(accountRef, transactionId,
                body == null ? null : body.reason(), AuditActor.resolve(http));
        return ApiResponse.ok(match, traceId());
    }

    /** 查一次匹配对应的解押与回补凭证。 */
    @GetMapping("/matches/{matchId}/release")
    public ApiResponse<Map<String, Object>> releaseOf(@PathVariable long matchId) {
        return ApiResponse.ok(Map.of(
                "lienRelease", service.releaseOf(matchId).orElse(null),
                "creditRestore", service.restoreOf(matchId).orElse(null)), traceId());
    }

    private static String traceId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 事件里的 traceId：优先沿用网关透传的 {@code X-Request-Id}，这样回款匹配事件能和
     * 这次 HTTP 请求的日志、审计串成同一条链；没有请求头时才生成。
     */
    private static String traceId(HttpServletRequest http) {
        String requestId = http == null ? null : http.getHeader("X-Request-Id");
        return requestId == null || requestId.isBlank() ? traceId() : requestId;
    }
}
