package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.PaymentTransaction;

import java.time.LocalDate;
import java.util.List;

/**
 * 银行回款流水仓储。
 *
 * <p><b>本接口只有一个写方法，而且是"插入或忽略"</b>——这是刻意的。
 * 流水是事实，事实不可修改：银行说这笔钱是 10:03 到的，就是 10:03。
 * 若提供 {@code update}，就给了"事后改事实"的能力，
 * 而这正是审计最忌讳的（改了无法自证）。</p>
 *
 * <p>匹配结果（{@code match_status}）由匹配层单独写入，不走本仓储的主写路径。</p>
 */
public interface PaymentTransactionRepository {

    /**
     * 幂等入账：已存在（同账户同流水号）则忽略。
     *
     * <p><b>幂等由数据库唯一约束保证，不是先查后插。</b>
     * 先查后插在并发下必然穿：两个实例同时查"不存在"，然后都插入。
     * 正确做法是让数据库去判，用 {@code ON CONFLICT DO NOTHING}，
     * 再看影响行数——1 表示新入，0 表示重复。</p>
     *
     * @return true=真正插入了；false=该流水已存在，本次被忽略
     */
    boolean insertIfAbsent(PaymentTransaction transaction);

    /** 按采集批次查（批次重放/排查用）。 */
    List<PaymentTransaction> findByBatchNo(String batchNo);

    /** 未匹配清单 —— 贷后跟进的直接对象。 */
    List<PaymentTransaction> findUnmatched();

    /** 某账户某区间的流水（对账用）。 */
    List<PaymentTransaction> findByAccountAndDateRange(String accountRef, LocalDate from, LocalDate to);

    /** 该批次实际入库条数（校验 insertedCount 是否与库内一致）。 */
    long countByBatchNo(String batchNo);
}
