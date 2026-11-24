package com.company.floorplan.web.job;

import com.company.floorplan.application.service.BatchApplicationService;
import com.company.floorplan.domain.service.DistributedLock;
import com.company.floorplan.domain.service.LockHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 核心账务日终作业调度（2026-11-24）。
 *
 * <p><b>为什么在 web 而不在拆出去的服务</b>：计息/库龄/结算窗口/处置/年审/归档
 * 都读写核心账本（vehicle_units / interest_ledger / batch_runs），依赖本地事务 + 行锁；
 * 拆到别的进程会把事务边界切断（ADR-001 勿拆）。所以调度器跟着账本留在 web。</p>
 *
 * <p><b>多实例安全</b>：每个作业先取 {@link DistributedLock}（key=job:&lt;名&gt;:&lt;bizDate&gt;，
 * TTL 30 分钟）。抢不到锁直接跳过，不阻塞、不等待；锁 provider 由
 * {@code floorplan.lock.provider} 决定（本地 memory，生产 redis）。
 * 作业本身还有 batch_runs / interest_ledger 的唯一约束兜底，锁只是避免重复计算。</p>
 *
 * <p><b>开关</b>：{@code floorplan.jobs.enabled} 默认 false——本地冒烟不想让 cron 干扰
 * 测试数据；<b>prod profile 启动检查会强制要求 true</b>（见 ProductionReadinessCheck），
 * 生产不允许"调度没开却以为在跑"。</p>
 *
 * <p><b>时间</b>：cron 与 bizDate 都按 Asia/Tokyo。具体时刻是<strong>默认值</strong>，
 * 每个作业的 cron 都可由配置键覆盖；签字前按 [待定源] 处理，不写死在代码里。
 * 默认顺序：计息 23:50 → 库龄推进 01:00 → 递减计划 01:30 → 结算窗口 02:00 →
 * 处置开单 02:30 → 年审扫描 03:00 → 季度归档 04:00（仅 1/4/7/10 月 1 日）。</p>
 */
@Component
@ConditionalOnProperty(name = "floorplan.jobs.enabled", havingValue = "true")
public class ScheduledCoreJobs {

    private static final Logger log = LoggerFactory.getLogger(ScheduledCoreJobs.class);
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);

    private final BatchApplicationService batchService;
    private final DistributedLock distributedLock;

    public ScheduledCoreJobs(BatchApplicationService batchService, DistributedLock distributedLock) {
        this.batchService = batchService;
        this.distributedLock = distributedLock;
    }

    /** 日终计息：默认 23:50 JST。 */
    @Scheduled(cron = "${floorplan.jobs.interest.cron:0 50 23 * * *}", zone = "Asia/Tokyo")
    public void dailyInterest() {
        runLocked("DAILY_INTEREST", batchService::runDailyInterest);
    }

    /** 库龄推进：默认 01:00 JST。 */
    @Scheduled(cron = "${floorplan.jobs.aging.cron:0 0 1 * * *}", zone = "Asia/Tokyo")
    public void agingAdvance() {
        runLocked("AGING_ADVANCE", batchService::runAgingAdvance);
    }

    /** 库龄递减计划生成：默认 01:30 JST。 */
    @Scheduled(cron = "${floorplan.jobs.aging-reduction.cron:0 30 1 * * *}", zone = "Asia/Tokyo")
    public void agingReduction() {
        runLocked("AGING_REDUCTION_DUE", batchService::runAgingReduction);
    }

    /** 结算窗口扫描：默认 02:00 JST。 */
    @Scheduled(cron = "${floorplan.jobs.settlement-scan.cron:0 0 2 * * *}", zone = "Asia/Tokyo")
    public void settlementScan() {
        runLocked("SETTLE_WINDOW_SCAN", batchService::runSettlementScan);
    }

    /** 违约处置自动开单：默认 02:30 JST。 */
    @Scheduled(cron = "${floorplan.jobs.disposal.cron:0 30 2 * * *}", zone = "Asia/Tokyo")
    public void defaultDisposal() {
        runLocked("DEFAULT_DISPOSAL", batchService::runDefaultDisposal);
    }

    /** 额度年审扫描：默认 03:00 JST。 */
    @Scheduled(cron = "${floorplan.jobs.annual-review-scan.cron:0 0 3 * * *}", zone = "Asia/Tokyo")
    public void annualReviewScan() {
        runLocked("ANNUAL_REVIEW_SCAN", batchService::runAnnualReviewScan);
    }

    /** 计息流水季度归档：默认 1/4/7/10 月 1 日 04:00 JST。 */
    @Scheduled(cron = "${floorplan.jobs.archive.cron:0 0 4 1 1,4,7,10 *}", zone = "Asia/Tokyo")
    public void archive() {
        runLocked("INTEREST_ARCHIVE", batchService::runArchive);
    }

    /**
     * 取锁后执行；抢不到锁或锁服务不可用都跳过本轮，不阻塞调度线程。
     *
     * <p>作业异常只记日志不向外抛：调度线程抛异常会被 Spring 记一次，但下一轮仍会继续；
     * 重复抛只会污染日志。batch_runs 里已有失败记录，监控应看那里。</p>
     */
    private void runLocked(String jobName, Function<LocalDate, Map<String, Object>> job) {
        LocalDate bizDate = LocalDate.now(TOKYO);
        String key = "lock:job:" + jobName + ":" + bizDate;
        Optional<LockHandle> acquired;
        try {
            acquired = distributedLock.tryLock(key, LOCK_TTL);
        } catch (RuntimeException e) {
            // fail-closed：锁服务不可用时宁可少跑一次，也不能无锁并跑两次。
            log.error("定时作业获取锁失败，跳过本轮 job={} bizDate={} provider={} err={}",
                    jobName, bizDate, distributedLock.provider(), e.toString());
            return;
        }
        if (acquired.isEmpty()) {
            log.info("定时作业已被其他实例执行，跳过 job={} bizDate={} provider={}",
                    jobName, bizDate, distributedLock.provider());
            return;
        }
        try (LockHandle ignored = acquired.get()) {
            Map<String, Object> result = job.apply(bizDate);
            log.info("定时作业完成 job={} bizDate={} result={}", jobName, bizDate, result);
        } catch (RuntimeException e) {
            log.error("定时作业失败 job={} bizDate={}（下一轮调度继续；batch_runs 有失败记录）",
                    jobName, bizDate, e);
        }
    }
}
