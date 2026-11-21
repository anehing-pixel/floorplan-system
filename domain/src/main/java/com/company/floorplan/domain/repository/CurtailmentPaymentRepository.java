package com.company.floorplan.domain.repository;

import java.util.Optional;

/**
 * 库龄递减还款核销流水仓储接口（P0-①）。
 *
 * <p>仅记录"每期实际还了多少本金"，用于审计与对账；
 * 不承载业务规则（规则在 {@code CurtailmentApplicationService}）。</p>
 */
public interface CurtailmentPaymentRepository {

    /**
     * 记录一期核销（按幂等键去重，重复上报不重复入账）。
     *
     * @param vin           车架号
     * @param periodNo      第几期
     * @param amount        还款本金（整数日元）
     * @param idempotencyKey 幂等键
     * @param operator      操作人
     */
    void save(String vin, int periodNo, long amount, String idempotencyKey, String operator);

    /**
     * 按幂等键查询已入账的核销流水（幂等重放判断用）。
     *
     * <p>返回长度为 2 的数组：[0]=期号 periodNo，[1]=金额 amount；
     * 该幂等键尚无流水时返回 {@link Optional#empty()}。</p>
     *
     * @param idempotencyKey 幂等键
     * @return 已入账流水的 (期号, 金额)，无则 empty
     */
    Optional<long[]> findByIdempotencyKey(String idempotencyKey);
}
