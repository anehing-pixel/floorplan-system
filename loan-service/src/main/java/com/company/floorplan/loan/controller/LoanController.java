package com.company.floorplan.loan.controller;

import com.company.floorplan.common.dto.ApiResponse;
import com.company.floorplan.common.security.RequirePermission;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.loan.model.DealerRiskProfile;
import com.company.floorplan.loan.model.Loan;
import com.company.floorplan.loan.service.DpdCalculator;
import com.company.floorplan.loan.service.LoanApplicationService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 借据服务 HTTP 入口。
 *
 * <p><b>读接口不标 {@link RequirePermission}</b>：与全系统口径一致（读接口对所有已登录用户开放，
 * 风控/催收/结算岗都要看台账）；服务间调用 by-vin 只需合法 JWT，不额外要业务权限点，
 * 否则 payment agent 用只读账号会被 403。所有 /api/** 仍需 JWT（见 WebMvcConfig）。</p>
 *
 * <p><b>写端点</b>（DPD 刷新、画像重算是批量作业）标 {@code @RequirePermission(JOB_TRIGGER)}，
 * 由系统管理员/运维手工触发，对应 Permission 枚举的"批量作业手工触发"权限点。</p>
 */
@RestController
@RequestMapping("/api")
public class LoanController {

    private final LoanApplicationService service;

    public LoanController(LoanApplicationService service) {
        this.service = service;
    }

    /** 借据详情。 */
    @GetMapping("/loans/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        Loan loan = service.getLoan(id);
        if (loan == null) {
            return notFound("借据不存在: id=" + id);
        }
        return ResponseEntity.ok(ApiResponse.ok(loan, traceId()));
    }

    /**
     * 契约主端点：按 VIN 查最新未结清借据。
     * 响应结构见 docs/接口契约-V16借据.md（字段已冻结，payment agent 按此编码）。
     */
    @GetMapping("/loans/by-vin/{vin}")
    public ResponseEntity<?> getByVin(@PathVariable String vin) {
        Loan loan = service.getActiveLoanByVin(vin);
        if (loan == null) {
            return notFound("未找到该 VIN 的未结清借据: vin=" + vin);
        }
        return ResponseEntity.ok(ApiResponse.ok(loan, traceId()));
    }

    /** 查询某笔借据截至 asOf 的 DPD（不写库）。 */
    @GetMapping("/loans/{id}/dpd")
    public ResponseEntity<?> dpd(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        DpdCalculator.DpdResult result = service.dpdOf(id, asOf == null ? LocalDate.now() : asOf);
        if (result == null) {
            return notFound("借据不存在: id=" + id);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("loanId", id);
        data.put("asOf", asOf == null ? LocalDate.now().toString() : asOf.toString());
        data.put("dpd", result.dpd());
        data.put("stageCode", result.stageCode());
        data.put("stageLabel", result.stage() == null ? null : result.stage().getLabel());
        return ResponseEntity.ok(ApiResponse.ok(data, traceId()));
    }

    /** 经销商贷后画像（只读投影，不参与流程判定）。 */
    @GetMapping("/dealer-profile/risk/{dealerId}")
    public ResponseEntity<?> profile(@PathVariable Long dealerId) {
        DealerRiskProfile profile = service.getProfile(dealerId);
        if (profile == null) {
            return notFound("经销商画像不存在: dealerId=" + dealerId);
        }
        return ResponseEntity.ok(ApiResponse.ok(profile, traceId()));
    }

    /** 手工触发 DPD 批量刷新（T+1 job 的补跑入口）。 */
    @PostMapping("/loans/dpd/refresh")
    @RequirePermission(Permission.JOB_TRIGGER)
    public ApiResponse<Map<String, Object>> refreshDpd(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        LocalDate bizDate = asOf == null ? LocalDate.now() : asOf;
        int processed = service.refreshDpd(bizDate);
        return ApiResponse.ok(Map.of("asOf", bizDate.toString(), "processed", processed), traceId());
    }

    /** 手工触发经销商画像全量重算（异步任务入口，可停算不影响流程）。 */
    @PostMapping("/dealer-profile/risk/{dealerId}/recompute")
    @RequirePermission(Permission.JOB_TRIGGER)
    public ApiResponse<DealerRiskProfile> recomputeProfile(
            @PathVariable Long dealerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        LocalDate bizDate = asOf == null ? LocalDate.now() : asOf;
        return ApiResponse.ok(service.recomputeProfile(dealerId, bizDate), traceId());
    }

    private static ResponseEntity<Object> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ErrorCode.NOT_FOUND_001.getCode(), message, traceId(), null));
    }

    private static String traceId() {
        return UUID.randomUUID().toString();
    }
}
