package com.company.floorplan.recon;

import com.company.floorplan.common.config.GlobalExceptionHandler;
import com.company.floorplan.common.config.JwtAuthInterceptor;
import com.company.floorplan.common.config.WebMvcConfig;
import com.company.floorplan.infrastructure.persistence.JdbcAuthUserRepository;
import com.company.floorplan.recon.controller.ReconController;
import com.company.floorplan.recon.repository.JdbcReconRepository;
import com.company.floorplan.recon.rule.ReconRuleConfig;
import com.company.floorplan.recon.service.ReconApplicationService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Import;

/**
 * 对账清算服务启动入口（Wave 0 骨架）。
 *
 * <p><b>本服务覆盖的限界上下文</b>：账本对拍——8 条对拍规则 + recon_diffs 差异表 + 差异报告。
 * 纯只读账本表，与写链路解耦，是所有新服务里依赖最弱、最适合作为并行"试验田"的一个。</p>
 */
@SpringBootApplication(scanBasePackages = "com.company.floorplan.recon")
@Import({
        JdbcAuthUserRepository.class,
        JwtAuthInterceptor.class,
        WebMvcConfig.class,
        GlobalExceptionHandler.class,
        // ---- recon 自有 bean：显式装配，不扫 infrastructure 全包 ----
        JdbcReconRepository.class,
        ReconRuleConfig.class,
        ReconApplicationService.class,
        ReconController.class
        // NamedParameterJdbcTemplate 由 Spring Boot 的 JdbcTemplateAutoConfiguration
        // 基于现有 DataSource 自动装配（spring-boot-starter-jdbc 由 infrastructure 传递引入），
        // 无需在此重复声明——手动 new 会绕过 Hikari 数据源与自动事务管理。
})
@EnableDiscoveryClient
public class ReconServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReconServiceApplication.class, args);
    }
}
