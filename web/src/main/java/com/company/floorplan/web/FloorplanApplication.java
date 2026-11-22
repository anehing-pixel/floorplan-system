package com.company.floorplan.web;

import com.company.floorplan.infrastructure.outbox.EventDispatcher;
import com.company.floorplan.infrastructure.outbox.JdbcOutboxPublisher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

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
// W0-6 事件地基装配。outbox 两个 bean 刻意<b>不带</b> @Component/@Repository 注解，只用 @Import 注册：
// web 的 @ComponentScan 覆盖整个 com.company.floorplan，若类上再有组件注解，
// "扫描 + @Import" 会注册出两个同类型 bean，按类型注入直接 NoUniqueBeanDefinitionException；
// 其余 8 个服务只扫自身包，反过来必须靠 @Import 才拿得到。统一走 @Import 才能两边都只有一份。
@Import({JdbcOutboxPublisher.class, EventDispatcher.class})
// core 是 LOAN_CREATED 的生产方（W2-CORE 接），也是 COLLATERAL_CHANGED 的规划消费方，需要调度线程。
@EnableScheduling
public class FloorplanApplication {

    public static void main(String[] args) {
        SpringApplication.run(FloorplanApplication.class, args);
    }
}
