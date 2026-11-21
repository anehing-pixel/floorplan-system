package com.company.floorplan.auth.controller;

import com.company.floorplan.application.service.AuthApplicationService;
import com.company.floorplan.common.dto.ApiResponse;
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

    public static class LoginRequest {
        public String username;
        public String password;
    }
}
