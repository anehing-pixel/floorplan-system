package com.company.floorplan.auth.controller;

import com.company.floorplan.application.service.AuthApplicationService;
import com.company.floorplan.application.service.AuthApplicationService.AuthContext;
import com.company.floorplan.common.dto.ApiResponse;
import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 登录认证接口（从 web 模块迁入 auth-service）。
 *
 * <p>响应结构与原 web 版完全一致，前端无需改动。登录失败同样走全局异常处理器
 * （auth-service 自带一份），返回 HTTP 401 + AUTH_004，用户名错和密码错同一结果（防枚举）。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthApplicationService service;

    public AuthController(AuthApplicationService service) {
        this.service = service;
    }

    /** 登录：POST /api/auth/login，{"username":"admin","password":"admin123"} */
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest req) {
        return ApiResponse.ok(service.login(req.username, req.password), UUID.randomUUID().toString());
    }

    /**
     * 退出登录 / 撤销该账号全部已签发令牌（安全基线，2026-11-24）。
     *
     * <p><b>为什么这里手动验令牌</b>：auth-service 的启动类刻意不装配
     * {@code JwtAuthInterceptor}（登录接口必须公开），所以本端点自己从 Authorization
     * 取 Bearer 令牌并调用 {@code service.verify()}。verify 会同时校验签名、过期、
     * 账号启用状态与 token_version；通过后把该用户的 token_version +1，
     * 所有已签发令牌在下一次请求时立即失效。</p>
     *
     * <p><b>语义</b>：按账号撤销（不是按设备）。多设备同时在线时会被一起踢下线；
     * 需要按设备撤销时再引入 jti + 黑名单表。</p>
     */
    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.AUTH_001, Map.of("reason", "NO_TOKEN"));
        }
        AuthContext auth = service.verify(header.substring(7));
        service.revokeAllTokens(auth.username());
        return ApiResponse.ok(Map.of("revoked", true, "username", auth.username()),
                UUID.randomUUID().toString());
    }

    public static class LoginRequest {
        public String username;
        public String password;
    }
}
