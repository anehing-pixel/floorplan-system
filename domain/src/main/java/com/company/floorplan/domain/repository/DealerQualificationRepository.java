package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.AdmissionState;
import com.company.floorplan.domain.model.AuctionMembership;
import com.company.floorplan.domain.model.ComplianceCheck;
import com.company.floorplan.domain.model.DealerLicense;
import com.company.floorplan.domain.model.DealerQualification;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 车商资质仓储接口（R1；定义在领域层，实现在基础设施层）。
 *
 * <p><b>一个聚合一个接口</b>：許可／会员／合规三张表在 D03 §2.1 里属同一个聚合
 * {@code DealerQualification}，因此不为每张表各造一个仓储——聚合根是唯一的写入入口，
 * 拆开会让"一次改动只写了一半"成为可能。</p>
 *
 * <p><b>幂等与唯一约束的配对</b>（每张表各自的兜底点，缺一个就可能并发写出重复事实）：
 * <pre>
 *   DLIC:{dealerId}:{type}:{no}  → uk_dlic_dealer_type_no（自然键，V45）
 *                              ＋ uk_dealer_license_active（有效唯一，D04 §3.1）
 *   DMEM:{dealerId}:{house}:{memberNo} → uk_dmem_dealer_house_no（表级 UNIQUE）
 *   CHK:{partner}:{requestNo}  → uk_dchk_request_no（部分唯一，人工行 request_no 为 NULL 不受限）
 * </pre>
 * 冲突时仓储返回已存在行，由应用层比对请求指纹后决定"回首次结果"还是抛
 * {@code CONFLICT_001}（与 {@code AdvanceApplicationService} 的既有做法一致）。</p>
 *
 * <p><b>乐观锁的适用范围（实测结论，不是推测）</b>：V45 三张表都没有 {@code version} 列，
 * {@code dealers} 也没有（V1 建表未带、V46 只 ADD COLUMN 未补 version），
 * 号段内唯一带 {@code version} 的新表是 {@code dealer_score_reviews}（V46:88）。
 * 因此本接口只有 {@code dealer_score_reviews} 那条路径（见
 * {@link DealerScoreReviewRepository}）能做版本 CAS，
 * {@link #updateAdmissionState} 只能按"当前值不同才写"处理，理由与后果见该方法注释。</p>
 */
public interface DealerQualificationRepository {

    /**
     * 装载完整资质聚合（許可历史 + 会员历史 + 合规历史）。
     *
     * @param dealerId 车商 ID
     * @return 聚合；车商不存在时返回空
     */
    Optional<DealerQualification> findByDealerId(Long dealerId);

    /**
     * 按自然键取許可行（幂等探测用）。
     *
     * @param dealerId    车商 ID
     * @param licenseType 許可類型
     * @param licenseNo   許可番号
     * @return 已存在的行
     */
    Optional<DealerLicense> findLicense(Long dealerId, String licenseType, String licenseNo);

    /**
     * 新增許可行。
     *
     * @param license 許可
     * @return 带 ID 的行
     */
    DealerLicense insertLicense(DealerLicense license);

    /**
     * 更新許可行的状态与期限（换证/取消/订正）。
     *
     * <p>只更新会变的列，不整行覆盖，避免把并发写入者刚改的 {@code status} 又盖回去。</p>
     *
     * @param license 許可
     * @return 是否命中行
     */
    boolean updateLicense(DealerLicense license);

    /**
     * 列出某车商的会员资格行。
     *
     * @param dealerId 车商 ID
     * @param status   可选状态过滤（D07 §7.1 的 {@code ?status=}）；为空则全量
     * @return 会员行列表
     */
    List<AuctionMembership> findMemberships(Long dealerId, String status);

    /**
     * 按三元组取会员行（幂等探测用）。
     *
     * @param dealerId         车商 ID
     * @param auctionHouseCode 会场代码
     * @param memberNo         会员号
     * @return 已存在的行
     */
    Optional<AuctionMembership> findMembership(Long dealerId, String auctionHouseCode, String memberNo);

    /**
     * 新增会员行。
     *
     * @param membership 会员
     * @return 带 ID 的行
     */
    AuctionMembership insertMembership(AuctionMembership membership);

    /**
     * 更新会员行（状态/期限/等级）。
     *
     * @param membership 会员
     * @return 是否命中行
     */
    boolean updateMembership(AuctionMembership membership);

    /**
     * 按外部请求号取合规行（{@code CHK:} 幂等探测）。
     *
     * @param partnerCode 合作方代码（幂等键第一段）
     * @param requestNo   外部请求号
     * @return 已存在的行
     */
    Optional<ComplianceCheck> findComplianceByRequestNo(String partnerCode, String requestNo);

    /**
     * 新增合规核查行（append-only：结论更正＝插新行）。
     *
     * @param check 核查行
     * @return 带 ID 的行
     */
    ComplianceCheck insertCompliance(ComplianceCheck check);

    /**
     * 读准入态快照。
     *
     * @param dealerId 车商 ID
     * @return 快照值；{@code dealers.admission_state} 为 NULL（存量行）时返回空
     */
    Optional<AdmissionState> findAdmissionState(Long dealerId);

    /**
     * 写准入态快照。
     *
     * <p><b>为什么没有 expectedVersion 参数</b>（实测：V1 建 {@code dealers} 时未带
     * {@code version}，V46 只 {@code ADD COLUMN} 也没有补）：没有版本列就做不了版本 CAS，
     * 硬造一个参数只会在实现里变成"根本不参与 WHERE 的摆设"，比没有更危险。
     * 这里的取舍是：</p>
     * <ul>
     *   <li>准入态是<b>纯推导快照</b>（D07 §7.1 明写重算"无副作用"），不是并发竞争的账务状态；
     *       两次并发重算的写入值都来自各自事务内读到的权威事实，后写覆盖先写不产生错账。</li>
     *   <li>因此仓储实现用 {@code WHERE id=? AND admission_state IS DISTINCT FROM ?}：
     *       同值重算不产生写放大，也不刷新 {@code updated_at} 制造"档案刚被改过"的假象。</li>
     *   <li>残留风险如实登记：若两次重算的 {@code bizDate} 不同，可能留下较早 bizDate 的结论。
     *       本包不擅自给 {@code dealers} 补 {@code version}（超出 D04 §5.2 的新增列），
     *       是否补列由收口方裁决。</li>
     * </ul>
     *
     * <p>只写 {@code admission_state}（＋ {@code updated_at} 的例行维护），
     * <b>不顺带刷 {@code license_expiry_date}</b>：那是許可事实的投影，
     * 归 {@link #updateLicense} 那条路径写，两个写入者改同一列必然漂移。</p>
     *
     * @param dealerId 车商 ID
     * @param state    新值
     * @return 是否发生了实际写入（false＝值未变化或车商不存在，调用方据返回值决定是否记审计）
     */
    boolean updateAdmissionState(Long dealerId, AdmissionState state);

    /**
     * 列出许可将于 {@code expiresBefore} 之前到期或已过期、且当前 {@code ACTIVE} 的车商許可。
     *
     * <p>供 UC-R1-5 的到期预警作业使用；本包只提供查询能力，
     * <b>不建定时任务</b>（作业触发归 {@code JOB_TRIGGER} 侧与收口方排期）。</p>
     *
     * @param expiresBefore 截止日（含）
     * @return 許可行列表
     */
    List<DealerLicense> findActiveLicensesExpiringBy(LocalDate expiresBefore);
}
