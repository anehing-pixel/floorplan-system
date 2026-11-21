package com.company.floorplan.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * 库存融资（Floorplan）核心系统启动入口。
 * <p>
 * 架构：模块化单体（逻辑拆、物理不拆）——domain / application / infrastructure / web。
 */
// T1-5（B1）：common 仅作为 Feign 契约依赖引入；其 config 包与 web 本地配置类同名，
// 必须排除组件扫描，否则 GlobalExceptionHandler 等 bean 冲突导致启动失败。
@SpringBootApplication
@ComponentScan(basePackages = "com.company.floorplan",
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "com\\.company\\.floorplan\\.common\\..*"))
// 扫描 common 中的 CollateralClient Feign 契约；门禁默认关闭，未起权属服务时不影响放款。
@EnableFeignClients(basePackages = "com.company.floorplan.common.contract")
public class FloorplanApplication {

    public static void main(String[] args) {
        SpringApplication.run(FloorplanApplication.class, args);
    }
}
