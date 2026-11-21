package com.company.floorplan.web.controller;

import com.company.floorplan.application.service.AdvanceApplicationService;
import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.Advance;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.web.config.AuditActor;
import com.company.floorplan.web.dto.ApiRequests;
import com.company.floorplan.web.security.PaymentWebhookVerifier;
import com.company.floorplan.web.security.RequirePermission;
import com.company.floorplan.web.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 垫款接口（业务闭环第 ③ 环节，系统最核心的写操作）。
 *
 * <p><b>职责分离（SOD）+ 银行回调专属鉴权</b>：垫款的"申请""复核""支付""银行确认"
 * 由四类不同主体触发——</p>
 * <ul>
 *   <li>{@code POST /api/advances}：申请，落 {@code PENDING} 指令、占用额度
 *       （申请人＝业务经办岗，权限点 {@code ADVANCE_APPLY}）；</li>
 *   <li>{@code POST /api/advances/{no}/review}：复核，核对放款要素与收款方
 *       （复核人＝资金复核岗，权限点 {@code ADVANCE_REVIEW}）；</li>
 *   <li>{@code POST /api/advances/{no}/disburse}：支付，提交支付通道、推 {@code PROCESSING}
 *       （支付人＝资金出纳岗，权限点 {@code ADVANCE_DISBURSE}）；</li>
 *   <li>{@code POST /api/advances/{no}/confirm}、{@code /fail}：<b>银行异步回调</b>，
 *       把 {@code PROCESSING} 收敛为 {@code PAID}/{@code FAILED}。
 *       这两个端点从 JWT 拦截器排除，仅接受带合法 HMAC 签名的支付通道调用，
 *       内部用户一律调不到——彻底杜绝"自行确认放款"。</li>
 * </ul>
 *
 * <p><b>申请 / 复核 / 支付三岗互斥的意义</b>：合并前两项意味着"自己填的申请自己批"，
 * 合并后两项意味着"自己批的自己打"——合并任一相邻段，
 * 收款方与金额就可能被同一人同时篡改，而系统无从发现。
 * 角色层面的不相容由 {@code RoleSeparationPolicy} 声明，
 * 同一笔业务上的自然人级复核由应用层的 {@code AUTH_003} 兜底。</p>
 *
 * <p><b>幂等</b>：申请支持 {@code Idempotency-Key} 请求头（推荐）或请求体中的 {@code idempotencyKey}。</p>
 */
@RestController
@RequestMapping("/api/advances")
public class AdvanceController {

    private final AdvanceApplicationService service;
    private final PaymentWebhookVerifier webhookVerifier;

    public AdvanceController(AdvanceApplicationService service, PaymentWebhookVerifier webhookVerifier) {
        this.service = service;
        this.webhookVerifier = webhookVerifier;
    }

    /**
     * 垫款申请（业务经办岗）。
     *
     * <p>只落 {@code PENDING} 指令、占用额度，<b>不调用支付通道</b>——
     * 申请人无权也无能力把资金放出去。</p>
     */
    @PostMapping
    @RequirePermission(Permission.ADVANCE_APPLY)
    public ResponseEntity<ApiResponse<Map<String, Object>>> apply(
            @RequestBody ApiRequests.AdvanceRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        String key = idempotencyKey != null ? idempotencyKey
                : (req.idempotencyKey != null ? req.idempotencyKey : UUID.randomUUID().toString());
        Map<String, Object> data = service.applyAdvance(req.creditLineId, req.vin, key,
                AuditActor.resolve(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data, UUID.randomUUID().toString()));
    }

    /**
     * 垫款复核（资金复核岗）。
     *
     * <p>在支付之前核对放款要素：收款方是谁（DIRECT_PAY 代付上游 / SELF_PAY 回款经销商）、
     * 收款账户是否有效、金额与垫款比例是否合规、委托付款协议是否已签（DIRECT_PAY 场景）。
     * 复核<b>只签字不划款</b>，且<b>不得复核自己发起的申请</b>。</p>
     */
    @PostMapping("/{advanceNo}/review")
    @RequirePermission(Permission.ADVANCE_REVIEW)
    public ResponseEntity<ApiResponse<Map<String, Object>>> review(
            @PathVariable String advanceNo,
            HttpServletRequest request) {
        Map<String, Object> data = service.reviewAdvance(advanceNo, AuditActor.resolve(request));
        return ResponseEntity.ok(ApiResponse.ok(data, UUID.randomUUID().toString()));
    }

