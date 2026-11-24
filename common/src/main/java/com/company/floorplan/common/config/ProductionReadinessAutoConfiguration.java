package com.company.floorplan.common.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 生产就绪检查的自动配置。
 *
 * <p>用 Spring Boot 3 的自动配置导入（{@code META-INF/spring/...AutoConfiguration.imports}），
 * 而不是 {@code @Component} + 组件扫描：web 单体的扫描显式排除了
 * {@code com.company.floorplan.common.*}，其余服务只扫自己的包——两条路都扫不到这里。
 * 自动配置是"库在 classpath 就生效"，十个服务与 web 一次覆盖。</p>
 */
@AutoConfiguration
public class ProductionReadinessAutoConfiguration {

    @Bean
    public ProductionReadinessCheck productionReadinessCheck(Environment environment) {
        return new ProductionReadinessCheck(environment);
    }
}
