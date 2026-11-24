package com.company.floorplan.bank.config;

import feign.RequestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * bank-service 的 Feign 认证透传。
 *
 * <p><b>为什么需要它</b>：回款匹配调用 loan-service 的 by-vin 端点，而该端点要求合法 JWT。
 * Feign 默认不带任何认证头，下游 {@code JwtAuthInterceptor} 会 401 —— 匹配会"永远是没借据"，
 * 这正是本项目在担保门禁上踩过的 B-9 坑（common 侧 Feign 无认证头 → 门禁永远走 fallback）。</p>
 *
 * <p><b>只透传，不伪造</b>：回款匹配由 HTTP 请求触发，当前请求本来就带着操作人的令牌；
 * 定时作业等无请求上下文的线程直接跳过，让下游按 401 拒绝，而不是拿一个系统身份越权。</p>
 */
@Configuration
public class BankFeignConfig {

    @Bean
    public RequestInterceptor bankJwtRelay() {
        return template -> {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (!(attrs instanceof ServletRequestAttributes servletAttrs)) {
                return;
            }
            HttpServletRequest request = servletAttrs.getRequest();
            String authorization = request.getHeader("Authorization");
            if (authorization != null && !authorization.isBlank()) {
                template.header("Authorization", authorization);
            }
            String operator = request.getHeader("X-Operator-Id");
            if (operator != null && !operator.isBlank()) {
                template.header("X-Operator-Id", operator);
            }
        };
    }
}
