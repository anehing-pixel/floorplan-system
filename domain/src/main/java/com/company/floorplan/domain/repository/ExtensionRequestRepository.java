package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.ExtensionRequest;

import java.util.Optional;

/**
 * 展期续贷请求仓储接口。
 */
public interface ExtensionRequestRepository {

    /** 保存（按 request_no 幂等）。 */
    ExtensionRequest save(ExtensionRequest request);

    /** 按车架号查询最新一条展期。 */
    Optional<ExtensionRequest> findLatestByVin(String vin);
}
