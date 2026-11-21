package com.company.floorplan.auth;

import com.company.floorplan.application.service.AuthApplicationService;
import com.company.floorplan.common.config.GlobalExceptionHandler;
import com.company.floorplan.infrastructure.persistence.JdbcAuthUserRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Import;

/**
 * 认证服务启动入口（真正拆分版）。
 *
 * <p><b>与脚手架版的区别</b>：脚手架用 {@code scanBasePackages = "com.company.floorplan"}
 * 扫全包，把 application/infrastructure 里所有 bean 都装进来，等于整套系统又跑了一遍。
 * 这里改成只扫本服务自己的包，再用 {@link Import} 精确装配认证所需的两个 bean：</p>
 * <ul>
 *   <li>{@link AuthApplicationService} —— 登录校验 + JWT 签发/验签（application 层）</li>
 *   <li>{@link JdbcAuthUserRepository} —— 用户仓储 JDBC 实现（infrastructure 层）</li>
 * </ul>
 * 其余十几个 application service / repository 一律不加载 —— 这才是真正隔离。
 *
 * <p><b>为什么不用 scanBasePackages 指到 application.service 包</b>：那个包里挤着十几个
 * 别的 Service，扫它等于把整套系统又装回来。{@code @Import} 按「类」精确注册，
 * 不受包结构影响，是本场景唯一干净的做法。</p>
 */
@SpringBootApplication(scanBasePackages = "com.company.floorplan.auth")
@Import({
        AuthApplicationService.class,
        JdbcAuthUserRepository.class,
        // 全局异常处理来自 common；登录接口公开，故不装 JwtAuthInterceptor / WebMvcConfig
        GlobalExceptionHandler.class
})
@EnableDiscoveryClient
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
