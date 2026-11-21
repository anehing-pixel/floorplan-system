package com.company.floorplan.release;

import com.company.floorplan.common.config.GlobalExceptionHandler;
import com.company.floorplan.common.config.JwtAuthInterceptor;
import com.company.floorplan.common.config.WebMvcConfig;
import com.company.floorplan.infrastructure.persistence.JdbcAuthUserRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Import;

/**
 * 出库指令服务启动入口（Wave 0 骨架）。
 *
 * <p><b>本服务覆盖的限界上下文</b>：放车放证指令——发出指令 → 等对方确认 → 放车 → 解抵，
 * 是跨机构异步协同的独立状态机。从 core 剥离的理由：不能让账本同步事务被外部响应时间拖住。</p>
 *
 * <p>设计稿已完成（《放车放证指令设计》：六张表 + PENDING_RELEASE 状态），release agent 负责代码落地。</p>
 */
@SpringBootApplication(scanBasePackages = "com.company.floorplan.release")
@Import({
        JdbcAuthUserRepository.class,
        JwtAuthInterceptor.class,
        WebMvcConfig.class,
        GlobalExceptionHandler.class
})
@EnableDiscoveryClient
public class ReleaseServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReleaseServiceApplication.class, args);
    }
}
