package com.company.floorplan.common.config;

import com.company.floorplan.application.service.AuthApplicationService;
import com.company.floorplan.application.service.AuthApplicationService.AuthContext;
import com.company.floorplan.common.dto.ApiResponse;
import com.company.floorplan.common.security.RequireAdmin;
import com.company.floorplan.common.security.RequirePermission;
import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.domain.model.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * JWT 认证拦截器（全服务共用，从 web 版迁入）。
 *
 * <p>两道关：认证（令牌签名/过期/撤销版本/账号启用状态）→ 授权（接口声明的权限点）。
 * 验签通过把操作人放入请求属性，供审计兜底。</p>
 *
 * <p><b>权限来源</b>（2026-11-24 起）：{@code AuthApplicationService.verify()} 每次请求从
 * {@code user_roles} 现算角色与有效权限，拦截器只使用返回的 {@link AuthContext}，
 * <b>不再信任令牌里的 {@code perms}/{@code roles} 快照</b>。这样角色调整对已签发令牌立即生效，
 * 退出登录（token_version +1）与禁用账号也会在下一次请求即失效。</p>
 *
 * <p>依赖 {@code AuthApplicationService.verify()} 验签，故 common 依赖 application 层；
 * 密钥来自 {@code floorplan.auth.secret}，各服务必须与 auth-service 一致。
 * 各服务通过启动类 {@code @Import} 显式装配本拦截器与 WebMvcConfig——
 * auth-service 的登录接口公开，故不导入这两个类。</p>
 */
@Component
public class JwtAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthInterceptor.class);

    /** 老接口 @RequireAdmin 兼容层：持有任一管理域权限点即放行。 */
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

        AuthContext auth;
        try {
            auth = authService.verify(header.substring(7));
        } catch (BusinessException ex) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json;charset=UTF-8");
            ApiResponse<Void> body = ApiResponse.fail(
                    ex.getErrorCode().getCode(), ex.getMessage(), UUID.randomUUID().toString(), ex.getDetails());
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return false;
        }

        String username = auth.username();
        String role = auth.primaryRole();
        request.setAttribute("authUsername", username);
        request.setAttribute("authRole", role);
        Set<UserRole> roles = auth.roles();
        Set<Permission> permissions = auth.permissions();
        request.setAttribute("authRoles", roles);
        request.setAttribute("authPermissions", permissions);
        request.setAttribute("requestLogActor", username);
        if (request.getHeader("X-Operator-Id") == null) {
            request.setAttribute("operatorId", username);
        }

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

        if (requiresAdmin(handler) && !hasAnyPermission(permissions, SYSTEM_DOMAIN_PERMISSIONS)) {
            log.warn("越权访问被拒（管理域）：user={} roles={} uri={}", username, roles, request.getRequestURI());
            throw new BusinessException(ErrorCode.AUTH_002, Map.of(
                    "requiredScope", "SYSTEM_DOMAIN",
                    "currentRoles", roles.toString(),
                    "uri", request.getRequestURI()));
        }

        return true;
    }

    private boolean hasAllPermissions(Set<Permission> owned, Permission[] required) {
        for (Permission p : required) {
            if (!owned.contains(p)) return false;
        }
        return true;
    }

    private boolean hasAnyPermission(Set<Permission> owned, Permission[] candidates) {
        for (Permission p : candidates) {
            if (owned.contains(p)) return true;
        }
        return false;
    }

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

    private boolean requiresAdmin(Object handler) {
        if (handler instanceof HandlerMethod method) {
            return method.hasMethodAnnotation(RequireAdmin.class)
                    || method.getBeanType().isAnnotationPresent(RequireAdmin.class);
        }
        return false;
    }
}
