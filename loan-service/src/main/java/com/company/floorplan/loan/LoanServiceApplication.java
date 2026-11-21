package com.company.floorplan.loan;

import com.company.floorplan.common.config.GlobalExceptionHandler;
import com.company.floorplan.common.config.JwtAuthInterceptor;
import com.company.floorplan.common.config.WebMvcConfig;
import com.company.floorplan.infrastructure.persistence.JdbcAuthUserRepository;
import com.company.floorplan.loan.repository.JdbcLoanRepository;
import com.company.floorplan.loan.service.LoanApplicationService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Import;

/**
 * 借据服务启动入口（Wave 0 骨架）。
 *
 * <p><b>装配策略</b>：只扫本服务包；application/infrastructure 的 bean 用 {@link Import} 精确注册。
 *  loan agent 在此追加借据/DPD/画像所需的 ApplicationService 与 JdbcRepository。</p>
 *
 * <p><b>本服务覆盖的限界上下文</b>：借据（loans）、还款计划、DPD 计算、经销商贷后画像。
 *  这是"算风险"的一层，区别于 risk-service 的"发现风险"（预警/工单）。</p>
 */
@SpringBootApplication(scanBasePackages = "com.company.floorplan.loan")
@Import({
        JdbcAuthUserRepository.class,   // JWT 验签所需
        JwtAuthInterceptor.class,
        WebMvcConfig.class,
        GlobalExceptionHandler.class,
        // 借据服务新 bean（T2-2）：显式装配，便于主 agent 合并时核对装配面
        JdbcLoanRepository.class,
        LoanApplicationService.class
})
@EnableDiscoveryClient
public class LoanServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoanServiceApplication.class, args);
    }
}
