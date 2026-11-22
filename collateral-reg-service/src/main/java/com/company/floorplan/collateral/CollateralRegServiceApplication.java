package com.company.floorplan.collateral;

import com.company.floorplan.application.service.AuthApplicationService;
import com.company.floorplan.collateral.repository.JdbcCollateralRegistrationRepository;
import com.company.floorplan.collateral.service.CollateralRegistrationService;
import com.company.floorplan.collateral.strategy.DefaultAuthoritativeSourceStrategy;
import com.company.floorplan.common.config.GlobalExceptionHandler;
import com.company.floorplan.common.config.JwtAuthInterceptor;
import com.company.floorplan.common.config.WebMvcConfig;
import com.company.floorplan.infrastructure.persistence.JdbcAuthUserRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Import;

/**
 * 担保权属权威源服务启动入口（Wave 0 骨架）。
 *
 * <p><b>本服务覆盖的限界上下文</b>：担保登记、顺位、抹消——把现在散落在
 * vehicle_units / collateral_ledger / 批次表 的"三源不一致"收敛到一个权威源。</p>
 *
 * <p><b>对外契约</b>：实现 common 的 {@code CollateralClient#getRegistration(vin)}，
 * 供 core 放款前做 B1 担保门禁。<b>ADR-Q27 已裁决</b>：门禁直读 V10 登记批次表
 * （唯一权威源），本服务的 V18 三张表只作投影与留痕，不参与判定。</p>
 */
@SpringBootApplication(scanBasePackages = "com.company.floorplan.collateral")
@Import({
        AuthApplicationService.class,       // JWT 验签（JwtAuthInterceptor 的依赖）
        JdbcAuthUserRepository.class,       // AuthApplicationService 的依赖
        JwtAuthInterceptor.class,
        WebMvcConfig.class,
        GlobalExceptionHandler.class,
        // 担保权属服务：service / repository / strategy 不加 stereotype，统一在此显式装配；
        // CollateralRegController 已用 @RestController 交给组件扫描注册，勿重复 @Import（否则映射冲突）
        JdbcCollateralRegistrationRepository.class,
        DefaultAuthoritativeSourceStrategy.class,
        CollateralRegistrationService.class
})
@EnableDiscoveryClient
public class CollateralRegServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CollateralRegServiceApplication.class, args);
    }
}
