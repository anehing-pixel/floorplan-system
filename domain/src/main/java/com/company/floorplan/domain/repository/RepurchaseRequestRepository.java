package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.RepurchaseRequest;

import java.util.Optional;

/**
 * 厂家回购请求仓储接口。
 */
public interface RepurchaseRequestRepository {

    /** 保存（按 request_no 幂等）。 */
    RepurchaseRequest save(RepurchaseRequest request);

    /** 按回购单号查询。 */
    Optional<RepurchaseRequest> findByRequestNo(String requestNo);

    /** 按车架号查询最新一条请求。 */
    Optional<RepurchaseRequest> findLatestByVin(String vin);
}
