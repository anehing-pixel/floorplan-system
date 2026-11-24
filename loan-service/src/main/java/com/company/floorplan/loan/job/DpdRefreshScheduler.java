package com.company.floorplan.loan.job;

import com.company.floorplan.domain.service.DistributedLock;
import com.company.floorplan.domain.service.LockHandle;
import com.company.floorplan.loan.service.LoanApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * 借据 DPD 批量刷新调度（2026-11-24）。
 *
 * <p><b>为什么在 loan-service</b>：DPD 是借据的派生状态，唯一写入点
 * {@link LoanApplicationService#refreshDpd} 在本服务；放回 web 会跨服务直写 loans 表。</p>
 *
 * <p><b>频率</b>：默认每日 00:30 JST（T+1 刷新）。ADR-008 要求 DPD 按自然日，
 * 不随营业日切换，所以 cron 用自然日。具体时刻可由
 * {@code floorplan.jobs.dpd-refresh.cron} 覆盖，属 [待定源]。</p>
 *
 * <p><b>多实例安全</b>：先取 {@link DistributedLock}（key=job:DPD_REFRESH:&lt;bizDate&gt;）。
 * 抢不到锁直接跳过；锁 provider 本地 memory、生产 redis。DPD 刷新本身按 asOf 幂等，
 * 锁只避免多副本重复扫全表。</p>
 *
 * <p><b>开关</b>：{@code floorplan.jobs.enabled} 默认 false；prod profile 启动检查强制 true。</p>
 */
@Component
@ConditionalOnProperty(name = "floorplan.jobs.enabled", havingValue = "true")
public class DpdRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(DpdRefreshScheduler.class);
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);

    private final LoanApplicationService loanService;
    private final DistributedLock distributedLock;

    public DpdRefreshScheduler(LoanApplicationService loanService, DistributedLock distributedLock) {
        this.loanService = loanService;
        this.distributedLock = distributedLock;
    }

    @Scheduled(cron = "${floorplan.jobs.dpd-refresh.cron:0 30 0 * * *}", zone = "Asia/Tokyo")
    public void refresh() {
        LocalDate asOf = LocalDate.now(TOKYO);
        String key = "lock:job:DPD_REFRESH:" + asOf;
        Optional<LockHandle> acquired;
        try {
            acquired = distributedLock.tryLock(key, LOCK_TTL);
        } catch (RuntimeException e) {
            log.error("定时作业获取锁失败，跳过 job=DPD_REFRESH asOf={} provider={} err={}",
                    asOf, distributedLock.provider(), e.toString());
            return;
        }
        if (acquired.isEmpty()) {
            log.info("定时作业已被其他实例执行，跳过 job=DPD_REFRESH asOf={} provider={}",
                    asOf, distributedLock.provider());
            return;
        }
        try (LockHandle ignored = acquired.get()) {
            int processed = loanService.refreshDpd(asOf);
            log.info("定时作业完成 job=DPD_REFRESH asOf={} processed={}", asOf, processed);
        } catch (RuntimeException e) {
            log.error("定时作业失败 job=DPD_REFRESH asOf={}（下一轮继续）", asOf, e);
        }
    }
}
