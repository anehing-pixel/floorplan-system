package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.Repayment;

import java.util.List;
import java.util.Optional;

/**
 * 还款核销记录仓储接口。
 *
 * <p>与垫款同理：幂等键查询用于防重复还款；
 * 另有按车架号查询某台车的全部还款记录（部分还款场景会用）。</p>
 */
public interface RepaymentRepository {

    /** 幂等：按 Idempotency-Key 查询首次结果。 */
    Optional<Repayment> findByIdempotencyKey(String key);

    List<Repayment> findByVin(String vin);

    Repayment save(Repayment repayment);
}
