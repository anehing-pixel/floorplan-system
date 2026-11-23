package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.LtvBand;
import com.company.floorplan.domain.model.LtvPolicy;
import com.company.floorplan.domain.model.LtvPolicyStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 分档 LTV 政策仓储（USED R3.2，{@code ltv_policies} + {@code ltv_policy_bands}，V57）。
 *
 * <p>读写单位是政策聚合：查出来的 {@link LtvPolicy} 一定带齐它的带集合。
 * 放款判定与试算都在带集合上跑，漏读一次带就等于「政策存在但零档」，
 * 而零档按 {@code LtvTierResolver} 是拒付而不是放行——表现形式是全线停摆，
 * 排查方向却会被带到「政策没生效」上。</p>
 *
 * <p><b>没有 {@code version} 乐观锁列可用</b>：V57 的 {@code version} 是<b>政策业务版本号</b>
 * （同号多版），不是行锁。所以改状态一律走 {@link #updateStatus} 的「带前置状态的条件更新」，
 * 用 {@code WHERE status = 期望值} 挡住重复批准；靠 {@code uk_policy_active} 兜并发双 ACTIVE。
 * 这里不假装有行级锁，也不新加列——本轮没有「同一政策并发编辑」的真实场景。</p>
 */
public interface LtvPolicyRepository {

    /** 某政策码当前生效版本（含带集合）；{@code uk_policy_active} 保证至多一行。 */
    Optional<LtvPolicy> findActive(String policyCode);

    /** 按 id 取（含带集合）——批准与试算都按 id 寻址，因为「哪一版」是调用方已知的。 */
    Optional<LtvPolicy> findById(long id);

    /**
     * 列表（管理台用）。
     *
     * @param status 为空串或 {@code null} 时不按状态过滤
     */
    List<LtvPolicy> find(String status);

    /** 某政策码的下一个业务版本号 = {@code max(version)+1}（{@code uk_ltv_policy} 要求同号内唯一）。 */
    int nextVersion(String policyCode);

    /**
     * 写入一份草稿（政策头，带集合由 {@link #replaceBands} 单独写）。
     *
     * <p>实现里强制把状态写成 {@code DRAFT}、清掉 {@code approved_by/approved_at}：
     * 生效只能走 {@code approve}（L5 二人 + V57 {@code ck_ltv_policy_approved}），
     * 不给「创建即生效」留入口。{@code policy_code + version} 重复时抛
     * {@code DuplicateKeyException}，由应用层翻成 {@code CONFLICT_001}。</p>
     */
    LtvPolicy insertDraft(LtvPolicy policy);

    /**
     * 全量替换带集合（D07 §7.2「PUT /bands ＝ 全量替换」）。
     *
     * <p>同事务内先按 {@code policy_id} 删干净再插，语义上不存在「部分替换」——
     * 逐条增删会让一次失败的请求留下半套带，而半套带的政策在 L2 上是「有缺口」，
     * 缺口只在放款时才暴露。L5（ACTIVE 不得改带）由调用方在进入本方法前判。</p>
     *
     * @return 写入的条数
     */
    int replaceBands(long policyId, List<LtvBand> bands);

    /**
     * 条件式状态迁移：仅当当前状态是 {@code from} 时才改成 {@code to}。
     *
     * @param approvedBy 生效人；只在 {@code to = ACTIVE} 时写（V57 要求 ACTIVE 必有生效痕迹）
     * @param approvedAt 生效时点，同上
     * @return 1 = 迁移成功；0 = 状态已被别人改过（调用方回 {@code CONFLICT_001}）
     */
    int updateStatus(long policyId, LtvPolicyStatus from, LtvPolicyStatus to,
                     String approvedBy, OffsetDateTime approvedAt);
}
