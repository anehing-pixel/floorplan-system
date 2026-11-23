package com.company.floorplan.web.controller;

import com.company.floorplan.application.dto.RecordValuationCmd;
import com.company.floorplan.application.service.UsedValuationApplicationService;
import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.web.config.AuditActor;
import com.company.floorplan.web.config.RequestLoggingFilter;
import com.company.floorplan.web.dto.ApiResponse;
import com.company.floorplan.web.dto.UsedRequests;
import com.company.floorplan.web.security.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 多源审慎估值内部端点（USED R3，D07 §7.2 估值那 4 行）。
 *
 * <p><b>落点是 core 兜底，这是定案不是遗漏</b>：D07 §7.0 表明确写了
 * {@code /api/valuations/**} 与 {@code /api/ltv-policies/**} 不命中任何具名网关路由，
 * 由 core 兜底接走（属主本就是 core）。因此<b>不需要</b>改网关 yml（AGENTS §7.1 禁区），
 * 但冒烟断言必须从 18080 打进去——否则「core 兜底把请求接走」和
 * 「这个服务真的实现了该端点」在冒烟里长得一模一样，正是 D07 §7.0 变更规矩第 2 条。</p>
 *
 * <p><b>路径用数字 id 而不是 valuationKey</b>：D07 §7.2 的 {@code /{id}/override}、
 * {@code /{id}/expire} 写的是 id，照抄。两个标识符都出现在响应体里，
 * 客户端要用键寻址时先读一次 {@code GET /active}。</p>
 */
@RestController
@RequestMapping("/api/valuations")
public class UsedValuationController {

    private final UsedValuationApplicationService valuationService;

    public UsedValuationController(UsedValuationApplicationService valuationService) {
        this.valuationService = valuationService;
    }

    /**
     * 估值录入（UC-R3-1）。
     *
     * <p>第一次落库回 201；带同一个 {@code valuationKey} 重推回 200 且
     * {@code data.idempotent=true}，库里行数不变（冒烟两条断言分别盯这两件事）。
     * 不合格的形状（无源／单源非人工／降级源直录／覆盖缺第二人）在写之前就被拒，
     * 库里不会留下「有一行但不生效」的估值。</p>
     */
    @PostMapping
    @RequirePermission(Permission.VALUATION_RECORD)
    public ResponseEntity<ApiResponse<Map<String, Object>>> record(
            @RequestBody UsedRequests.ValuationRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemHeader,
            HttpServletRequest request) {
        RecordValuationCmd cmd = toCmd(req);
        Map<String, Object> data = withIdempotencyHeader(
                valuationService.record(cmd, AuditActor.resolve(request), trace(request)), idemHeader);
        return statusOf(data, HttpStatus.CREATED).body(ApiResponse.ok(data, trace(request)));
    }

    /**
     * 当前放款基准（UC-R3-5 的读口，与闸门同源）。
     *
     * <p>没有 ACTIVE 基准回 404 {@code NOT_FOUND_001}，不返回空对象：
     * 空对象会被读成「有一个估值但字段没填」，而正确动作是去做估值。</p>
     */
    @GetMapping("/active")
    @RequirePermission(Permission.VALUATION_READ)
    public ResponseEntity<ApiResponse<Map<String, Object>>> active(
            @RequestParam("vin") String vin, HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(valuationService.activeFor(vin), trace(request)));
    }

    /** 人工覆盖（UC-R3-3）：权限点 {@code VALUATION_OVERRIDE} 与录入点分属两岗（D10 §4 D-4）。 */
    @PostMapping("/{id}/override")
    @RequirePermission(Permission.VALUATION_OVERRIDE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> override(
            @PathVariable("id") Long id,
            @RequestBody UsedRequests.ValuationOverrideRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemHeader,
            HttpServletRequest request) {
        requireId(id);
        Map<String, Object> data = withIdempotencyHeader(
                valuationService.override(id, req.manualValue, req.reason, req.coSignedBy,
                        AuditActor.resolve(request), trace(request)), idemHeader);
        return ResponseEntity.ok(ApiResponse.ok(data, trace(request)));
    }

    /**
     * 人工置失效（UC-R3-4）。
     *
     * <p>权限点用 {@code VALUATION_RECORD} 而不是覆盖点：D07 §7.2 就这么点的名，
     * 语义是「谁有资格决定一条基准的存在与否」，与「谁能抬高数值」（覆盖）是两件事。
     * 置失效<b>不需要</b>第二人——它只会让可借额变小，不构成红线上的放宽。</p>
     */
    @PostMapping("/{id}/expire")
    @RequirePermission(Permission.VALUATION_RECORD)
    public ResponseEntity<ApiResponse<Map<String, Object>>> expire(
            @PathVariable("id") Long id,
            @RequestBody(required = false) UsedRequests.ValuationExpireRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemHeader,
            HttpServletRequest request) {
        requireId(id);
        Map<String, Object> data = withIdempotencyHeader(
                valuationService.expire(id, req == null ? null : req.reason,
                        AuditActor.resolve(request), trace(request)), idemHeader);
        return ResponseEntity.ok(ApiResponse.ok(data, trace(request)));
    }

    /** 请求体 → 命令：DTO 是可变 POJO（Jackson 友好），命令是 record（用例内部不可变）。 */
    private static RecordValuationCmd toCmd(UsedRequests.ValuationRequest req) {
        if (req == null) {
            return new RecordValuationCmd(null, null, null, null, null, null, null, null,
                    null, null, null, null, null);
        }
        List<RecordValuationCmd.SourceLine> lines = new ArrayList<>();
        if (req.sources != null) {
            for (UsedRequests.ValuationRequest.SourceLine s : req.sources) {
                if (s == null) {
                    lines.add(null);
                    continue;
                }
                lines.add(new RecordValuationCmd.SourceLine(s.source, s.value, s.ref, s.provider,
                        s.asOf, s.weight, s.rank));
            }
        }
        return new RecordValuationCmd(req.vin, req.purpose, lines, req.valuationDate, req.validUntil,
                req.coSignedBy, req.auctionLotId, req.marketSnapshotId, req.appraisalProvider,
                req.appraisalNo, req.reportPath, req.reportDigest, req.valuationKey);
    }

    /**
     * 路径上的 id 必须可解析成正数。
     *
     * <p>{@code @PathVariable Long} 交给 Spring 转换，非数字会在绑定阶段抛
     * {@code MethodArgumentTypeMismatchException}，由 {@code GlobalExceptionHandler}
     * 统一收口；这里只补「不存在 id≤0 的估值行」这一条业务判断。</p>
     */
    private static void requireId(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.VALID_001, "路径上的估值 id 必须是正整数");
        }
    }

    private static String trace(HttpServletRequest request) {
        Object existing = request.getAttribute(RequestLoggingFilter.ATTR_TRACE_ID);
        return existing == null ? UUID.randomUUID().toString() : String.valueOf(existing);
    }

    private static Map<String, Object> withIdempotencyHeader(Map<String, Object> data, String idemHeader) {
        data.put("idempotencyKeyHeader", idemHeader);
        return data;
    }

    /** 新建返 201，命中幂等返 200——冒烟据此区分「第一次」与「重推」，不必翻响应体。 */
    private static ResponseEntity.BodyBuilder statusOf(Map<String, Object> data, HttpStatus created) {
        return ResponseEntity.status(Boolean.TRUE.equals(data.get("idempotent")) ? HttpStatus.OK : created);
    }
}
