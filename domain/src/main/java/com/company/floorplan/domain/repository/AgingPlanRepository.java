package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.AgingPlan;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 库龄递减还款计划仓储接口。
 *
 * <p>用于生成与推进"库龄超期强制分期偿还本金"的计划；
 * {@link #findDueOn(java.time.LocalDate)} 供日终作业扫描到期计划。</p>
 */
public interface AgingPlanRepository {

    Optional<AgingPlan> findByVin(String vin);

    /** 递减到期扫描。 */
    List<AgingPlan> findDueOn(LocalDate date);

    AgingPlan save(AgingPlan plan);
}