    /**
     * 支付放款（资金出纳岗）。
     *
     * <p><b>职责分离（SOD）</b>：与"申请""复核"必须由不同角色、不同自然人触发——
     * 申请人只能落 PENDING 指令，资金岗才能真正把款划出去。
     * 本端点<b>只提交支付指令</b>并把垫款推到 PROCESSING，
     * <b>绝不内联确认付款结果</b>——终态只能由银行回调 {@code /confirm}、{@code /fail} 翻。</p>
     */
    @PostMapping("/{advanceNo}/disburse")
    @RequirePermission(Permission.ADVANCE_DISBURSE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> disburse(
            @PathVariable String advanceNo,
            HttpServletRequest request) {
        Map<String, Object> data = service.disburseAdvance(advanceNo, AuditActor.resolve(request));
        return ResponseEntity.ok(ApiResponse.ok(data, UUID.randomUUID().toString()));
    }

    /**
     * 支付网关回调：确认支付成功（由 {@code AdvanceApplicationService#confirmByAdvanceNo} 落库）。
     *
     * <p><b>仅银行 webhook 可调用</b>：HMAC 签名校验（{@link PaymentWebhookVerifier}），
     * 不做用户 JWT 鉴权；校验失败返回 401 PAYMENT_001。审计操作人固定为 {@code PAYMENT_GATEWAY}。</p>
     */
    @PostMapping("/{advanceNo}/confirm")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirm(
            @PathVariable String advanceNo,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-Payment-Signature", required = false) String signature,
            @RequestHeader(value = "X-Payment-Timestamp", required = false) String timestamp) {
        String ref = body != null && body.get("paymentRef") != null
                ? String.valueOf(body.get("paymentRef")) : null;
        webhookVerifier.verify(advanceNo, timestamp, ref, signature);
        Advance a = service.confirmByAdvanceNo(advanceNo, ref, PaymentWebhookVerifier.WEBHOOK_ACTOR);
        return ResponseEntity.ok(ApiResponse.ok(toAdvanceData(a), UUID.randomUUID().toString()));
    }

    /**
     * 支付网关回调：标记支付失败（释放额度、翻 FAILED）。同样仅银行 webhook 可调用。
     */
    @PostMapping("/{advanceNo}/fail")
    public ResponseEntity<ApiResponse<Map<String, Object>>> fail(
            @PathVariable String advanceNo,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-Payment-Signature", required = false) String signature,
            @RequestHeader(value = "X-Payment-Timestamp", required = false) String timestamp) {
        String msg = body != null && body.get("message") != null
                ? String.valueOf(body.get("message")) : null;
        webhookVerifier.verify(advanceNo, timestamp, msg, signature);
        Advance a = service.failByAdvanceNo(advanceNo, msg, PaymentWebhookVerifier.WEBHOOK_ACTOR);
        return ResponseEntity.ok(ApiResponse.ok(toAdvanceData(a), UUID.randomUUID().toString()));
    }

    /**
     * 作废垫款申请（经销商主动撤销 / 风控驳回 / 超时未放款）。
     *
     * <p><b>为什么不是删除</b>：删除会丢掉"谁撤的、何时撤的、额度被占了多久"，
     * 而这正是重复融资风控要看的信号。作废后记录以 {@code VOIDED} 留存，
     * <b>不占用该车唯一的垫款名额</b>，随时可以重新申请——
     * 同一台车因此可以留下任意多条作废记录。</p>
     *
     * <p><b>仅 PENDING 可作废</b>：已提交银行的 {@code PROCESSING} 不许作废
     * （否则会出现"系统里没记录了、银行却把钱打出去"），
     * 已付款的 {@code PAID} 只能走冲正。</p>
     *
     * <p>权限沿用 {@link Permission#ADVANCE_APPLY}：能发起申请的人才能撤销自己的申请；
     * 风控驳回由具备该权限的内部岗调用，原因传 {@code REVIEW_REJECTED}。</p>
     */
    @PostMapping("/{advanceNo}/void")
    @RequirePermission(Permission.ADVANCE_APPLY)
    public ResponseEntity<ApiResponse<Map<String, Object>>> voidAdvance(
            @PathVariable String advanceNo,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        Advance target = service.findByAdvanceNo(advanceNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "垫款不存在：" + advanceNo));
        String reasonCode = body == null || body.get("reasonCode") == null
                ? null : String.valueOf(body.get("reasonCode"));
        String remark = body == null || body.get("remark") == null
                ? null : String.valueOf(body.get("remark"));
        Advance a = service.voidAdvance(target.getId(), reasonCode, remark, AuditActor.resolve(request));
        return ResponseEntity.ok(ApiResponse.ok(toAdvanceData(a), UUID.randomUUID().toString()));
    }

    private Map<String, Object> toAdvanceData(Advance a) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("advanceId", a.getAdvanceNo());
        data.put("status", a.getStatus() == null ? null : a.getStatus().name());
        data.put("paymentRef", a.getPaymentRef());
        data.put("closedReason", a.getClosedReason());
        return data;
    }
}
