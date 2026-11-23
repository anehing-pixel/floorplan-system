package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.ValuationInput;
import com.company.floorplan.domain.model.VehicleValuation;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 车辆估值仓储（USED R3，{@code vehicle_valuations} + {@code valuation_inputs}，V55）。
 *
 * <p><b>一行 = 一次估值结论</b>：主表存「四个来源的事实列 + 审慎值 + 有效期」，
 * 子表存「全部输入明细」。两套并存不是冗余（V55 头注给了理由）：闸门读主表一次就够，
 * 审计复算读子表。所以本接口的读写单位是<b>聚合</b>——查出来的 {@link VehicleValuation}
 * 一定带着它的 inputs，写进去的 inputs 一定与主表同事务落库，
 * 不给「只写主表、子表回头再补」的口子（那正是 D08 §3.2 要求同事务的原因）。</p>
 *
 * <p><b>不提供任何改写事实列的 UPDATE</b>：{@code auction_won_price} /
 * {@code appraised_value} / {@code market_price} 只在 {@link #insert} 时写入。
 * 人工修正走 {@link #appendInput} + {@link #updateConclusion}，
 * 「绝不改写历史行」（V55 表注 P20）在接口形状上就表达出来。</p>
 *
 * <p><b>ACTIVE 的唯一性维度</b>：V55 的 {@code uk_val_active} 是<b>按 VIN</b>（D04 §3.12 /
 * D03 §3.7 / D00 §8.5 三处同口径），不是 D08 §3.3 写的「同 VIN 同 purpose」。
 * 本接口按库走：{@link #supersedeActive} 会把该 VIN 当前所有 ACTIVE 行一并置替代，
 * <b>不分 purpose</b>。直接后果是一台车的处置定价估值会顶掉它的放款基准估值——
 * 处置（R10）在本轮范围外，真要上线处置定价时必须先由业务裁决这两者能不能并存
 * （登记见 {@code PROGRESS.md}）。不要用「insert 前只 supersede 同 purpose」绕过索引，
 * 那会在 uk_val_active 上抛一个没有业务语义的 500。</p>
 */
public interface VehicleValuationRepository {

    /**
     * 按 D00 §13.1 的键式 {@code VAL-{vin}-{yyyymmdd}-{seq}} 取下一个可用序号。
     *
     * <p>由库里 {@code count(*)+1} 而不是内存计数器：重放同一天的第二笔要给 seq=2。
     * 并发同刻可能撞出同一个 seq，那时 {@code valuation_key} 的 UNIQUE 会挡下来，
     * 由用例翻成 {@code CONFLICT_001}——这是刻意的失败方向（宁可让调用方重试，
     * 不能静默合并两笔估值）。</p>
     */
    String nextValuationKey(String vin, LocalDate valuationDate);

    /**
     * 写入一次估值结论：主表一行 + 其全部输入明细。
     *
     * <p>{@code valuation.valuationKey} 必填（{@link #nextValuationKey} 或用方自带幂等键）。
     * 返回对象已带库内 id；{@code valuation_key} 重复时抛
     * {@code DuplicateKeyException}，由应用层翻成 {@code CONFLICT_001}。</p>
     */
    VehicleValuation insert(VehicleValuation valuation);

    /** 给已存在的结论追加一条输入明细（人工覆盖的第二步用）。 */
    void appendInput(long valuationId, ValuationInput input);

    /**
     * 更新结论列（审慎值、主源、规则、覆盖二人、状态、有效期），带 {@code version} 乐观锁。
     *
     * <p>SET 清单里没有三个事实列——覆盖只新增 {@code manual_value} 并重算 {@code prudent_value}。
     * 返回 0 ＝ 并发覆盖，调用方必须回 {@code CONFLICT_001} 而不是重试到成功。</p>
     */
    int updateConclusion(VehicleValuation valuation);

    /**
     * 把该 VIN 现有 ACTIVE 行全部置 {@code SUPERSEDED}（与 {@link #insert} 同一事务，
     * 且必须先调用：{@code uk_val_active} 不允许两行 ACTIVE 并存）。
     *
     * @return 被替代的行数（0 = 这是该 VIN 的第一笔估值）
     */
    int supersedeActive(String vin, String actor);

    /** 该 VIN 当前的放款基准估值（{@code status='ACTIVE' AND purpose='ADVANCE_BASE'}）。 */
    Optional<VehicleValuation> findAdvanceBase(String vin);

    /** 按 id 取（覆盖/置失效端点的入口，必须能取到明细才能重算审慎值）。 */
    Optional<VehicleValuation> findById(long id);

    /**
     * 按业务键 {@code VAL-{vin}-{yyyymmdd}-{seq}} 取（含明细）。
     *
     * <p>只服务一件事：<b>幂等重放的前置读</b>。调用方显式带键重推时，
     * 必须先读再决定要不要写——写在唯一索引上抛 {@code DuplicateKeyException}，
     * 而 PostgreSQL 里语句失败会把整个事务置为 aborted，之后<b>任何</b>读都报
     * 「current transaction is aborted」，届时想回查「那一条已经存在的结论」并返回它是做不到的。
     * 所以重放判定只能安排在写之前。</p>
     */
    Optional<VehicleValuation> findByKey(String valuationKey);
}
