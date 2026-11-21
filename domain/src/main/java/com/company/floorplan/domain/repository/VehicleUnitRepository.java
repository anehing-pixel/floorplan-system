package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.VehicleStatus;
import com.company.floorplan.domain.model.VehicleUnit;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 车辆单元仓储接口（定义在领域层，实现在基础设施层）。
 *
 * <p><b>为什么接口放在 domain</b>：领域层不依赖任何框架与持久化技术，
 * 只声明"我需要什么能力"，由基础设施层用 JdbcTemplate 实现。
 * 这是依赖倒置——保证领域逻辑可被独立测试，也便于未来替换持久化方案。</p>
 *
 * <p><b>行锁约定</b>：涉及状态流转与金额变更的场景（售出、结清、担保变更）
 * 必须使用 {@link #findByVinForUpdate(String)}，
 * 只读场景用 {@link #findByVin(String)} 即可。</p>
 */
public interface VehicleUnitRepository {

    /**
     * 按车架号查询（不加锁，用于只读场景）。
     *
     * @param vin 车架号
     * @return 车辆单元
     */
    Optional<VehicleUnit> findByVin(String vin);

    /** 行锁读取，用于状态流转与还款。 */
    Optional<VehicleUnit> findByVinForUpdate(String vin);

    List<VehicleUnit> findByDealerId(Long dealerId);

    /**
     * 按采购订单查询车辆清单（ADR-007：采购订单一单多车）。
     *
     * @param purchaseOrderId 采购订单 ID
     * @return 该订单下的车辆列表
     */
    List<VehicleUnit> findByPurchaseOrderId(Long purchaseOrderId);

    List<VehicleUnit> findByStatus(VehicleStatus status);

    /** 计息扫描：处于计息状态且尚未在 bizDate 计过息的车辆。 */
    List<VehicleUnit> findInterestBearing(LocalDate bizDate);

    /** 结算窗口扫描：已售待还且逾期未还。 */
    List<VehicleUnit> findSettlementOverdue(LocalDate today);

    VehicleUnit save(VehicleUnit unit);

    boolean existsByVin(String vin);

    int countByDealerAndStatus(Long dealerId, VehicleStatus status);

    /**
     * 统计某经销商名下的全部车辆数（不限状态）。
     *
     * <p>用于删除前的护栏检查：名下还有车就不能删经销商主体，
     * 否则这些台账会变成没有归属的孤儿数据。</p>
     */
    int countByDealerId(Long dealerId);
}
