package com.company.floorplan.bank;

import com.company.floorplan.application.service.AuthApplicationService;
import com.company.floorplan.application.service.BankFetchApplicationService;
import com.company.floorplan.common.config.GlobalExceptionHandler;
import com.company.floorplan.common.config.JwtAuthInterceptor;
import com.company.floorplan.common.config.WebMvcConfig;
import com.company.floorplan.infrastructure.integration.CsvBankStatementSource;
import com.company.floorplan.infrastructure.integration.DemoBankStatementSource;
import com.company.floorplan.infrastructure.lock.InMemoryDistributedLock;
import com.company.floorplan.infrastructure.lock.RedisDistributedLock;
import com.company.floorplan.infrastructure.persistence.JdbcAuthUserRepository;
import com.company.floorplan.infrastructure.persistence.JdbcBankFetchBatchRepository;
import com.company.floorplan.infrastructure.persistence.JdbcBusinessCalendar;
import com.company.floorplan.infrastructure.persistence.JdbcPaymentTransactionRepository;
import com.company.floorplan.infrastructure.persistence.JdbcSupportRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Import;

/**
 * 银行采集服务启动入口（真正拆分版）。
 *
 * <p><b>装配策略与 auth-service 一致</b>：只扫本服务自己的包
 * ({@code com.company.floorplan.bank})，application/infrastructure 层的 bean 用 {@link Import}
 * 按「类」精确注册，不扫全包——否则 23 个 application service / 29 个仓储会全部装进来。</p>
 *
 * <p><b>与 auth-service 不同的地方：这里有「二选一」的条件实现</b>。
 * 以下两组接口各有两个实现，且都带 {@code @ConditionalOnProperty}：
 * <ul>
 *   <li>{@code BankStatementSource}：{@link CsvBankStatementSource} / {@link DemoBankStatementSource}
 *       —— 由 {@code floorplan.bank-fetch.source} 选；</li>
 *   <li>{@code DistributedLock}：{@link InMemoryDistributedLock} / {@link RedisDistributedLock}
 *       —— 由 {@code floorplan.lock.provider} 选。</li>
 * </ul>
 * 因此两个候选都要 {@code @Import} 进来，让 {@code @ConditionalOnProperty} 在注册阶段
 * 按配置只留下一个。<b>只导入一个是错的</b>——那样切换配置就不生效了，等于把条件装配写死。</p>
 *
 * <p>{@link AuthApplicationService} 与 {@link JdbcAuthUserRepository} 是为了 JWT 鉴权：
 * bank-fetch 端点带 {@code @RequirePermission(JOB_TRIGGER)}，
 * 独立后必须自带验签能力（网关尚未接管鉴权前）。</p>
 */
@SpringBootApplication(scanBasePackages = "com.company.floorplan.bank")
@Import({
        // 应用服务
        BankFetchApplicationService.class,
        AuthApplicationService.class,          // JWT 验签（鉴权链）
        // 仓储
        JdbcPaymentTransactionRepository.class,
        JdbcBankFetchBatchRepository.class,
        JdbcSupportRepository.class,
        JdbcBusinessCalendar.class,            // JdbcSupportRepository 的依赖
        JdbcAuthUserRepository.class,          // AuthApplicationService 的依赖
        // 数据源端口：两个候选都导入，条件二选一
        CsvBankStatementSource.class,
        DemoBankStatementSource.class,
        // 分布式锁：两个候选都导入，条件二选一
        InMemoryDistributedLock.class,
        RedisDistributedLock.class,
        // 共用 Web/鉴权基础设施（来自 common）：拦截器 + MVC 配置 + 全局异常处理
        JwtAuthInterceptor.class,
        WebMvcConfig.class,
        GlobalExceptionHandler.class
})
@EnableDiscoveryClient
public class BankServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankServiceApplication.class, args);
    }
}
