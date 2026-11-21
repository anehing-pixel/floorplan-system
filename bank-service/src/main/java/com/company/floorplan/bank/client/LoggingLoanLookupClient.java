package com.company.floorplan.bank.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 借据查询的默认存根：只记日志，不假装有数据。
 *
 * <p><b>为什么返回 empty 而不是造一笔假借据</b>：宁可让流水挂起待人工，
 * 也不能凭空匹配出一笔债务去做解押和额度回补——那是资损。
 * 真实 Feign 实现接入前，所有流水都会走 UNMATCHED（挂起），这是安全侧的默认。</p>
 *
 * <p>TODO(Wave2 集成)：替换为 {@code @FeignClient(name="loan-service")} 实现，
 * 契约见 docs/接口契约-V16借据.md；404 映射为 Optional.empty()，其余异常照常抛出。</p>
 */
public class LoggingLoanLookupClient implements LoanLookupClient {

    private static final Logger log = LoggerFactory.getLogger(LoggingLoanLookupClient.class);

    @Override
    public Optional<LoanSnapshot> findActiveByVin(String vin) {
        log.warn("TODO(Wave2) 借据查询未接线，按无在贷债务处理（流水将挂起待人工）：vin={}", vin);
        return Optional.empty();
    }
}
