package com.company.floorplan.domain.repository;

import java.util.List;
import java.util.Optional;

import com.company.floorplan.domain.model.CollateralRegistrationBatch;

/**
 * 担保登记批次仓储。
 */
public interface CollateralRegistrationBatchRepository {

    /** 按主键查批次。 */
    Optional<CollateralRegistrationBatch> findById(Long id);

    /** 按内部批次号查。 */
    Optional<CollateralRegistrationBatch> findByBatchNo(String batchNo);

    /** 按法务省登记编号查（唯一性校验用）。 */
    Optional<CollateralRegistrationBatch> findByRegistrationNo(String registrationNo);

    /** 全部批次。 */
    List<CollateralRegistrationBatch> findAll();

    /**
     * 某批次覆盖的车辆明细（VIN 列表）。
     *
     * @param batchId 批次 ID
     */
    List<String> findVinsByBatchId(Long batchId);

    /**
     * 某台车所属的批次（一台车在同一批次只能出现一次）。
     *
     * @param vin 车架号
     */
    List<CollateralRegistrationBatch> findByVin(String vin);

    /**
     * 即将到期或已过期的批次（让渡担保登记 5 年期限、不可续展）。
     *
     * @param deadline 预警截止时点（含）
     */
    List<CollateralRegistrationBatch> findExpiring(java.time.LocalDateTime deadline);

    /** 抹消待办清单（REQUESTED / APPLIED）。 */
    List<CollateralRegistrationBatch> findCancellationPending();

    /** 保存批次（新增或更新）。 */
    CollateralRegistrationBatch save(CollateralRegistrationBatch batch);

    /**
     * 把一台车挂到批次上（批次 ↔ 车辆明细）。
     *
     * @param batchId  批次 ID
     * @param ledgerId 担保物台账行 ID
     * @param vin      车架号
     */
    void addItem(Long batchId, Long ledgerId, String vin);

    /**
     * 从批次移除一台车（登记被撤销等情形）。
     *
     * @param batchId 批次 ID
     * @param vin     车架号
     */
    void removeItem(Long batchId, String vin);
}
