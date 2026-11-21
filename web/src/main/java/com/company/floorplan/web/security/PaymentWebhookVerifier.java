package com.company.floorplan.web.security;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * 银行/支付通道回调鉴权：HMAC-SHA256 签名校验。
 *
 * <p><b>为什么不用用户 JWT</b>：{@code /confirm}、{@code /fail} 是给<b>银行异步回调</b>用的，
 * 银行没有登录态、拿不到用户令牌。若复用内部 JWT 鉴权，等于任何登录用户（含操作员 USER）
 * 都能自行把 {@code PENDING} 翻成 {@code PAID}——绕过资金岗放款、且绕过真实付款，
 * 形成"虚构放款"的欺诈路径。所以这两个端点从 JWT 拦截器<b>排除</b>，改由本组件用
 * 渠道共享密钥做签名校验，<b>内部用户一律调不到</b>。</p>
 *
 * <p><b>签名算法（渠道对接文档口径，必须与 {@link #sign} 一致）</b>：</p>
 * <pre>
 * 签名原文 = advanceNo + "." + timestamp + "." + (paymentRef 或 message)
 * 签名     = Hex(HmacSHA256(共享密钥, 签名原文))
 * 请求头   = X-Payment-Signature: &lt;签名&gt;
 *            X-Payment-Timestamp: &lt;Unix 秒，±300s 防重放&gt;
 * </pre>
 *
 * <p><b>审计操作人</b>：校验通过后，回调的审计操作人固定为 {@link #WEBHOOK_ACTOR}
 * （{@code PAYMENT_GATEWAY}，非自然人，不可伪造），与人工操作明确区分。</p>
 */
@Component
public class PaymentWebhookVerifier {

    /** 银行回调固定审计操作人（非自然人，不可伪造）。 */
    public static final String WEBHOOK_ACTOR = "PAYMENT_GATEWAY";

    /** 重放时间窗（秒）。 */
    private static final long REPLAY_WINDOW_SECONDS = 300;

    private final String secret;

    public PaymentWebhookVerifier(@Value("${floorplan.payment.webhook-secret}") String secret) {
        this.secret = secret;
    }

    /**
     * 校验银行回调签名。失败抛 {@code PAYMENT_001}（401）。
     *
     * @param advanceNo   垫款业务单号（参与签名）
     * @param timestamp   请求头 X-Payment-Timestamp（Unix 秒）
     * @param refOrMessage 回调体中的 paymentRef 或 message（参与签名）
     * @param signature   请求头 X-Payment-Signature
     */
    public void verify(String advanceNo, String timestamp, String refOrMessage, String signature) {
        if (signature == null || signature.isBlank() || timestamp == null || timestamp.isBlank()) {
            throw new BusinessException(ErrorCode.PAYMENT_001, "缺签名或时间戳");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.PAYMENT_001, "时间戳非法");
        }
        long skew = Math.abs(System.currentTimeMillis() / 1000 - ts);
        if (skew > REPLAY_WINDOW_SECONDS) {
            throw new BusinessException(ErrorCode.PAYMENT_001, "时间戳过期（防重放）");
        }
        String message = advanceNo + "." + timestamp + "." + (refOrMessage == null ? "" : refOrMessage);
        String expected = sign(secret, message);
        if (!constantTimeEquals(expected, signature.trim())) {
            throw new BusinessException(ErrorCode.PAYMENT_001, "签名不匹配");
        }
    }

    /** 生成回调签名（供测试与渠道对接文档复用，算法必须与 {@link #verify} 一致）。 */
    public static String sign(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYS_001, "签名计算失败");
        }
    }

    /** 常量时间比较，避免时序侧信道。 */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (ba.length != bb.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < ba.length; i++) {
            result |= ba[i] ^ bb[i];
        }
        return result == 0;
    }
}
