package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.PayoffCertificate;

import java.util.Optional;

/**
 * 结清证明仓储接口。
 */
public interface PayoffCertificateRepository {

    /** 保存（按 cert_no 幂等）。 */
    PayoffCertificate save(PayoffCertificate certificate);

    /** 按车架号查询结清证明。 */
    Optional<PayoffCertificate> findByVin(String vin);
}
