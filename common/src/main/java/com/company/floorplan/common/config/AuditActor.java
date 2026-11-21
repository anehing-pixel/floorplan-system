package com.company.floorplan.common.config;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 审计操作人解析（全服务共用）：以服务端验签得到的令牌身份为准。
 *
 * <p>优先级：令牌身份（authUsername，不可伪造）→ 请求头 X-Operator-Id（无登录态时兜底）
 * → 字面量 "system"。</p>
 *
 * <p>这里合并了 web 版的完整方法集（含 resolveRoles），credit/risk 用到的
 * resolve/resolveRole 语义不变，多出的 resolveRoles 对现有服务无害。</p>
 */
public final class AuditActor {

    public static final String ATTR_AUTH_USERNAME = "authUsername";
    public static final String ATTR_AUTH_ROLE = "authRole";
    public static final String ATTR_AUTH_ROLES = "authRoles";

    private static final String HEADER_OPERATOR = "X-Operator-Id";
    private static final String FALLBACK = "system";

    private AuditActor() {
    }

    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return FALLBACK;
        }
        Object authUsername = request.getAttribute(ATTR_AUTH_USERNAME);
        if (authUsername != null && !authUsername.toString().isBlank()) {
            return authUsername.toString().trim();
        }
        String header = request.getHeader(HEADER_OPERATOR);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        return FALLBACK;
    }

    public static String resolveRole(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object role = request.getAttribute(ATTR_AUTH_ROLE);
        if (role == null || role.toString().isBlank()) {
            return null;
        }
        return role.toString().trim();
    }

    public static String resolveRoles(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object roles = request.getAttribute(ATTR_AUTH_ROLES);
        if (roles instanceof java.util.Collection<?> collection) {
            if (collection.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (Object r : collection) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(r);
            }
            return sb.toString();
        }
        return resolveRole(request);
    }
}
