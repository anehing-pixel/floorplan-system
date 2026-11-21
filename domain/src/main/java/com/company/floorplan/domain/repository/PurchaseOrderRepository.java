package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.PurchaseOrder;

import java.util.List;
import java.util.Optional;

/**
 * 采购订单主单仓储。
 *
 * <p>{@code order_no} 是幂等锚点：重复导入返回首次结果，不重复建账。</p>
 */
public interface PurchaseOrderRepository {

    Optional<PurchaseOrder> findById(Long id);

    /** 按采购订单号查询（业务唯一键）。 */
    Optional<PurchaseOrder> findByOrderNo(String orderNo);

    List<PurchaseOrder> findAll();

    /** 按经销商查询。 */
    List<PurchaseOrder> findByDealerId(Long dealerId);

    /** 按导入批次查询（用于回显一次导入建了哪些单）。 */
    List<PurchaseOrder> findByImportBatchId(Long importBatchId);

    PurchaseOrder save(PurchaseOrder order);

    /** 更新主单状态。 */
    int updateStatus(Long id, String status);

    /**
     * 判断某 VIN 是否已挂在未终结的采购订单下（VIN 幂等）。
     *
     * @param vin 车架号
     * @param excludeOrderId 排除的订单 ID（同一订单内重复校验时用）
     * @return true 表示该 VIN 已被其他在途订单占用
     */
    boolean existsActiveVin(String vin, Long excludeOrderId);

    /** 查某 VIN 关联的采购订单（用于幂等命中时返回首次结果）。 */
    Optional<PurchaseOrder> findByActiveVin(String vin);
}
