package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.InsurancePolicy;

import java.util.Optional;

/**
 * 车辆保险单仓储接口。
 */
public interface InsurancePolicyRepository {

    /** 保存（按 policy_no 幂等）。 */
    InsurancePolicy save(InsurancePolicy policy);

    /** 按保单号查询（理赔登记用）。 */
    Optional<InsurancePolicy> findByPolicyNo(String policyNo);

    /** 按车架号查询当前有效保单。 */
    Optional<InsurancePolicy> findActiveByVin(String vin);
}
