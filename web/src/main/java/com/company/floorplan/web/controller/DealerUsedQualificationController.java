package com.company.floorplan.web.controller;

import com.company.floorplan.application.service.DealerQualificationApplicationService;
import com.company.floorplan.application.service.DealerRatingApplicationService;
import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.AdmissionDecision;
import com.company.floorplan.domain.model.AuctionMembership;
import com.company.floorplan.domain.model.ComplianceCheck;
import com.company.floorplan.domain.model.DealerLicense;
import com.company.floorplan.domain.model.DealerScoreReview;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.web.config.AuditActor;
import com.company.floorplan.web.dto.ApiResponse;
import com.company.floorplan.web.dto.UsedQualificationRequests;
import com.company.floorplan.web.security.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * USED 域车商资质与准入接口（R1；端点清单＝D07 §7.1 给 W-U1 的那八行，一字不多不少）。
 *
 * <p><b>本层的纪律</b>：只做"绑定请求体 → 调应用服务 → 投影成响应 Map"。
 * 异常一律抛给 {@code GlobalExceptionHandler} 翻码，
 * 这里不写 try-catch 把业务失败包成 200（AGENTS 分层约定 §4）。</p>
 *
 * <p><b>配置闸门（D00 §12 的 {@code floorplan.used.enabled}，P-3 双闸门的①）</b>：
 * 缺省 {@code false}，此时本控制器<b>读写全部拒绝</b>，回
 * {@code VALID_001 reason=USED_FEATURE_DISABLED}。
 * 取值口径说明：D00 §12 那行写的是「{@code false} 时 USED 域所有新<b>写</b>端点返回
 * {@code VALID_001}」，而 D00 §1.2 P-3 写的是「任一切断，USED 域<b>全部新代码路径</b>不可达」。
 * 两条范围不同，本包按<b>较严</b>的那条做：读取也拦。
 * 理由是 P-3 存在的目的正是"评审没过就不能让新代码被执行"，
 * 留一条只读的新区代码路径就等于留一个绕过闸门的口子；
 * 真需要关着闸门看数据时，直接查库比开闸门更符合回退意图。
 * 该取舍已登记待收口方确认（一句话即可改回：把 {@link #requireUsedEnabled()}
 * 从三个 GET 方法里删掉）。</p>
 *
 * <p><b>GET 为什么不标权限点</b>：D07 §7.1 的三行 GET 写的是 {@code DEALER_READ}，
 * 但（a）{@link Permission} 里没有这个常量，(b) 仓内约定是"查询类接口不标注解、
 * 对所有已登录用户开放"（见 {@link RequirePermission} 类注释），
 * (c) D10 的角色矩阵里 {@code DEALER_READ} 该授给哪些岗没有清单（D10 §表只给了风险级 L）。
 * 三条合起来意味着：现在加这个点并注解上去，效果只能靠"授给所有角色"来抵消，
 * 那是把同一个行为绕一圈实现。故本轮按仓内约定不加注解，
 * 位置留在这里等 D00 §11 的授予矩阵。</p>
 *
 * <p><b>{@code POST /compliance-checks} 的权限点名字冲突（已按定源解决）</b>：
 * D07 §7.1 与 D10 §表把 {@code COMPLIANCE_CHECK_DONE} 当权限点写，
 * 但 D00 §11（权限点定源）给的是 {@code DEALER_COMPLIANCE_CHECK}，
 * 而 {@code COMPLIANCE_CHECK_DONE} 在 D00 §13 的事件词表里是<b>审计动作名</b>。
 * 本控制器用权限点 {@code DEALER_COMPLIANCE_CHECK}，
 * 服务层用动作名 {@code COMPLIANCE_CHECK_DONE} 写审计——两边各归各的定源，不互相冒充。</p>
 *
 * <p><b>{@code POST /scores} 的"（二人）"落在哪里</b>：D07 §7.1 那行标了
 * {@code DEALER_SCORE_SET}（二人），但双维评级的"二人"是<b>确认</b>动作的约束
 * （D06 §1.4），而 D07 没有给确认端点行。本端点因此是单人录入一维分，
 * 二人校验在 {@code DealerRatingApplicationService.confirmScores} 里
 * （{@code AUTH_003} 拦自证），等确认端点定源后接线。</p>
 *
 * <p><b>操作人只来自令牌</b>：{@link AuditActor#resolve} 是 {@code audit_events.actor} 的唯一来源，
 * 请求体里不给 {@code operator} 字段（客户端可伪造，见 {@link AuditActor} 类注释记的那次事故）。</p>
 */
@RestController
@RequestMapping("/api")
public class DealerUsedQualificationController {

    private final DealerQualificationApplicationService qualificationService;
    private final DealerRatingApplicationService ratingService;

    /** P-3 配置闸门（D00 §12，默认 false＝USED 域不可达）。 */
    private final boolean usedEnabled;

    public DealerUsedQualificationController(DealerQualificationApplicationService qualificationService,
                                             DealerRatingApplicationService ratingService,
                                             @Value("${floorplan.used.enabled:false}") boolean usedEnabled) {
        this.qualificationService = qualificationService;
        this.ratingService = ratingService;
        this.usedEnabled = usedEnabled;
    }

    /* ---------------- 古物商许可（D07 §7.1 第 1–2 行） ---------------- */

    /**
     * 许可新增／订正／换证（幂等键 {@code DLIC:{dealerId}:{type}:{no}} 的自然键落点）。
     *
     * @param id      车商 ID
     * @param body    请求体
     * @param request 当前请求（取令牌身份与 traceId）
     * @return 许可行投影
     */
    @PostMapping("/dealers/{id}/licenses")
    @RequirePermission(Permission.DEALER_QUALIFICATION_MAINTAIN)
    public ResponseEntity<ApiResponse<Map<String, Object>>> upsertLicense(
            @PathVariable("id") Long id,
            @RequestBody UsedQualificationRequests.LicenseUpsertRequest body,
            HttpServletRequest request) {
        requireUsedEnabled();
        UsedQualificationRequests.LicenseUpsertRequest req = requireBody(body);
        String traceId = traceId(request);
        DealerLicense license = qualificationService.upsertLicense(id, req.licenseType, req.licenseNo,
                req.competentAuthority, parseDate(req.issuedOn, "issuedOn"), parseDate(req.expiresOn, "expiresOn"),
                req.evidencePath, req.evidenceDigest, req.verifiedBy, parseInstant(req.verifiedAt),
                AuditActor.resolve(request), traceId);
        return ResponseEntity.ok(ApiResponse.ok(toLicenseMap(license), traceId));
    }

    /**
     * 许可清单（含 {@code SUPERSEDED}／{@code REVOKED} 历史行，换证链在接口上是连着的）。
     *
     * @param id      车商 ID
     * @param request 当前请求
     * @return 许可行列表
     */
    @GetMapping("/dealers/{id}/licenses")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listLicenses(
            @PathVariable("id") Long id, HttpServletRequest request) {
        requireUsedEnabled();
        String traceId = traceId(request);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DealerLicense license : qualificationService.listLicenses(id)) {
            rows.add(toLicenseMap(license));
        }
        return ResponseEntity.ok(ApiResponse.ok(rows, traceId));
    }

    /* ---------------- 拍卖场会员关系（D07 §7.1 第 3–4 行） ---------------- */

    /**
     * 会员关系新增／订正（幂等键 {@code DMEM:{dealerId}:{house}:{memberNo}}）。
     *
     * @param id      车商 ID
     * @param body    请求体
     * @param request 当前请求
     * @return 会员行投影
     */
    @PostMapping("/dealers/{id}/auction-memberships")
    @RequirePermission(Permission.DEALER_QUALIFICATION_MAINTAIN)
    public ResponseEntity<ApiResponse<Map<String, Object>>> upsertAuctionMembership(
            @PathVariable("id") Long id,
            @RequestBody UsedQualificationRequests.AuctionMembershipUpsertRequest body,
            HttpServletRequest request) {
        requireUsedEnabled();
        UsedQualificationRequests.AuctionMembershipUpsertRequest req = requireBody(body);
        String traceId = traceId(request);
        AuctionMembership membership = qualificationService.upsertAuctionMembership(id,
                req.auctionHouseCode, req.memberNo, req.memberGrade,
                parseDate(req.joinedOn, "joinedOn"), parseDate(req.expiresOn, "expiresOn"),
                req.evidencePath, AuditActor.resolve(request), traceId);
        return ResponseEntity.ok(ApiResponse.ok(toMembershipMap(membership), traceId));
    }

    /**
     * 会员关系列表。
     *
     * @param id      车商 ID
     * @param status  状态过滤（D07 §7.1 唯一给了查询参数的 GET；非法取值回 400 而不是空列表）
     * @param request 当前请求
     * @return 会员行列表
     */
    @GetMapping("/dealers/{id}/auction-memberships")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listAuctionMemberships(
            @PathVariable("id") Long id,
            @RequestParam(value = "status", required = false) String status,
            HttpServletRequest request) {
        requireUsedEnabled();
        String traceId = traceId(request);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AuctionMembership membership : qualificationService.listAuctionMemberships(id, status)) {
            rows.add(toMembershipMap(membership));
        }
        return ResponseEntity.ok(ApiResponse.ok(rows, traceId));
    }

    /* ---------------- 合规核查（D07 §7.1 第 5 行） ---------------- */

    /**
     * 合规核查结论录入（幂等键 {@code CHK:{partner}:{requestNo}} 的 {@code requestNo} 段落点）。
     *
     * <p>权限点用 {@code DEALER_COMPLIANCE_CHECK}（D00 §11 定源），
     * 不用 D07 那行写的 {@code COMPLIANCE_CHECK_DONE}——后者是审计动作名，理由见类注释。</p>
     *
     * @param id      车商 ID
     * @param body    请求体
     * @param request 当前请求
     * @return 核查行投影
     */
    @PostMapping("/dealers/{id}/compliance-checks")
    @RequirePermission(Permission.DEALER_COMPLIANCE_CHECK)
    public ResponseEntity<ApiResponse<Map<String, Object>>> recordComplianceCheck(
            @PathVariable("id") Long id,
            @RequestBody UsedQualificationRequests.ComplianceCheckRequest body,
            HttpServletRequest request) {
        requireUsedEnabled();
        UsedQualificationRequests.ComplianceCheckRequest req = requireBody(body);
        String traceId = traceId(request);
        String actor = AuditActor.resolve(request);
        ComplianceCheck check = qualificationService.recordComplianceCheck(id, req.checkType,
                parseDate(req.checkedOn, "checkedOn"), parseDate(req.validUntil, "validUntil"),
                req.result, req.source, req.requestNo, req.evidencePath, req.note,
                req.checkedBy, actor, traceId);
        return ResponseEntity.ok(ApiResponse.ok(toComplianceMap(check), traceId));
    }

    /* ---------------- 双维评级（D07 §7.1 第 6 行） ---------------- */

    /**
     * 单维评分录入（{@code Idempotency-Key} 请求头优先于请求体同名字段）。
     *
     * <p><b>与既有控制器的一个刻意差异</b>：{@code AdvanceController} 在两个来源都空时
     * 用 {@code UUID.randomUUID()} 兜一个键。本端点<b>不跟这个写法</b>——
     * 服务端自造的随机键每次都不一样，既不能去重也不能重放，
     * 只会把"这次请求没有幂等保护"这件事藏进一个看起来很规范的键里
     * （D07 §7.1 给的 {@code DSCR:{dealerId}:{ts}} 里那个 {@code ts} 是<b>调用方</b>时间戳，
     * 语义上就要求由客户端出）。缺键时本端点照常录入，但不承诺重放安全。</p>
     *
     * @param id             车商 ID
     * @param body           请求体
     * @param idempotencyKey {@code Idempotency-Key} 请求头
     * @param request        当前请求
     * @return 候选行投影（含 version，供下一次订正带 CAS）
     */
    @PostMapping("/dealers/{id}/scores")
    @RequirePermission(Permission.DEALER_SCORE_SET)
    public ResponseEntity<ApiResponse<Map<String, Object>>> scoreDimension(
            @PathVariable("id") Long id,
            @RequestBody UsedQualificationRequests.ScoreDimensionRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        requireUsedEnabled();
        UsedQualificationRequests.ScoreDimensionRequest req = requireBody(body);
        String traceId = traceId(request);
        String key = idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey : req.idempotencyKey;
        DealerScoreReview candidate = ratingService.scoreDimension(id, req.dimension, req.score, req.reviewYear,
                req.evidencePath, req.note, key, AuditActor.resolve(request), traceId);
        return ResponseEntity.ok(ApiResponse.ok(toScoreMap(candidate), traceId));
    }

    /* ---------------- 准入态（D07 §7.1 第 7–8 行） ---------------- */

    /**
     * 当前准入判定（只算不落；与下面 recalculate 的分工见服务层注释）。
     *
     * @param id      车商 ID
     * @param request 当前请求
     * @return 判定投影（现算结论＋库中存量＋本次用的预警窗口）
     */
    @GetMapping("/dealers/{id}/admission")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAdmission(
            @PathVariable("id") Long id, HttpServletRequest request) {
        requireUsedEnabled();
        String traceId = traceId(request);
        DealerQualificationApplicationService.AdmissionView view = qualificationService.currentAdmission(id, null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dealerId", view.dealerId());
        m.put("bizDate", view.bizDate());
        m.put("storedState", view.storedState() == null ? null : view.storedState().name());
        m.put("storedStateLabel", view.storedState() == null ? null : view.storedState().getLabel());
        m.putAll(toDecisionMap(view.decision()));
        m.put("storedMatchesComputed", view.storedState() == view.decision().getState());
        m.put("expiringWindowDays", view.expiringWindowDays());
        m.put("expiringWindowSigned", view.expiringWindowDays() != null);
        return ResponseEntity.ok(ApiResponse.ok(m, traceId));
    }

    /**
     * 重算并落准入态（唯一会把结论写进 {@code dealers.admission_state} 的端点）。
     *
     * @param id      车商 ID
     * @param body    请求体（只有 reason）
     * @param request 当前请求
     * @return 判定投影
     */
    @PostMapping("/dealers/{id}/admission/recalculate")
    @RequirePermission(Permission.DEALER_ADMISSION_MANAGE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> recalculateAdmission(
            @PathVariable("id") Long id,
            @RequestBody(required = false) UsedQualificationRequests.AdmissionRecalculateRequest body,
            HttpServletRequest request) {
        requireUsedEnabled();
        String traceId = traceId(request);
        String reason = body == null || body.reason == null || body.reason.isBlank()
                ? "MANUAL_API" : body.reason.trim();
        AdmissionDecision decision = qualificationService.recalculateAdmission(id, reason,
                AuditActor.resolve(request), traceId);
        Map<String, Object> m = new LinkedHashMap<>(toDecisionMap(decision));
        m.put("recalculateReason", reason);
        return ResponseEntity.ok(ApiResponse.ok(m, traceId));
    }

    /* ---------------- 投影与入口校验 ---------------- */

    /** 许可行投影（枚举同时给 name 与 label，前端不必再翻字典）。 */
    private static Map<String, Object> toLicenseMap(DealerLicense license) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", license.getId());
        m.put("dealerId", license.getDealerId());
        m.put("licenseType", name(license.getLicenseType()));
        m.put("licenseTypeLabel", license.getLicenseType() == null ? null : license.getLicenseType().getLabel());
        m.put("licenseNo", license.getLicenseNo());
        m.put("competentAuthority", license.getCompetentAuthority());
        m.put("issuedOn", license.getIssuedOn());
        m.put("expiresOn", license.getExpiresOn());
        m.put("status", name(license.getStatus()));
        m.put("statusLabel", license.getStatus() == null ? null : license.getStatus().getLabel());
        m.put("evidencePath", license.getEvidencePath());
        m.put("evidenceDigest", license.getEvidenceDigest());
        m.put("verifiedBy", license.getVerifiedBy());
        m.put("verifiedAt", license.getVerifiedAt());
        m.put("createdBy", license.getCreatedBy());
        m.put("createdAt", license.getCreatedAt());
        m.put("updatedBy", license.getUpdatedBy());
        m.put("updatedAt", license.getUpdatedAt());
        return m;
    }

    /** 会员行投影。 */
    private static Map<String, Object> toMembershipMap(AuctionMembership membership) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", membership.getId());
        m.put("dealerId", membership.getDealerId());
        m.put("auctionHouseCode", membership.getAuctionHouseCode());
        m.put("memberNo", membership.getMemberNo());
        m.put("memberGrade", membership.getMemberGrade());
        m.put("joinedOn", membership.getJoinedOn());
        m.put("expiresOn", membership.getExpiresOn());
        m.put("status", name(membership.getStatus()));
        m.put("statusLabel", membership.getStatus() == null ? null : membership.getStatus().getLabel());
        m.put("evidencePath", membership.getEvidencePath());
        m.put("createdAt", membership.getCreatedAt());
        m.put("updatedAt", membership.getUpdatedAt());
        return m;
    }

    /** 合规核查行投影。 */
    private static Map<String, Object> toComplianceMap(ComplianceCheck check) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", check.getId());
        m.put("dealerId", check.getDealerId());
        m.put("checkType", name(check.getCheckType()));
        m.put("checkTypeLabel", check.getCheckType() == null ? null : check.getCheckType().getLabel());
        m.put("checkedOn", check.getCheckedOn());
        m.put("validUntil", check.getValidUntil());
        m.put("result", name(check.getResult()));
        m.put("resultLabel", check.getResult() == null ? null : check.getResult().getLabel());
        m.put("source", name(check.getSource()));
        m.put("sourceLabel", check.getSource() == null ? null : check.getSource().getLabel());
        m.put("requestNo", check.getRequestNo());
        m.put("evidencePath", check.getEvidencePath());
        m.put("note", check.getNote());
        m.put("checkedBy", check.getCheckedBy());
        m.put("createdAt", check.getCreatedAt());
        return m;
    }

    /** 评级候选行投影。 */
    private static Map<String, Object> toScoreMap(DealerScoreReview review) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", review.getId());
        m.put("dealerId", review.getDealerId());
        m.put("reviewYear", review.getReviewYear());
        m.put("operatingScore", review.getOperatingScore());
        m.put("stockQualityScore", review.getStockQualityScore());
        m.put("status", name(review.getStatus()));
        m.put("statusLabel", review.getStatus() == null ? null : review.getStatus().getLabel());
        m.put("scoredBy", review.getScoredBy());
        m.put("confirmedBy", review.getConfirmedBy());
        m.put("evidencePath", review.getEvidencePath());
        m.put("note", review.getNote());
        m.put("idempotencyKey", review.getIdempotencyKey());
        m.put("version", review.getVersion());
        // 综合分恒为 null 且带一句原因：权重 [待定源]（U-11），本包不算综合分。
        m.put("compositeScore", null);
        m.put("scoreRangeCalibrated", false);
        return m;
    }

    /** 判定结论投影（GET 与 POST recalculate 共用同一形状）。 */
    private static Map<String, Object> toDecisionMap(AdmissionDecision decision) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("admissionState", name(decision.getState()));
        m.put("admissionStateLabel", decision.getState() == null ? null : decision.getState().getLabel());
        m.put("blocked", decision.isBlocked());
        m.put("errorCode", decision.getErrorCode() == null ? null : decision.getErrorCode().name());
        m.put("reason", decision.getReason());
        return m;
    }

    /**
     * P-3 配置闸门：{@code floorplan.used.enabled=false} 时本控制器全部拒绝。
     *
     * <p>返回码按 D00 §12 那行给的 {@code VALID_001}（不是 404——路径是存在的，
     * 404 会让调用方以为接错地址，反而去翻路由）。</p>
     */
    private void requireUsedEnabled() {
        if (!usedEnabled) {
            throw new BusinessException(ErrorCode.VALID_001, Map.of(
                    "reason", "USED_FEATURE_DISABLED",
                    "configKey", "floorplan.used.enabled",
                    "detail", "USED 域闸门未开（P-3 双闸门的配置侧）。开启需评审通过后由部署属性设置，"
                            + "本包不写 yml 默认值"));
        }
    }

    /** 请求体存在性：Spring 的 required 绑定之外再兜一层，给统一的 400 形状。 */
    private static <T> T requireBody(T body) {
        if (body == null) {
            throw new BusinessException(ErrorCode.VALID_001, "请求体不能为空");
        }
        return body;
    }

    /**
     * 日期解析：格式错必须在入口回 400。
     *
     * <p>仓内既有写法（{@code SupplierController#parseDate}）把解析失败吞成 {@code null}，
     * 那是"字段没填"与"字段填错"共用一个值——对 NOT NULL 的业务日期来说，
     * 填错会退化成一次 500 的约束冲突或一条落空的必填校验，本控制器不跟。</p>
     */
    private static LocalDate parseDate(String raw, String field) {
        String value = raw == null ? null : raw.trim();
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.VALID_001, Map.of(
                    "field", field, "incoming", value, "reason", "DATE_FORMAT_INVALID",
                    "detail", "日期格式须为 yyyy-MM-dd"));
        }
    }

    /** 时点解析（ISO-8601，如 {@code 2026-11-22T09:30:00Z}）；同上的口径。 */
    private static Instant parseInstant(String raw) {
        String value = raw == null ? null : raw.trim();
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.VALID_001, Map.of(
                    "field", "verifiedAt", "incoming", value, "reason", "INSTANT_FORMAT_INVALID",
                    "detail", "时点格式须为 ISO-8601，例如 2026-11-22T09:30:00Z"));
        }
    }

    private static String traceId(HttpServletRequest request) {
        return request.getAttribute("traceId") + "";
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
