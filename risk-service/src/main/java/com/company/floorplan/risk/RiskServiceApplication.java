package com.company.floorplan.risk;

import com.company.floorplan.application.service.AuthApplicationService;
import com.company.floorplan.application.service.CollateralLedgerApplicationService;
import com.company.floorplan.application.service.DisposalApplicationService;
import com.company.floorplan.application.service.InventoryAuditApplicationService;
import com.company.floorplan.application.service.LienCheckApplicationService;
import com.company.floorplan.common.config.GlobalExceptionHandler;
import com.company.floorplan.common.config.JwtAuthInterceptor;
import com.company.floorplan.common.config.WebMvcConfig;
import com.company.floorplan.infrastructure.persistence.JdbcAuthUserRepository;
import com.company.floorplan.infrastructure.persistence.JdbcBankAccountRepository;
import com.company.floorplan.infrastructure.persistence.JdbcBusinessCalendar;
import com.company.floorplan.infrastructure.persistence.JdbcCollateralEvidenceRepository;
import com.company.floorplan.infrastructure.persistence.JdbcCollateralLedgerRepository;
import com.company.floorplan.infrastructure.persistence.JdbcCollateralRegistrationBatchRepository;
import com.company.floorplan.infrastructure.persistence.JdbcCreditLineRepository;
import com.company.floorplan.infrastructure.persistence.JdbcDisposalCaseRepository;
import com.company.floorplan.infrastructure.persistence.JdbcFinancingLienCheckRepository;
import com.company.floorplan.infrastructure.persistence.JdbcInterestLedgerRepository;
import com.company.floorplan.infrastructure.persistence.JdbcSupportRepository;
import com.company.floorplan.infrastructure.persistence.JdbcVehicleUnitRepository;
import com.company.floorplan.infrastructure.outbox.EventDispatcher;
import com.company.floorplan.infrastructure.outbox.JdbcOutboxPublisher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 风控服务启动入口（真正拆分版）。
 *
 * <p><b>装配策略</b>：只扫本服务包 {@code com.company.floorplan.risk}，
 * application/infrastructure 层的 bean 用 {@link Import} 按「类」精确注册。</p>
 *
 * <p><b>可安全拆出的理由</b>：本服务的写操作落在 alerts / disposal_cases / collateral_* 台账
 * 与车辆状态字段（终态流转），不碰额度占用与垫款本金——
 * 后两者仍由核心账务的本地事务 + 行锁守住，留在 web。</p>
 *
 * <p><b>链式依赖（漏一个运行时就报 UnsatisfiedDependency）</b>：</p>
 * <ul>
 *   <li>{@link InventoryAuditApplicationService} 与 {@link DisposalApplicationService}
 *       都依赖 {@link CollateralLedgerApplicationService}，三者必须一起装；</li>
 *   <li>{@link CollateralLedgerApplicationService} 依赖
 *       {@link JdbcBankAccountRepository}（账户引用码校验）；</li>
 *   <li>{@link DisposalApplicationService} 自 T1-1 起还依赖
 *       {@link JdbcCreditLineRepository}（处置开单时行锁冻结额度），须一并装配；</li>
 *   <li>{@link JdbcSupportRepository} 依赖 {@link JdbcBusinessCalendar}；</li>
 *   <li>{@link AuthApplicationService} 依赖 {@link JdbcAuthUserRepository}（JWT 验签）。</li>
 * </ul>
 *
 * <p>账户明文加密（AccountCryptoService）由 SupplierApplicationService 使用，
 * 本服务只按引用码读写账户关联，不涉及明文账号，故不装它。</p>
 */
@SpringBootApplication(scanBasePackages = "com.company.floorplan.risk")
@Import({
        // 应用服务
        InventoryAuditApplicationService.class,
        DisposalApplicationService.class,
        CollateralLedgerApplicationService.class,
        LienCheckApplicationService.class,
        AuthApplicationService.class,                       // JWT 验签
        // 仓储
        JdbcVehicleUnitRepository.class,
        JdbcDisposalCaseRepository.class,
        JdbcInterestLedgerRepository.class,
        JdbcFinancingLienCheckRepository.class,
        JdbcCollateralLedgerRepository.class,
        JdbcCollateralRegistrationBatchRepository.class,
        JdbcCollateralEvidenceRepository.class,
        JdbcBankAccountRepository.class,                    // CollateralLedgerService 的依赖
        JdbcCreditLineRepository.class,                    // T1-1: DisposalApplicationService 新增的额度行锁依赖
        JdbcSupportRepository.class,
        JdbcBusinessCalendar.class,                         // JdbcSupportRepository 的依赖
        JdbcAuthUserRepository.class,                        // AuthApplicationService 的依赖
        // 共用 Web/鉴权基础设施（来自 common）
        JwtAuthInterceptor.class,
        WebMvcConfig.class,
        GlobalExceptionHandler.class,
        // W0-6 事件地基装配：发布端 + 消费轮询器（无消费者的服务 poll() 直接返回，不发 SQL）
        JdbcOutboxPublisher.class,
        EventDispatcher.class
})
@EnableScheduling
@EnableDiscoveryClient
public class RiskServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RiskServiceApplication.class, args);
    }
}
