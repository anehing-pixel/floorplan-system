package com.company.floorplan.release;

import com.company.floorplan.application.service.AuthApplicationService;
import com.company.floorplan.common.config.GlobalExceptionHandler;
import com.company.floorplan.common.config.JwtAuthInterceptor;
import com.company.floorplan.common.config.WebMvcConfig;
import com.company.floorplan.infrastructure.persistence.JdbcAuthUserRepository;
import com.company.floorplan.release.channel.ManualReleaseChannel;
import com.company.floorplan.release.repository.JdbcReleaseOrderRepository;
import com.company.floorplan.release.service.ReleaseApplicationService;
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
 * <p>T2-9 已落地：V19 六表 + 领域模型 + 状态机 + 下发接缝 + 仓储/服务/Controller。
 * V24 起按 Q9/Q10 建议口径收敛：接收方按释放项分线、起步 MANUAL 人工转交 + 我方见证代录回执。</p>
 */
@SpringBootApplication(scanBasePackages = "com.company.floorplan.release")
@Import({
        AuthApplicationService.class,       // JWT 验签（JwtAuthInterceptor 的依赖）
        JdbcAuthUserRepository.class,       // AuthApplicationService 的依赖
        JwtAuthInterceptor.class,
        WebMvcConfig.class,
        GlobalExceptionHandler.class,
        // T2-9 新增 bean：Controller 带 @RestController 走组件扫描，这里只装配无注解的三类
        ManualReleaseChannel.class,         // Q10 起步口径：人工转交，替换原日志占位实现
        JdbcReleaseOrderRepository.class,
        ReleaseApplicationService.class
})
@EnableDiscoveryClient
public class ReleaseServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReleaseServiceApplication.class, args);
    }
}
