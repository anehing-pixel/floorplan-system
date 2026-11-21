package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.Supplier;

import java.util.List;
import java.util.Optional;

/**
 * 上游主数据仓储。
 *
 * <p>上游是独立风险主体，按编号（业务唯一键）与 ID 定位。</p>
 */
public interface SupplierRepository {

    Optional<Supplier> findById(Long id);

    /** 按上游编号查询（业务唯一键，用于创建查重与导入定位）。 */
    Optional<Supplier> findByCode(String supplierCode);

    List<Supplier> findAll();

    /** 按上游类型查询（后台列表筛选用）。 */
    List<Supplier> findByType(String supplierType);

    Supplier save(Supplier supplier);

    /** 更新状态（ACTIVE / SUSPENDED / TERMINATED）。 */
    int updateStatus(Long id, String status);
}
