package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.Advance;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 垫款记录仓储接口。
 *
 * <p>核心能力是按<b>幂等键</b>查询（{@link #findByIdempotencyKey(String)}）：
 * 重复请求直接返回首次结果，配合数据库唯一索引，杜绝重复垫款。</p>
 */
public interface AdvanceRepository {

    /** 幂等：按 Idempotency-Key 查询首次结果。 */
    Optional<Advance> findByIdempotencyKey(String key);

    Optional<Advance> findByVin(String vin);

    /**
     * 查询该 VIN 下<b>尚未终结</b>的垫款指令（{@code PENDING} / {@code PROCESSING}）。
     *
     * <p><b>为什么需要它</b>：{@code advances.vin} 上没有唯一约束，
     * 而"同一台车不得有两笔在途垫款"这条业务规则原先<b>靠车辆状态间接兜住</b>——
     * 旧实现里申请垫款后车辆即被置为 {@code FINANCED}，第二次申请会被状态校验挡下。
     * V12 引入 {@code PENDING_ADVANCE} 后，未付款的车合法地停留在该状态，
     * 这层间接保护就消失了：同一台车可以用不同的幂等键申请出两笔 {@code PENDING}
     * 指令，造成<b>额度双重占用 + 重复付款</b>。
     * 因此改为在本处直接、显式地判定。</p>
     *
     * @param vin 车架号
     * @return 在途指令；无则返回 empty
     */
    Optional<Advance> findActiveByVin(String vin);

    /**
     * 查询该 VIN 上<b>仍占用名额</b>的垫款（{@code PENDING} / {@code PROCESSING} / {@code PAID}）。
     *
     * <p>与 {@link #findActiveByVin} 的差别是<b>包含 PAID</b>：
     * PAID 表示债权存续中，它仍然占着"一车一笔垫款"的唯一名额。
     * 结清/赎回时靠本方法定位要推进到 {@code REDEEMED} 的那条记录；
     * 若查不到（历史台账没有垫款记录），调用方应容许继续，而非报错。</p>
     *
     * @param vin 车架号
     * @return 占用名额的垫款；无则返回 empty
     */
    Optional<Advance> findOccupyingByVin(String vin);

    /** 按主键查询（确认/失败回填用）。 */
    Optional<Advance> findById(Long id);

    /** 按业务单号查询（支付网关回调用）。 */
    Optional<Advance> findByAdvanceNo(String advanceNo);

    Advance save(Advance advance);

    /**
     * 仅更新复核痕迹（复核人 + 复核时间），不触碰状态。
     *
     * @param id         垫款主键
     * @param reviewer   复核人（自然人标识）
     * @param reviewedAt 复核时间
     */
    void saveReview(Long id, String reviewer, LocalDateTime reviewedAt);

    /**
     * 仅更新支付人痕迹，不触碰状态。
     *
     * @param id        垫款主键
     * @param disburser 支付人（自然人标识）
     */
    void saveDisburser(Long id, String disburser);
}
