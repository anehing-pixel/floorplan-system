package com.company.floorplan.web.controller;

import com.company.floorplan.application.dto.LicenseDraft;
import com.company.floorplan.application.service.DealerQualificationApplicationService;
import com.company.floorplan.application.service.DealerRatingApplicationService;
import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.LicenseType;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.web.config.AuditActor;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 车商资质内部端点（USED R1，D07 §7.1 的 8 行）。
 *
 * <p><b>路径裁决（对应 D07 §7.0 表第 1 行的 ⚠️ 冲突，本轮定案）</b>：
 * D07 §7.1 把这些端点标在 {@code web}，但网关把 {@code /api/dealers/**} 整段路由给
 * <b>credit-service</b>（{@code gateway/.../application.yml:49-52}）。两个选项中取 ②：
 * <b>换用未被占用的前缀 {@code /api/dealer-qualifications/**}，由 core 实现</b>。</p>
 *
 * <p>不取 ①（改由 credit-service 实现）的理由，按后果排序：</p>
 * <ol>
 *   <li>取 ① 会让同一前缀下出现两套实现——credit 现有的 {@code DealerController}
 *       已经在 {@code /api/dealers} 上做 LEGACY 档案 CRUD，USED 的资质子资源挂进去，
 *       正好是 §7.0 警告的「端点存在、编译通过、冒烟 404 或返回了另一套实现」；</li>
 *   <li>取 ① 需要把 R1 的仓储与策略接进 credit-service，而 R1 的产物（准入态）
 *       的直接消费方是 core 里的放款闸门；同一事务内写证据＋重算快照这条链
 *       跨服务后就地断成两次调用，红线的原子性没了；</li>
 *   <li>取 ② 不动网关 yml（AGENTS §7.1 共享禁区），core 兜底路由合法接住新前缀。</li>
 * </ol>
 * <p>代价：路径名与 D07 §7.1 的字面表不一致，已在 D07 §7.0 下登记为定案，
 * 收口时若仍要归 credit，需要连带把放款闸门的读入也搬迁，不是改个路径那么简单。</p>
 *
 * <p><b>幂等键的来源</b>：D07 §7 要求写操作带 {@code Idempotency-Key} 头，
 * 但本域的四枚键（{@code DLIC/DMEM/CHK/DSCR}）都能由<b>自然键在服务端推出</b>，
 * 比客户端随手给的一个字符串可靠。所以这里的处理是：头接受、原样回显在响应里，
 * 真实判定走服务端推导的自然键；客户端给的头不一致时不因此拒绝（否则同一个业务动作
 * 会因为客户端换了这个字段而重复落库）。唯一真正需要客户端给键的是评分批次（{@code runTs}）。</p>
 */
@RestController
@RequestMapping("/api/dealer-qualifications")
public class DealerQualificationController {

    private final DealerQualificationApplicationService qualificationService;
    private final DealerRatingApplicationService ratingService;

    public DealerQualificationController(DealerQualificationApplicationService qualificationService,
                                        DealerRatingApplicationService ratingService) {
        this.qualificationService = qualificationService;
        this.ratingService = ratingService;
    }

    /** 登记/换发古物商许可（旧 ACTIVE 证自动置 SUPERSEDED）。 */
    @PostMapping("/{id}/licenses")
    @RequirePermission(Permission.DEALER_QUALIFICATION_MAINTAIN)
    public ResponseEntity<ApiResponse<Map<String, Object>>> upsertLicense(
            @PathVariable("id") Long id,
            @RequestBody UsedRequests.LicenseRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemHeader,
            HttpServletRequest request) {
        LicenseType type = parseLicenseType(req.licenseType);
        LicenseDraft draft = new LicenseDraft(id, type, req.licenseNo, req.competentAuthority,
                req.issuedOn, req.expiresOn, req.evidencePath, req.evidenceDigest);
        Map<String, Object> data = withIdempotencyHeader(
                qualificationService.upsertLicense(draft, AuditActor.resolve(request)), idemHeader);
        return statusOf(data, HttpStatus.CREATED).body(ApiResponse.ok(data, trace(request)));
    }

    /** 许可列表（含历史）。 */
    @GetMapping("/{id}/licenses")
    @RequirePermission(Permission.DEALER_READ)
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listLicenses(
            @PathVariable("id") Long id, HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(qualificationService.listLicenses(id), trace(request)));
    }

    /** 维护拍卖场会员关系（同自然键就地更新）。 */
    @PostMapping("/{id}/auction-memberships")
    @RequirePermission(Permission.DEALER_QUALIFICATION_MAINTAIN)
    public ResponseEntity<ApiResponse<Map<String, Object>>> upsertMembership(
            @PathVariable("id") Long id,
            @RequestBody UsedRequests.MembershipRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemHeader,
            HttpServletRequest request) {
        Map<String, Object> data = withIdempotencyHeader(
                qualificationService.upsertAuctionMembership(id, req.auctionHouseCode, req.memberNo,
                        req.memberGrade, req.joinedOn, req.expiresOn, req.status, req.evidencePath,
                        AuditActor.resolve(request)), idemHeader);
        return statusOf(data, HttpStatus.CREATED).body(ApiResponse.ok(data, trace(request)));
    }

    /** 会员列表，可按状态筛。 */
    @GetMapping("/{id}/auction-memberships")
    @RequirePermission(Permission.DEALER_READ)
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listMemberships(
            @PathVariable("id") Long id,
            @RequestParam(value = "status", required = false) String status,
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                qualificationService.listMemberships(id, status), trace(request)));
    }

    /**
     * 录入合规核查结论。
     *
     * <p>权限点用 {@code DEALER_COMPLIANCE_CHECK}：D07 §7.1 那一行写的是
     * {@code COMPLIANCE_CHECK_DONE}，但本仓 {@link Permission} 里的常量名是前者
     * （D10 定源，注释亦标「D07 §7.1 行，同一处置」）。按常量名实现，不改文档里的措辞。</p>
     */
    @PostMapping("/{id}/compliance-checks")
    @RequirePermission(Permission.DEALER_COMPLIANCE_CHECK)
    public ResponseEntity<ApiResponse<Map<String, Object>>> recordComplianceCheck(
            @PathVariable("id") Long id,
            @RequestBody UsedRequests.ComplianceCheckRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemHeader,
            HttpServletRequest request) {
        Map<String, Object> data = withIdempotencyHeader(
                qualificationService.recordComplianceCheck(id, req.checkType, req.checkedOn, req.validUntil,
                        req.result, req.source, req.requestNo, req.evidencePath, req.note,
                        req.coSignedBy, AuditActor.resolve(request)), idemHeader);
        return statusOf(data, HttpStatus.CREATED).body(ApiResponse.ok(data, trace(request)));
    }

    /** 双维评分录入（二人签认；G-A4）。 */
    @PostMapping("/{id}/scores")
    @RequirePermission(Permission.DEALER_SCORE_SET)
    public ResponseEntity<ApiResponse<Map<String, Object>>> score(
            @PathVariable("id") Long id,
            @RequestBody UsedRequests.DealerScoreRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemHeader,
            HttpServletRequest request) {
        // 幂等键形状 DSCR:{dealerId}:{ts}（D07 §7.1）：ts 由客户端给（同一批分数重推要同值），
        // 服务端补上前缀，避免客户端拼错形状后两个不同批次撞成一个键。
        String runKey = req.runTs == null || req.runTs.isBlank()
                ? idemHeader
                : "DSCR:" + id + ":" + req.runTs.trim();
        Map<String, Object> data = withIdempotencyHeader(
                ratingService.scoreDimension(id, req.operatingScore, req.stockQualityScore,
                        runKey, req.coSignedBy, AuditActor.resolve(request)), idemHeader);
        return ResponseEntity.ok(ApiResponse.ok(data, trace(request)));
    }

    /** 读准入态与证据概览（只读，不重算）。 */
    @GetMapping("/{id}/admission")
    @RequirePermission(Permission.DEALER_READ)
    public ResponseEntity<ApiResponse<Map<String, Object>>> admission(
            @PathVariable("id") Long id, HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(qualificationService.getAdmission(id), trace(request)));
    }

    /** 重算准入态快照（UC-R1-4；到期作业与资质变更后的手工触发共用）。 */
    @PostMapping("/{id}/admission/recalculate")
    @RequirePermission(Permission.DEALER_ADMISSION_MANAGE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> recalculateAdmission(
            @PathVariable("id") Long id,
            @RequestParam(value = "reason", required = false, defaultValue = "MANUAL") String reason,
            HttpServletRequest request) {
        Map<String, Object> data = qualificationService.recalculateAdmission(id, reason, AuditActor.resolve(request));
        return ResponseEntity.ok(ApiResponse.ok(data, trace(request)));
    }

    /**
     * 许可类型解析：省略＝FURIGUSHO（当前取值域唯一），给了别的值必须报参数错误。
     *
     * <p>不在这里静默接受任意字符串：库内 {@code ck_dlic_license_type} 只允许 FURIGUSHO，
     * 若把它交给 SQL 去撞 CHECK，报出来的就是 500 SYS_001 而不是 400 VALID_001，
     * 前端拿不到可懂的提示，运维也只能翻日志。</p>
     */
    private static LicenseType parseLicenseType(String value) {
        if (value == null || value.isBlank()) {
            return LicenseType.FURIGUSHO;
        }
        try {
            return LicenseType.from(value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALID_001, "不支持的许可类型: " + value);
        }
    }

    /** 响应体里的 traceId：优先用请求上下文里已有的（RequestLoggingFilter 生成），没有就自己造一个。 */
    private static String trace(HttpServletRequest request) {
        Object existing = request.getAttribute(com.company.floorplan.web.config.RequestLoggingFilter.ATTR_TRACE_ID);
        return existing == null ? UUID.randomUUID().toString() : String.valueOf(existing);
    }

    /** 把客户端的 Idempotency-Key 原样回显（不代表本次真的用它判了幂等，见类注释）。 */
    private static Map<String, Object> withIdempotencyHeader(Map<String, Object> data, String idemHeader) {
        data.put("idempotencyKeyHeader", idemHeader);
        return data;
    }

    /** 新建返 201，命中幂等或就地更新返 200——冒烟可以据此区分「第一次」与「重推」。 */
    private static ResponseEntity.BodyBuilder statusOf(Map<String, Object> data, HttpStatus created) {
        boolean createdNow = !Boolean.TRUE.equals(data.get("idempotent"))
                && !Boolean.FALSE.equals(data.get("created"));
        return ResponseEntity.status(createdNow ? created : HttpStatus.OK);
    }
}
