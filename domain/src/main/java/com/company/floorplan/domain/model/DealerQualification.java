package com.company.floorplan.domain.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 车商资质聚合根（R1；D03 §2.1 的 {@code DealerQualification} 独立聚合）。
 *
 * <p><b>为什么独立成聚合而不塞进现有 {@code Dealer}</b>：{@code Dealer} 是 LEGACY 主数据，
 * 新车链路每天都在读写它；把許可／会员／合规三张子表挂进 {@code Dealer}，
 * 等于让 USED 的加载代价压到 LEGACY 的每次查询上，也让两个并行包的仓储改动撞在同一文件。
 * D03 §2.1 把它标成「🆕 独立聚合」正是这个意思。</p>
 *
 * <p><b>{@code admissionState} 是派生快照</b>（D03 §2.1「派生，不落库或只落快照」）：
 * 本类不保存它，也不提供 {@code setAdmissionState}。值只能由
 * {@code DealerAdmissionPolicy.evaluate(...)} 现场算出；写进 {@code dealers.admission_state}
 * 的是那份快照，写入口唯一在 {@code DealerQualificationApplicationService.recalculateAdmission}
 * （UC-R1-4）。</p>
 *
 * <p><b>"1..n 历史 + 有效唯一"的读法</b>：{@code licenses} 装的是该 dealerId 的全部行，
 * 有效行由 {@link #activeLicense()} 取；库里那条 {@code uk_dealer_license_active}
 * 才是"最多一条 ACTIVE"的强制点，本类只做读取侧的收敛，不做写入侧的自证。</p>
 */
public class DealerQualification {

    /** 车商 ID（聚合身份）。 */
    private final Long dealerId;

    /** 許可历史行（含已过期、已取消、被替换的行）。 */
    private final List<DealerLicense> licenses = new ArrayList<>();

    /** 各拍卖场的会员资格行。 */
    private final List<AuctionMembership> memberships = new ArrayList<>();

    /** 合规核查历史行。 */
    private final List<ComplianceCheck> complianceChecks = new ArrayList<>();

    /**
     * 构造聚合根。
     *
     * @param dealerId 车商 ID
     */
    public DealerQualification(Long dealerId) {
        this.dealerId = dealerId;
    }

    public Long getDealerId() {
        return dealerId;
    }

    /** 許可历史（不可变视图由调用方保证，仓储装载时按 {@code issued_on} 倒序）。 */
    public List<DealerLicense> getLicenses() {
        return licenses;
    }

    /** 会员资格历史。 */
    public List<AuctionMembership> getMemberships() {
        return memberships;
    }

    /** 合规核查历史。 */
    public List<ComplianceCheck> getComplianceChecks() {
        return complianceChecks;
    }

    /**
     * 取当前有效許可行。
     *
     * <p>「有效」在这里只指 {@code status = ACTIVE}（不含日期判断），
     * 因为日期判断属策略层（{@code LicenseStatus} 类注释说明了为什么不能两层各判一次）。
     * 若库里因历史脏数据出现多行 ACTIVE，本方法取第一条——<b>这是读取侧的容忍，
     * 不是对不变式的放松</b>：约束由 {@code uk_dealer_license_active} 保证，
     * 出现多行说明迁移或直写库出了问题，该由收口方的结构自检报出来。</p>
     *
     * @return 有效許可，可能为空
     */
    public DealerLicense activeLicense() {
        for (DealerLicense license : licenses) {
            if (license.getStatus() != null && license.getStatus().isUsable()) {
                return license;
            }
        }
        return null;
    }

    /**
     * 取指定拍卖场当前有效的会员资格行。
     *
     * @param auctionHouseCode 拍卖场代码（D06 §1.1 的「申请指向的拍卖场」）
     * @param bizDate          业务日（会员行同样受日期影响，这里只比 {@code expiresOn}，
     *                         状态判定仍归 {@link AuctionMembershipStatus#isUsable()}）
     * @return 有效会员行，可能为空
     */
    public AuctionMembership activeMembership(String auctionHouseCode, LocalDate bizDate) {
        for (AuctionMembership membership : memberships) {
            // 会场代码是精确匹配：D06 §1.1 的规则 3 说的是「申请指向的拍卖场」，
            // 只要别的场还有效就放行，会把「USS 被停、TAA 还在」误判成可用。
            if (auctionHouseCode == null || !auctionHouseCode.equals(membership.getAuctionHouseCode())) {
                continue;
            }
            if (membership.getStatus() == null || !membership.getStatus().isUsable()) {
                continue;
            }
            if (membership.getExpiresOn() != null && bizDate != null && bizDate.isAfter(membership.getExpiresOn())) {
                continue;
            }
            return membership;
        }
        return null;
    }
}
