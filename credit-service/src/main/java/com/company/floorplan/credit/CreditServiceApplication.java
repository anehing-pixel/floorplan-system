package com.company.floorplan.credit;

import com.company.floorplan.application.service.AnnualReviewApplicationService;
import com.company.floorplan.application.service.AuthApplicationService;
import com.company.floorplan.application.service.CreditApplicationService;
import com.company.floorplan.application.service.DealerApplicationService;
import com.company.floorplan.application.service.RatingConfigApplicationService;
import com.company.floorplan.common.config.GlobalExceptionHandler;
import com.company.floorplan.common.config.JwtAuthInterceptor;
import com.company.floorplan.common.config.WebMvcConfig;
import com.company.floorplan.infrastructure.persistence.JdbcAnnualReviewRepository;
import com.company.floorplan.infrastructure.persistence.JdbcAuthUserRepository;
import com.company.floorplan.infrastructure.persistence.JdbcBusinessCalendar;
import com.company.floorplan.infrastructure.persistence.JdbcCreditLineRepository;
import com.company.floorplan.infrastructure.persistence.JdbcDealerRepository;
import com.company.floorplan.infrastructure.persistence.JdbcRatingConfigRepository;
import com.company.floorplan.infrastructure.persistence.JdbcSupportRepository;
import com.company.floorplan.infrastructure.persistence.JdbcVehicleUnitRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Import;

/**
 * 授信管理服务启动入口（真正拆分版）。
 *
 * <p><b>装配策略</b>：只扫本服务自己的包 {@code com.company.floorplan.credit}，
 * application/infrastructure 层的 bean 用 {@link Import} 按「类」精确注册，不扫全包。</p>
 *
 * <p><b>本服务覆盖的限界上下文</b>：经销商主数据、核定额度、评级口径、额度年审。
 * 注意"额度的占用/释放"不在这里——它由垫款/结清用例在行锁下完成，
 * 这正是核心账务最强的正确性基石，拆服务时不能把它挪走。</p>
 *
 * <p><b>为什么还要装 AuthApplicationService + JdbcAuthUserRepository</b>：
 * credit 的写接口带 {@code @RequirePermission} 权限点，网关接管鉴权前必须自带验签能力。</p>
 *
 * <p><b>两个易漏的依赖</b>（bank-service 踩过同款坑）：</p>
 * <ul>
 *   <li>{@link JdbcSupportRepository} 依赖 {@link JdbcBusinessCalendar}，两者都要装；</li>
 *   <li>{@link DealerApplicationService} 依赖 {@link JdbcVehicleUnitRepository}
 *       （删除护栏 + 名下车辆清单），同库阶段直接引用，分库后要改成跨服务查询。</li>
 * </ul>
 */
@SpringBootApplication(scanBasePackages = "com.company.floorplan.credit")
@Import({
        // 应用服务
        CreditApplicationService.class,
        DealerApplicationService.class,
        RatingConfigApplicationService.class,
        AnnualReviewApplicationService.class,
        AuthApplicationService.class,              // JWT 验签（鉴权链）
        // 仓储
        JdbcCreditLineRepository.class,
        JdbcDealerRepository.class,
        JdbcRatingConfigRepository.class,
        JdbcAnnualReviewRepository.class,
        JdbcVehicleUnitRepository.class,           // DealerApplicationService 的依赖
        JdbcSupportRepository.class,
        JdbcBusinessCalendar.class,                // JdbcSupportRepository 的依赖
        JdbcAuthUserRepository.class,              // AuthApplicationService 的依赖
        // 共用 Web/鉴权基础设施（来自 common）
        JwtAuthInterceptor.class,
        WebMvcConfig.class,
        GlobalExceptionHandler.class
})
@EnableDiscoveryClient
public class CreditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CreditServiceApplication.class, args);
    }
}
