package com.company.floorplan.web.config;

import com.company.floorplan.application.service.AuthApplicationService;
import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.domain.model.UserRole;
import com.company.floorplan.domain.service.RoleSeparationPolicy;
import com.company.floorplan.web.dto.ApiResponse;
import com.company.floorplan.web.security.RequireAdmin;
import com.company.floorplan.web.security.RequirePermission;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * JWT 认证拦截器：所有 /api/** 请求进门先验令牌（登录接口除外）。
 *
 * <p>与 {@link GlobalExceptionHandler} 的分工——
 * 异常处理器管"出门"（业务代码抛异常后统一转 JSON），
 * 拦截器管"进门"（没有合法身份的请求根本进不了业务代码）。
 * 拦截器抛出的 BusinessException 同样会被 GlobalExceptionHandler 接住，
 * 所以此处直接抛 AUTH_001 即可，无需手写响应 JSON。</p>
 *
 * <p><b>鉴权分级</b>（两道关，缺一不可）：</p>
 * <ol>
 *   <li><b>认证</b>：令牌有无、签名对不对、过期没过期 → 不过则 401 AUTH_001；</li>
 *   <li><b>授权</b>：接口标了 {@link RequireAdmin} 时，令牌里的 role 必须是 ADMIN
 *       → 不过则 403 AUTH_002。</li>
 * </ol>
 * 401 和 403 必须分清：401 是"你是谁我不知道"，403 是"我知道你是谁，但你不能做这件事"。
 * 混为一谈会让前端无法区分"重新登录"和"权限不足"两种完全不同的处置。
 *
 * <p>验签通过后：把登录名与角色放进请求属性 {@code authUsername} / {@code authRole}，
 * 若调用方没带 X-Operator-Id 审计头，用令牌身份兜底。</p>
 */
