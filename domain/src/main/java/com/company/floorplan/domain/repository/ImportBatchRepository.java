package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.ImportBatch;
import com.company.floorplan.domain.model.ImportRowError;

import java.util.List;

/**
 * 导入批次仓储（Excel 通道的行级结果留痕）。
 *
 * <p>应用层不直接写 SQL——批次与行级错误的持久化走本接口，
 * 保持"应用层只依赖领域仓储接口"的分层纪律。</p>
 */
public interface ImportBatchRepository {

    /**
     * 创建导入批次。
     *
     * @param batch 批次
     * @return 批次 ID
     */
    Long create(ImportBatch batch);

    /**
     * 更新批次状态与提交时间。
     *
     * @param batchId    批次 ID
     * @param status     目标状态
     * @param committed  是否已提交
     */
    void markStatus(Long batchId, String status, boolean committed);

    /**
     * 批量保存行级错误。
     *
     * @param batchId 批次 ID
     * @param errors  错误明细
     */
    void saveRowErrors(Long batchId, List<ImportRowError> errors);

    /** 列出导入批次（后台展示）。 */
    List<ImportBatch> findAll();

    /** 查某批次的行级错误。 */
    List<ImportRowError> findRowErrors(Long batchId);
}
