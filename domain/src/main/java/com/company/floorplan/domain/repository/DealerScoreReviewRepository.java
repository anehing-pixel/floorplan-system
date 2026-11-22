package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.DealerScoreReview;

import java.util.List;
import java.util.Optional;

/**
 * 双维评级评审流水仓储接口（R1.3；定义在领域层，实现在基础设施层）。
 *
 * <p><b>幂等与唯一约束的配对</b>（V46 实测，两条都不可少）：
 * <pre>
 *   DSCR:{dealerId}:{ts}  → uk_dsr_idem (idempotency_key 部分唯一，V46:93)
 *   同车商同年度只允许一条在途候选 → uk_dsr_pending (dealer_id, review_year WHERE status='PENDING'，V46:97)
 * </pre>
 * 前者拦"同一请求重放"，后者拦"并发开两条候选"——语义不同，缺任一条都会让
 * {@code confirmScores} 拿不到唯一候选。</p>
 *
 * <p><b>版本 CAS 只用在本表</b>：{@code dealer_score_reviews} 有 {@code version} 列（V46:88），
 * 而 {@code dealers} 没有（V1 建表未带、V46 只加列），所以
 * {@link #confirm} 走版本比较、{@link #writeConfirmedScoresToDealer} 只能按列直接写。
 * 这不是疏漏，是既有表结构给出的边界；差异已登记在实现说明。</p>
 */
public interface DealerScoreReviewRepository {

    /**
     * 按主键取候选行（D06 签名里的 candidateId）。
     *
     * @param id 主键
     * @return 行；不存在返回空
     */
    Optional<DealerScoreReview> findById(Long id);

    /**
     * 按幂等键取行（重放探测：同键同指纹回首次结果，同键不同指纹抛 CONFLICT_001）。
     *
     * @param idempotencyKey {@code DSCR:{dealerId}:{ts}}
     * @return 行；不存在返回空
     */
    Optional<DealerScoreReview> findByIdempotencyKey(String idempotencyKey);

    /**
     * 取某车商某评审年度的在途候选（{@code status='PENDING'}）。
     *
     * <p>最多一条由 {@code uk_dsr_pending} 保证，因此返回 Optional 而不是 List。</p>
     *
     * @param dealerId   车商 ID
     * @param reviewYear 评审年度
     * @return 在途候选；无则空
     */
    Optional<DealerScoreReview> findPending(Long dealerId, Integer reviewYear);

    /**
     * 某车商的评审历史（含终态行），供 {@code annualReviewSnapshot} 只读方法使用。
     *
     * @param dealerId   车商 ID
     * @param reviewYear 可选年度过滤；为空则全部年度
     * @return 按年度倒序、同年度按 ID 倒序
     */
    List<DealerScoreReview> findHistory(Long dealerId, Integer reviewYear);

    /**
     * 新增候选行（PENDING）。
     *
     * <p>实现必须在 {@code uk_dsr_idem} / {@code uk_dsr_pending} 冲突时返回已存在行，
     * 由应用层比对指纹，而不是把数据库异常直接抛给调用方。</p>
     *
     * @param review 候选行
     * @return 带 ID 与 version 的行（可能是既有行）
     */
    DealerScoreReview insert(DealerScoreReview review);

    /**
     * 签认候选行（{@code PENDING → CONFIRMED}），带版本比较。
     *
     * <p>SQL 必须同时带 {@code status = 'PENDING'} 与 {@code version = expectedVersion} 两个条件：
     * 前者防"已签认的行被再签一次"，后者防"读候选与签认之间有人改了分数"。
     * 同人签认的校验不在本方法（属 {@code RoleSeparationPolicy} 的职责，应用层先做）。</p>
     *
     * @param id              候选行 ID
     * @param confirmedBy     第二人标识
     * @param expectedVersion 期望版本
     * @return 是否更新成功（false＝状态已变或版本冲突，调用方需重读）
     */
    boolean confirm(Long id, String confirmedBy, int expectedVersion);

    /**
     * 作废候选行（{@code PENDING → CANCELLED}），带版本比较，条件同上。
     *
     * @param id              候选行 ID
     * @param note            作废原因（写入备注列）
     * @param expectedVersion 期望版本
     * @return 是否更新成功
     */
    boolean cancel(Long id, String note, int expectedVersion);

    /**
     * 把已签认的两维终值回写 {@code dealers}（G-A4 的落地动作）。
     *
     * <p><b>调用前置条件由应用层保证</b>：只有 {@code CONFIRMED} 且两维非空的流水行才允许调用
     * 本方法（{@code DealerScoreReview.isConfirmedFinal()}）。仓储不做二次判断——
     * 若仓储也判一次，"谁有权写主数据"这条规则就活在了两层，改一处必漏另一处。</p>
     *
     * <p><b>没有版本参数、也没有操作人参数</b>：{@code dealers} 无 {@code version} 列，
     * 加版本参数只会变成不参与 WHERE 的摆设；该表也没有 {@code updated_by} 列，
     * "谁改的分"只由 {@code dealer_score_reviews.confirmed_by} 与审计表承担，
     * 本方法签名里不放一个没地方落的参数。并发覆盖的窗口由"与 {@link #confirm} 同事务"压缩，
     * 缺版本列这件事已登记为实现说明里的表结构缺口。</p>
     *
     * @param dealerId          车商 ID
     * @param operatingScore    经营资质分终值
     * @param stockQualityScore 在库车辆质量分终值
     * @return 是否写入成功（false＝车商行不存在）
     */
    boolean writeConfirmedScoresToDealer(Long dealerId, Integer operatingScore,
                                         Integer stockQualityScore);
}
