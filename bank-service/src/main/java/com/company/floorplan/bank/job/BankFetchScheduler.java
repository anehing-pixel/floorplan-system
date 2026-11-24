package com.company.floorplan.bank.job;

import com.company.floorplan.application.service.BankFetchApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 银行流水采集（BANK_FETCH）定时调度（2026-11-24）。
 *
 * <p><b>频率</b>：默认每 15 分钟（银企直连高频场景）；文件场景可把
 * {@code floorplan.jobs.bank-fetch.cron} 改成每日一次（例如 {@code 0 5 6 * * *}）。
 * 属于 [待定源]：具体频率取决于银行对接方式，签字前按默认值运行、配置可改。</p>
 *
 * <p><b>多实例安全</b>：本类不再加调度级锁——{@link BankFetchApplicationService#fetch}
 * 内部已经按账户取 {@code DistributedLock}（key 含账户与业务日），
 * 多副本同时触发时只有抢到锁的实例会真正拉取，其余返回 {@code SKIPPED_LOCKED}。
 * 再套一层调度锁只会把"哪个账户被谁拉"的粒度藏起来。</p>
 *
 * <p><b>开关</b>：{@code floorplan.jobs.enabled} 默认 false；prod profile 启动检查强制 true。</p>
 */
@Component
@ConditionalOnProperty(name = "floorplan.jobs.enabled", havingValue = "true")
public class BankFetchScheduler {

    private static final Logger log = LoggerFactory.getLogger(BankFetchScheduler.class);
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    private final BankFetchApplicationService bankFetchService;

    public BankFetchScheduler(BankFetchApplicationService bankFetchService) {
        this.bankFetchService = bankFetchService;
    }

    @Scheduled(cron = "${floorplan.jobs.bank-fetch.cron:0 */15 * * * *}", zone = "Asia/Tokyo")
    public void fetch() {
        LocalDate bizDate = LocalDate.now(TOKYO);
        try {
            Map<String, Object> result = bankFetchService.fetch(bizDate, List.of(), "system");
            log.info("定时作业完成 job=BANK_FETCH bizDate={} result={}", bizDate, result);
        } catch (RuntimeException e) {
            log.error("定时作业失败 job=BANK_FETCH bizDate={}（下一轮继续；行级唯一约束兜底）",
                    bizDate, e);
        }
    }
}
