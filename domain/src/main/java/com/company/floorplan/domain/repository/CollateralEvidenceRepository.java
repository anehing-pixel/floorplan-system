package com.company.floorplan.domain.repository;

import java.util.List;

import com.company.floorplan.domain.model.CollateralEvidenceLink;

/**
 * 担保物证据链仓储。
 */
public interface CollateralEvidenceRepository {

    /** 按台账 ID 查证据链（按事件时间排序）。 */
    List<CollateralEvidenceLink> findByLedgerId(Long ledgerId);

    /** 按 VIN 查证据链。 */
    List<CollateralEvidenceLink> findByVin(String vin);

    /** 追加一条证据。 */
    CollateralEvidenceLink add(CollateralEvidenceLink link);
}
