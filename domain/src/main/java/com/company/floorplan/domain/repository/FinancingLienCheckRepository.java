package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.FinancingLienCheck;

import java.util.Optional;

/**
 * 权属 / 抵押 / 查封核对记录仓储接口。
 * 建账前重复融资拦截（P0-③）依赖此接口判断该车是否在他处存在权属负担。
 */
public interface FinancingLienCheckRepository {

    /** 保存一条核对记录。 */
    FinancingLienCheck save(FinancingLienCheck check);

    /**
     * 查询该车<b>最近一条</b>核对记录。
     * 若该记录 {@link FinancingLienCheck#isEncumbered()} 为 true，建账应拒绝。
     */
    Optional<FinancingLienCheck> findLatestByVin(String vin);
}
