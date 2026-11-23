package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.AdmissionState;
import com.company.floorplan.domain.model.DealerAuctionMembership;
import com.company.floorplan.domain.model.DealerComplianceCheck;
import com.company.floorplan.domain.model.DealerLicense;
import com.company.floorplan.domain.model.DealerQualification;
import com.company.floorplan.domain.model.LicenseStatus;
import com.company.floorplan.domain.model.LicenseType;
import com.company.floorplan.domain.model.MembershipStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 车商资质仓储（USED R1）。
 *
 * <p>接口在 {@code domain}、实现在 {@code infrastructure}，沿用本仓库既有的
 * {@code XxxRepository} / {@code JdbcXxxRepository} 配对（AGENTS.md §3.1：
 * domain 不依赖 Spring 容器，保持可纯单测）。</p>
 *
 * <p>方法命名一律带业务含义而不是 {@code save/findById}：本聚合的写入是
 * 「登记许可」「降级旧证」「追加合规结论」三种动作，各自约束不同
 * （许可要降级旧 ACTIVE 行、合规只追加不覆盖）。用统一的 save 会把这三种语义糊成一个。</p>
 */
public interface DealerQualificationRepository {

    /**
     * 组装某车商的资质聚合（许可 + 会员 + 合规 + 库内准入态快照）。
     *
     * <p>这是准入判定唯一的取数入口：一次拿全三方证据，避免调用方漏查一项。</p>
     */
    DealerQualification findByDealerId(Long dealerId);

    /** 许可列表（全部状态，登记时间倒序）。 */
    List<DealerLicense> findLicenses(Long dealerId);

    /** 按自然键取许可（幂等判定的依据：{@code DLIC:{dealerId}:{type}:{no}}）。 */
    Optional<DealerLicense> findLicenseByKey(Long dealerId, LicenseType licenseType, String licenseNo);

    /**
     * 新登记一条许可，返回带主键的对象。
     *
     * <p>{@code operator} 只用于 {@code created_by/updated_by} 两列，取值必须是
     * {@code AuditActor.resolve(request)} 的结果（AGENTS.md §6.3：操作人不从请求体读）。</p>
     */
    DealerLicense insertLicense(DealerLicense license, String operator);

    /**
     * 变更许可状态（换发时把旧 ACTIVE 置 {@code SUPERSEDED}、到期作业置 {@code EXPIRED}）。
     *
     * @return 实际更新行数（0 表示并发下已被别人改过，调用方要重读而不是重试写）
     */
    int updateLicenseStatus(Long licenseId, LicenseStatus status, String operator);

    /** 到期预警列表用：状态 ACTIVE 且到期角落在区间内的许可（含车商代码与名称）。 */
    List<DealerLicense> findActiveLicensesExpiringBetween(LocalDate fromInclusive, LocalDate toInclusive);

    /** 会员关系列表（全部状态）。 */
    List<DealerAuctionMembership> findMemberships(Long dealerId);

    /** 按自然键取会员（幂等判定：{@code DMEM:{dealerId}:{house}:{memberNo}}）。 */
    Optional<DealerAuctionMembership> findMembershipByKey(Long dealerId, String auctionHouseCode, String memberNo);

    /** 新增会员关系（{@code operator} 写 created_by/updated_by，取 AuditActor 结果）。 */
    DealerAuctionMembership insertMembership(DealerAuctionMembership membership, String operator);

    /** 变更会员关系（状态/到期日/会员番号）。 */
    int updateMembership(DealerAuctionMembership membership, String operator);

    /** 合规核查记录列表（全部类型与历史）。 */
    List<DealerComplianceCheck> findComplianceChecks(Long dealerId);

    /** 按外部请求编号取记录（I-11 入站幂等：{@code CHK:{partner}:{requestNo}}）。 */
    Optional<DealerComplianceCheck> findComplianceCheckByRequestNo(String requestNo);

    /** 追加一条合规结论（本表只追加，不覆盖历史结论；{@code operator} 写 created_by/updated_by）。 */
    DealerComplianceCheck insertComplianceCheck(DealerComplianceCheck check, String operator);

    /**
     * 取双维评分四列（G-A4 的判定输入）。
     *
     * <p>返回的是<b>视图</b>而不是散开的四个 getter：闸门必须同时拿到分数与其签认证据，
     * 分开取就会出现「只查了分数没查签认人」的那种半个判定（结果是把未确认的分数当成终值）。</p>
     */
    com.company.floorplan.domain.model.DealerScores findScores(Long dealerId);

    /**
     * 写入双维评分与其二人痕迹（{@code scores_run_key} 承载幂等）。
     *
     * <p>本方法不做二人校验，校验在用例层（要回 {@code AUTH_003} 而不是撞库 CHECK）；
     * 库内 {@code ck_dealers_score_two_person} 是第二道防线。</p>
     */
    int updateDualScores(Long dealerId, Integer operatingScore, Integer stockQualityScore,
                         String scoresRunKey, String coSignedBy, String operator);

    /**
     * 写回准入态快照。
     *
     * <p>只写 {@code dealers.admission_state} 与 {@code updated_at}（本表<b>没有</b> {@code updated_by} 列，
     * 「谁改的」由应用层写进 audit_events）；仓储不在这里偷偷插审计，
     * 那会让「改一次状态」产生两条不同来源的审计，追溯时分不清是谁写的。</p>
     */
    int updateAdmissionState(Long dealerId, AdmissionState state);

    /** 车商是否存在（返回主键；不存在时 empty，调用方按 DEALER_001/NOT_FOUND_001 处理）。 */
    Optional<Long> findDealerId(Long dealerId);
}
