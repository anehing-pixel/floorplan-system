package com.company.floorplan.web.controller;

import com.company.floorplan.application.dto.CreateLtvPolicyCmd;
import com.company.floorplan.application.dto.LtvBandDraft;
import com.company.floorplan.application.service.LtvPolicyApplicationService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 分档 LTV 政策内部端点（USED R3，D07 §7.2 政策那 5 行）。
 *
 * <p><b>三个写端点是三个权限位，不是「一个管理页」</b>：
 * {@code LTV_POLICY_MANAGE}（起草、配带）与 {@code LTV_POLICY_APPROVE}（生效）
 * 分属两岗（D10 §4 D-5），用例层还会把「同一个人」再拦一次（AUTH_003 + 独立留痕）。
 * 权限点在角色上就不并列，换人本来就不该靠前端隐藏按钮保证。</p>
 *
 * <p>落点同 {@link UsedValuationController}：core 兜底路由，网关不加具名路由，
 * 冒烟必须从 18080 打进来验到这一层。</p>
 */
@RestController
@RequestMapping("/api/ltv-policies")
public class LtvPolicyController {

    private final LtvPolicyApplicationService policyService;

    public LtvPolicyController(LtvPolicyApplicationService policyService) {
        this.policyService = policyService;
    }

    /** 列表（可按状态筛），带集合与 L1〜L4 校验结论内联。 */
    @GetMapping
    @RequirePermission(Permission.LTV_POLICY_READ)
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(
            @RequestParam(value = "status", required = false) String status,
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(policyService.list(status), trace(request)));
    }

    /** 起草新版本（服务端推 version，状态恒为 DRAFT）。 */
    @PostMapping
    @RequirePermission(Permission.LTV_POLICY_MANAGE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestBody UsedRequests.LtvPolicyRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemHeader,
            HttpServletRequest request) {
        CreateLtvPolicyCmd cmd = req == null ? new CreateLtvPolicyCmd(null, null, null, null, null)
                : new CreateLtvPolicyCmd(req.policyCode, req.policyName, req.effectiveFrom,
                        req.effectiveTo, req.defaultRatio);
        Map<String, Object> data = withIdempotencyHeader(
                policyService.create(cmd, AuditActor.resolve(request), trace(request)), idemHeader);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data, trace(request)));
    }

    /**
     * 全量替换带集合（服务端跑 L1〜L4，未通过则整体不写）。
     *
     * <p>请求体是<b>数组本体</b>而不是 {@code {bands:[...]}}：与 D07 §7.2「PUT 全量替换」的
     * 形状一致，也避免「传了对象但忘写 bands 键」被读成「清空带集合」——
     * 这两件事的后果差一整条政策。</p>
     */
    @PutMapping("/{id}/bands")
    @RequirePermission(Permission.LTV_POLICY_MANAGE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> replaceBands(
            @PathVariable("id") Long id,
            @RequestBody List<UsedRequests.LtvBandRequest> bands,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemHeader,
            HttpServletRequest request) {
        requireId(id);
        Map<String, Object> data = withIdempotencyHeader(
                policyService.replaceBands(id, toDrafts(bands), AuditActor.resolve(request),
                        trace(request)), idemHeader);
        return ResponseEntity.ok(ApiResponse.ok(data, trace(request)));
    }

    /**
     * 批准生效（D07 §7.2：旧版自动 {@code RETIRED}）。
     *
     * <p>请求体为空：批准这个动作的输入只有「谁批的」（令牌身份）与「批哪一版」（路径）。
     * 任何附带理由的字段都会变成可被伪造的事后说明，而 {@code audit_events} 里已经有完整快照。</p>
     */
    @PostMapping("/{id}/approve")
    @RequirePermission(Permission.LTV_POLICY_APPROVE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> approve(
            @PathVariable("id") Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemHeader,
            HttpServletRequest request) {
        requireId(id);
        Map<String, Object> data = withIdempotencyHeader(
                policyService.approve(id, AuditActor.resolve(request), trace(request)), idemHeader);
        return ResponseEntity.ok(ApiResponse.ok(data, trace(request)));
    }

    /**
     * 只算不落的影响面试算（D08 §5.2 的评审入口）。
     *
     * <p>{@code requested_amount} 与 {@code dealer_grade} 都可空：
     * 不给申请额就只回答「会定到哪一档」，给了才顺手算「这个额度会不会被 LTV_001 拒」。
     * 结论一律 200，未命中／多重命中以 {@code outcome} 字段表达，
     * 理由见用例注释（评审要数缺口，拒付码属闸门那一层）。</p>
     */
    @GetMapping("/{id}/simulation")
    @RequirePermission(Permission.LTV_POLICY_READ)
    public ResponseEntity<ApiResponse<Map<String, Object>>> simulate(
            @PathVariable("id") Long id,
            @RequestParam("vin") String vin,
            @RequestParam(value = "dealerGrade", required = false) String dealerGrade,
            @RequestParam(value = "asOf", required = false) LocalDate asOf,
            @RequestParam(value = "requestedAmount", required = false) Long requestedAmount,
            HttpServletRequest request) {
        requireId(id);
        return ResponseEntity.ok(ApiResponse.ok(
                policyService.simulate(id, vin, dealerGrade, asOf, requestedAmount), trace(request)));
    }

    /** 请求体 → 命令记录；DTO 是可变的（Jackson），命令是不可变的（用例）。 */
    private static List<LtvBandDraft> toDrafts(List<UsedRequests.LtvBandRequest> bands) {
        if (bands == null) {
            return null;
        }
        List<LtvBandDraft> drafts = new ArrayList<>(bands.size());
        for (UsedRequests.LtvBandRequest b : bands) {
            if (b == null) {
                drafts.add(null);
                continue;
            }
            drafts.add(new LtvBandDraft(b.priority, b.ageBandMinMonths, b.ageBandMaxMonths,
                    b.mileageBandMinKm, b.mileageBandMaxKm, b.repairHistoryIn, b.gradeMin, b.gradeMax,
                    b.inspectionRemainingMin, b.modelHeat, b.dealerGradeIn, b.maxRatio,
                    b.rateAddonBps, b.breachThreshold, b.note));
        }
        return drafts;
    }

    private static void requireId(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.VALID_001, "路径上的政策 id 必须是正整数");
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
}
