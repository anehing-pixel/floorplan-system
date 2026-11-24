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
import com.company.floorplan.bank.client.FeignLoanLookupClient;
import com.company.floorplan.bank.event.PaymentMatchedPublisher;
import com.company.floorplan.bank.service.PaymentMatchApplicationService;
import com.company.floorplan.bank.service.ReleaseOrchestrator;
import com.company.floorplan.bank.store.JdbcPaymentStore;
import com.company.floorplan.common.contract.LoanQueryClient;
import com.company.floorplan.infrastructure.outbox.EventDispatcher;
import com.company.floorplan.infrastructure.outbox.JdbcOutboxPublisher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

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
        // ---- PAYMENT 回款匹配（T2-3 后半段）----
        // 新 bean 显式 @Import，类本身不加 stereotype，避免被本包的组件扫描重复注册
        FeignLoanLookupClient.class,           // 借据查询：冻结契约 LoanQueryClient（真实 Feign）
        JdbcPaymentStore.class,                // V17 三表 JDBC 实现（匹配结论不再重启即丢）
        PaymentMatchedPublisher.class,         // PAYMENT_MATCHED 的 outbox 发布器
        ReleaseOrchestrator.class,             // 解押留痕 + 回补请求登记
        PaymentMatchApplicationService.class,  // 匹配应用服务
        // 数据源端口：两个候选都导入，条件二选一
        CsvBankStatementSource.class,
        DemoBankStatementSource.class,
        // 分布式锁：两个候选都导入，条件二选一
        InMemoryDistributedLock.class,
        RedisDistributedLock.class,
        // 共用 Web/鉴权基础设施（来自 common）：拦截器 + MVC 配置 + 全局异常处理
        JwtAuthInterceptor.class,
        WebMvcConfig.class,
        GlobalExceptionHandler.class,
        // W0-6 事件地基装配：发布端 + 消费轮询器（无消费者的服务 poll() 直接返回，不发 SQL）
        JdbcOutboxPublisher.class,
        EventDispatcher.class
})
@EnableScheduling
@EnableDiscoveryClient
// 只注册回款匹配真正需要的借据契约；不要扫整个 common.contract 包——
// CollateralClient 带 fallback，在 bank 里没有对应 bean，会被 Feign 装配阶段直接拒。
@EnableFeignClients(clients = LoanQueryClient.class)
public class BankServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankServiceApplication.class, args);
    }
}
