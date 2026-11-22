package com.company.floorplan.release.controller.dto;

/**
 * 状态推进动作的可选请求体：操作人一律由服务端从令牌解析，不接受请求体自称。
 *
 * @param reason                原因说明
 * @param confirmationAuthority 确认权威（仅 confirm 动作使用）：
 *                              {@code PARTNER_RECEIPT} 接收方回执 /
 *                              {@code INTERNAL_WITNESS} 我方见证代录 /
 *                              {@code CERTIFICATE_HANDOVER} 凭证交出。
 *                              不传时默认 {@code INTERNAL_WITNESS}
 *                              （起步无外部 WMS，人工转交 + 我方代录）。
 */
public record ReleaseActionRequest(String reason, String confirmationAuthority) {
}
