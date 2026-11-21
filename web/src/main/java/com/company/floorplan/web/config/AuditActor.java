package com.company.floorplan.web.config;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 审计操作人解析：统一决定"这次操作是谁做的"。
 *
 * <p><b>为什么需要它（2026-10-26 修复的审计链断裂）</b></p>
 * <p>此前各 Controller 直接取请求头 {@code X-Operator-Id} 作为审计操作人：
 * <pre>
 * &#64;RequestHeader(value = "X-Operator-Id", required = false) String actor
 * </pre>
 * 这个头是<b>客户端可任意伪造</b>的——请求方写什么，审计表就记什么。
 * 实测库里 {@code audit_events.actor} 存的是 {@code "1"}（冒烟脚本传的假值），
 * 而不是真实登录人。JWT 拦截器明明已经验签并把登录名放进
 * {@code authUsername} 请求属性，却<b>只写了没人读</b>。</p>
 *
 * <p>金融系统的审计表要能回答"谁在什么时点做了这个动作"。若操作人来自可伪造的请求头，
 * 整条审计链就形同虚设——责任无法追溯到具体自然人。</p>
 *
 * <p><b>优先级</b>（从高到低）：</p>
 * <ol>
 *   <li><b>令牌身份</b>（{@code authUsername}）——服务端验签得来，不可伪造，<b>唯一可信来源</b></li>
 *   <li>请求头 {@code X-Operator-Id}——仅在无令牌上下文时兜底（如定时任务、内部调用）</li>
 *   <li>字面量 {@code "system"}——连兜底都没有时</li>
 * </ol>
 *
 * <p><b>为什么不直接删掉请求头支持</b>：定时作业（{@code JobController}）与部分内部调用
 * 没有登录态，仍需要一个标识来区分"人操作"与"系统操作"。保留兜底，
 * 但只要有令牌就一律以令牌身份为准。</p>
 */
public final class AuditActor {

    /** 拦截器验签后写入请求属性的登录名。 */
    public static final String ATTR_AUTH_USERNAME = "authUsername";
    /** 拦截器验签后写入请求属性的主角色。 */
    public static final String ATTR_AUTH_ROLE = "authRole";
    /** 拦截器验签后写入请求属性的全部角色集合。 */
    public static final String ATTR_AUTH_ROLES = "authRoles";

    private static final String HEADER_OPERATOR = "X-Operator-Id";
    private static final String FALLBACK = "system";

    private AuditActor() {
    }

    /**
     * 解析本次请求的审计操作人。
     *
     * @param request 当前请求；可为 null（单元测试或非 HTTP 场景）
     * @return 可信的操作人标识；绝不返回 null
     */
    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return FALLBACK;
        }

        // 1) 首选：服务端验签得到的登录身份（不可伪造）
        Object authUsername = request.getAttribute(ATTR_AUTH_USERNAME);
        if (authUsername != null && !authUsername.toString().isBlank()) {
            return authUsername.toString().trim();
        }

        // 2) 兜底：请求头（定时任务等无登录态场景）
        String header = request.getHeader(HEADER_OPERATOR);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }

        // 3) 都没有：系统操作
        return FALLBACK;
    }

    /**
     * 解析本次请求的审计角色，用于写入 {@code audit_events.actor_role}。
     *
     * <p>一人多岗时，主角色是"这个人在组织里的身份"，兼岗是"额外承担的职责"。
     * 审计表记录主角色即可回答"谁做的"；若要查"以哪个岗做的"，
     * 靠 {@code actor} 关联用户表查当时的角色清单。</p>
     *
     * @param request 当前请求；可为 null
     * @return 角色标识（如 SYSTEM_ADMIN / BUSINESS_OPERATOR）；取不到时返回 null
     */
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

    /**
     * 解析本次请求的全部角色（逗号分隔），用于需要区分兼岗场景的审计写入。
     *
     * @param request 当前请求；可为 null
     * @return 角色代码串；取不到时返回 null
     */
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
