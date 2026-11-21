package com.company.floorplan.domain.repository;

import java.util.List;
import java.util.Optional;

import com.company.floorplan.domain.model.CollateralLedger;

/**
 * 担保物台账仓储（按 VIN 一台一行）。
 */
public interface CollateralLedgerRepository {

    /** 按 VIN 查询台账。 */
    Optional<CollateralLedger> findByVin(String vin);

    /** 全部台账（可按经销商过滤）。 */
    List<CollateralLedger> findAll(Long dealerId);

    /**
     * 已售未回款的敞口台账（库存释放规则的重点监控对象）。
     */
    List<CollateralLedger> findSoldUnpaid();

    /**
     * 存在登记/抹消待办的台账。
     */
    List<CollateralLedger> findWithDueItems();

    /**
     * 保存（新增或更新）。
     *
     * <p>更新走 upsert 语义：按 VIN 定位，行不存在则插入。
     * 这让"放款确认时建台账""售出时更新台账"可以在同一方法里表达，
     * 不必让调用方先查再判。</p>
     */
    CollateralLedger save(CollateralLedger ledger);
}
