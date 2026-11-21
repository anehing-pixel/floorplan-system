package com.company.floorplan.web.config;

import com.company.floorplan.common.contract.CollateralClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T1-5（B1）：注册 CollateralClient 契约的 fallback bean。
 *
 * <p>契约 {@code @FeignClient(fallback = ...)} 要求 fallback 类在 Spring 上下文中可注入，
 * 否则 Feign 构建代理时会报 bean 缺失。契约在 common 中为只读，故在此注册。
 * Feign 代理 bean 由 @EnableFeignClients 以服务名注册，注入时用 @Qualifier 区分。</p>
 */
@Configuration
public class GuaranteeFeignConfig {

    @Bean
    public CollateralClient.Fallback collateralClientFallback() {
        return new CollateralClient.Fallback();
    }
}
