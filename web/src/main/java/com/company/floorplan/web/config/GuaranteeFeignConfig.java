package com.company.floorplan.web.config;

import com.company.floorplan.common.contract.CollateralClient;
import feign.RequestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * T1-5（B1）：CollateralClient 契约的 Feign 装配。
 *
 * <p>契约 {@code @FeignClient(fallback = ...)} 要求 fallback 类在 Spring 上下文中可注入，
 * 否则 Feign 构建代理时会报 bean 缺失。契约在 common 中为只读，故在此注册。
 * Feign 代理 bean 由 {@code @EnableFeignClients} 以服务名注册。</p>
 *
 * <p><b>B-9（CORE_VERIFIER_REPORT）</b>：跨服务 Feign 调用默认不带任何认证头，
 * 下游 collateral-reg-service 的 {@code JwtAuthInterceptor} 会以 401 拦截，
 * 结果 B1 担保门禁永远只能走 fallback（fail-closed 拒付），门禁形同虚设。
 * 这里补一个 {@link RequestInterceptor}，把当前请求的 JWT 与操作人透传下去。</p>
 *
 * <p>注意：门禁开关 {@code floorplan.core.guarantee-gate.enabled} 默认 false，
 * 关闭时根本不会发起 Feign 调用，因此本拦截器不改变现有本地冒烟行为；
 * 仅当门禁开启且确有 HTTP 请求上下文时才生效。批处理线程无请求上下文时
 * 直接跳过，不伪造身份。</p>
 */
@Configuration
public class GuaranteeFeignConfig {

    @Bean
    public CollateralClient.Fallback collateralClientFallback() {
        return new CollateralClient.Fallback();
    }

    /** B-9：透传当前请求的 Bearer JWT 与操作人，供下游服务做同一套鉴权。 */
    @Bean
    public RequestInterceptor collateralJwtRelay() {
        return template -> {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (!(attrs instanceof ServletRequestAttributes servletAttrs)) {
                return; // 无 HTTP 请求上下文（定时作业等）：不注入，也不伪造身份
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
