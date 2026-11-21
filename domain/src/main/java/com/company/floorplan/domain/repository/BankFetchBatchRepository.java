package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.BankFetchBatch;

import java.util.List;
import java.util.Optional;

/**
 * 采集批次仓储。批次是运行日志，因此允许"先插入 RUNNING，再更新为 SUCCESS/FAILED"。
 *
 * <p>与流水仓储的"只插不改"形成对照：<b>事实不可改，过程记录必须可改</b>。
 * 把两者用同一套仓储抽象，就会在"能不能改"这件事上妥协。</p>
 */
public interface BankFetchBatchRepository {

    /** 按批次号 upsert（首次插入 RUNNING，结束时更新计数与状态）。 */
    BankFetchBatch save(BankFetchBatch batch);

    Optional<BankFetchBatch> findByBatchNo(String batchNo);

    /** 某账户最近的批次（排查"上次拉到几点"）。 */
    List<BankFetchBatch> findRecent(String accountRef, int limit);

    /**
     * 该账户是否已有 RUNNING 状态的批次。
     *
     * <p><b>这是锁的兜底，不是锁的替代。</b>正常情况下分布式锁已经挡住并发；
     * 但当锁服务不可用、或锁 TTL 提前过期（长任务）时，
     * 这个查询能回答"库里还挂着一个 RUNNING 吗"。
     * 注意它仍是 check-then-act，只用于告警与人工排查，不作为互斥手段。</p>
     */
    boolean hasRunning(String accountRef);
}
