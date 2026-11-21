package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.InterestEntry;

import java.time.LocalDate;
import java.util.List;

/**
 * 计息流水仓储接口 —— <b>批处理可重跑能力的基石</b>。
 *
 * <p>{@code (vin, biz_date)} 的唯一约束保证：
 * 同一台车同一业务日只会存在一条计息流水，
 * 因此作业被重复触发（故障恢复、人为误操作）也不会重复计息。</p>
 */
public interface InterestLedgerRepository {

    /** 幂等基石：该车辆在该业务日是否已计息。 */
    boolean exists(String vin, LocalDate bizDate);

    /** 幂等写入：已存在则跳过，不重复计息。 */
    InterestEntry saveIfAbsent(InterestEntry entry);

    /** 结清时以流水汇总为准。 */
    long sumInterestByVin(String vin);

    List<InterestEntry> findByVin(String vin);

    /**
     * 按季度归档（预设）：将早于 cutoff 的计息流水复制到历史表后从主表删除。
     *
     * @param cutoff 归档截止日（不含）—— biz_date < cutoff 的流水会被归档
     * @return 实际从主表删除的流水条数
     */
    int archiveBefore(LocalDate cutoff);
}
