package com.company.floorplan.bank.client;

import com.company.floorplan.common.contract.CreditLineClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

/**
 * 授信侧只读查询的日志存根。
 *
 * <p>{@link CreditLineClient} 是 Wave 0 冻结的 Feign 契约，只提供 {@code getAvailable}——
 * 额度写入仍在授信侧行锁事务内（接口 Javadoc 明确），不会因跨服务调用破坏一致性。
 * 因此这里只用它取"回补前可用额度"快照用于审计，绝不假装完成了额度写入。</p>
 *
 * <p>TODO(Wave2 集成)：本类替换为 Feign 实现（启用 @EnableFeignClients 后由 Spring 提供），
 * 降级语义保持 fail-closed（返回 0），单测继续用手写 fake 注入 ReleaseOrchestrator。</p>
 */
public class LoggingCreditLineClient implements CreditLineClient {

    private static final Logger log = LoggerFactory.getLogger(LoggingCreditLineClient.class);

    @Override
    public BigDecimal getAvailable(Long dealerId) {
        log.warn("TODO(Wave2) 授信侧只读查询未接线，按降级返回 0 快照：dealerId={}", dealerId);
        return BigDecimal.ZERO;
    }
}