@Component
public class JwtAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthInterceptor.class);

    /**
     * 系统管理域的写权限点。
     *
     * <p>老接口上残留的 {@link RequireAdmin} 按此集合判定：
     * 持有任一管理域权限点即放行。这让"管理员能改配置、能触发作业"继续成立，
     * 同时把"管理员代行业务审批"挡在门外。</p>
     */
    private static final Permission[] SYSTEM_DOMAIN_PERMISSIONS = new Permission[]{
            Permission.CONFIG_MAINTAIN,
            Permission.JOB_TRIGGER,
            Permission.DEALER_MAINTAIN};

    private final AuthApplicationService authService;
    private final ObjectMapper objectMapper;

    public JwtAuthInterceptor(AuthApplicationService authService, ObjectMapper objectMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String header = request.getHeader("Authorization");

        if (header == null || !header.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.AUTH_001, Map.of("reason", "NO_TOKEN"));
        }

        Claims claims;
        try {
            claims = authService.verify(header.substring(7));   // 去掉 "Bearer " 前缀
        } catch (BusinessException ex) {
            // 验签失败：不要 500，明确写 401 + 统一错误结构
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json;charset=UTF-8");
            ApiResponse<Void> body = ApiResponse.fail(
                    ex.getErrorCode().getCode(), ex.getMessage(), UUID.randomUUID().toString(), ex.getDetails());
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return false;  // 拦下，不进业务代码
        }

        String username = claims.getSubject();
        String role = claims.get("role") == null ? null : String.valueOf(claims.get("role"));
        request.setAttribute("authUsername", username);
        request.setAttribute("authRole", role);
        // 角色集合与有效权限点：令牌里直接带权限点，拦截器无需回查数据库。
        // 带权限点而非只带角色，是为了避免每个请求都查一次 user_roles；
        // 代价是"角色调整"要等令牌过期才生效——这在金融系统里是可接受的
        // （紧急撤权走"禁用账号"通道，禁用后重新登录即失效）。
        Set<UserRole> roles = parseRoles(claims);
        Set<Permission> permissions = parsePermissions(claims);
        request.setAttribute("authRoles", roles);
        request.setAttribute("authPermissions", permissions);
        // 回填给 RequestLoggingFilter：过滤器在拦截器之前执行，日志写在其 finally 里，
        // 此时重新读取该属性即可拿到身份；否则参数流水只能记到 anon。
        request.setAttribute("requestLogActor", username);
        if (request.getHeader("X-Operator-Id") == null) {
            // 令牌身份兜底审计头（包装器只对本次请求生效）
            request.setAttribute("operatorId", username);
        }

        // ---- 第二关：授权（仅当接口显式声明权限要求时才拦）----
        Permission[] required = requiredPermissions(handler);
        if (required.length > 0 && !hasAllPermissions(permissions, required)) {
            log.warn("越权访问被拒：user={} roles={} uri={} required={}",
                    username, roles, request.getRequestURI(), java.util.Arrays.toString(required));
            throw new BusinessException(ErrorCode.AUTH_002, Map.of(
                    "requiredPermissions", java.util.Arrays.toString(required),
                    "currentPermissions", permissions.toString(),
                    "currentRoles", roles.toString(),
                    "uri", request.getRequestURI()));
        }

        // ---- 兼容层：老接口上的 @RequireAdmin 仍按"系统管理域写操作"拦截 ----
        if (requiresAdmin(handler) && !hasAnyPermission(permissions, SYSTEM_DOMAIN_PERMISSIONS)) {
            log.warn("越权访问被拒（管理域）：user={} roles={} uri={}", username, roles, request.getRequestURI());
            throw new BusinessException(ErrorCode.AUTH_002, Map.of(
                    "requiredScope", "SYSTEM_DOMAIN",
                    "currentRoles", roles.toString(),
                    "uri", request.getRequestURI()));
        }

        return true;   // 放行，进入业务代码
    }

    /**
     * 从令牌载荷解析角色集合。
     *
     * <p>兼容两种载荷：多角色数组 {@code roles}，与旧版单值 {@code role}。
     * 旧令牌的 {@code role=ADMIN} 会被映射为 {@link UserRole#SYSTEM_ADMIN}，
     * {@code role=USER} 映射为 {@link UserRole#READONLY_USER}。</p>
     */
    private Set<UserRole> parseRoles(Claims claims) {
        Set<UserRole> roles = new LinkedHashSet<>();
        Object raw = claims.get("roles");
        if (raw instanceof Collection<?> list) {
            for (Object item : list) {
                if (item == null) continue;
                try {
                    roles.add(UserRole.valueOf(String.valueOf(item)));
                } catch (IllegalArgumentException ignored) {
                    // 令牌里出现未知角色（枚举演进后旧令牌残留）：跳过，不放行任何权限
                    log.debug("令牌含未知角色，已忽略：{}", item);
                }
            }
        }
        if (roles.isEmpty()) {
            String legacy = claims.get("role") == null ? null : String.valueOf(claims.get("role"));
            if (legacy != null) {
                if ("ADMIN".equals(legacy)) {
                    roles.add(UserRole.SYSTEM_ADMIN);
                } else if ("USER".equals(legacy)) {
                    roles.add(UserRole.READONLY_USER);
                } else {
                    try {
                        roles.add(UserRole.valueOf(legacy));
                    } catch (IllegalArgumentException ignored) {
                        // 未知角色按无权限处理
                    }
                }
            }
        }
        return roles;
    }

    /**
     * 从令牌载荷解析有效权限点集合。
     *
     * <p>优先用令牌里直接签发的 {@code perms}；缺失时由角色推导
     * （兼容手工构造的旧令牌）。</p>
     */
    private Set<Permission> parsePermissions(Claims claims) {
        Set<Permission> permissions = new LinkedHashSet<>();
        Object raw = claims.get("perms");
        if (raw instanceof Collection<?> list) {
            for (Object item : list) {
                if (item == null) continue;
                try {
                    permissions.add(Permission.valueOf(String.valueOf(item)));
                } catch (IllegalArgumentException ignored) {
                    log.debug("令牌含未知权限点，已忽略：{}", item);
                }
            }
            return permissions;
        }
        return RoleSeparationPolicy.effectivePermissions(parseRoles(claims));
    }

    /** 是否拥有全部所需权限点。 */
    private boolean hasAllPermissions(Set<Permission> owned, Permission[] required) {
        for (Permission p : required) {
            if (!owned.contains(p)) {
                return false;
            }
        }
        return true;
    }

    /** 是否拥有任一指定权限点。 */
    private boolean hasAnyPermission(Set<Permission> owned, Permission[] candidates) {
        for (Permission p : candidates) {
            if (owned.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取接口声明的权限点。
     *
     * <p>方法级注解优先于类级注解：类上标了表示整个控制器同一权限要求；
     * 单个方法再标一次是重复声明，无副作用。</p>
     */
    private Permission[] requiredPermissions(Object handler) {
        if (handler instanceof HandlerMethod method) {
            RequirePermission ann = method.getMethodAnnotation(RequirePermission.class);
            if (ann == null) {
                ann = method.getBeanType().getAnnotation(RequirePermission.class);
            }
            return ann == null ? new Permission[0] : ann.value();
        }
        return new Permission[0];
    }

    /**
     * 判断当前处理方法是否要求 ADMIN 权限。
     *
     * <p>方法级注解优先于类级注解：类上标了 {@code @RequireAdmin} 表示整个控制器
     * 都是管理员专区；单个方法再标一次是重复声明，无副作用。</p>
     */
    private boolean requiresAdmin(Object handler) {
        if (handler instanceof HandlerMethod method) {
            return method.hasMethodAnnotation(RequireAdmin.class)
                    || method.getBeanType().isAnnotationPresent(RequireAdmin.class);
        }
        return false;
    }
}
