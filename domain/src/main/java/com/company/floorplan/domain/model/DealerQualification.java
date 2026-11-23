package com.company.floorplan.domain.model;

import java.time.LocalDate;
import java.util.List;

/**
 * 车商资质聚合根（USED R1，D03 §2.1）。
 *
 * <p>把「许可 + 会员 + 合规」三方证据收在一个对象里，是为了让准入判定只看一个入参：
 * {@link com.company.floorplan.domain.service.DealerAdmissionPolicy#evaluate} 需要同时
 * 看到这三方才能给出唯一结论。若让它们各自散在应用层的三次查询里，
 * 就会出现「查了许可忘了查合规」的部分判定——那比全都查更危险，因为它会返回 ADMITTED。</p>
 *
 * <p>本聚合<b>不持有</b> {@code dealers} 主数据本身（额度、评级、地址等在
 * {@link Dealer} 聚合），也不做库访问；它是一次判定的事实快照，由仓储组装。</p>
 *
 * <p>{@code admissionState} 在这里只是<b>上次推导的落库快照</b>，
 * 不是当前结论——当前结论必须现算（{@code recalculateAdmission}）。
 * 读快照的地方都要能回答「这个快照是什么时候算的」，所以 {@link #snapshotComputedOn}
 * 由调用方（application 层从审计/更新日期取）传入，本类不猜。</p>
 */
public class DealerQualification {

    /** 车商主键（{@code dealers.id}）。 */
    private final Long dealerId;

    /** 许可历史（含各状态，按登记时间倒序由仓储给出）。 */
    private final List<DealerLicense> licenses;

    /** 拍卖场会员关系（各场分别一条）。 */
    private final List<DealerAuctionMembership> memberships;

    /** 合规核查记录（每类可多条，取未过期 PASS 的最新）。 */
    private final List<DealerComplianceCheck> complianceChecks;

    /** 库内已存的准入态快照；{@code null} 表示从未推导过（fail-closed）。 */
    private final AdmissionState storedAdmissionState;

    public DealerQualification(Long dealerId,
                               List<DealerLicense> licenses,
                               List<DealerAuctionMembership> memberships,
                               List<DealerComplianceCheck> complianceChecks,
                               AdmissionState storedAdmissionState) {
        this.dealerId = dealerId;
        // List.copyOf：聚合内的事实不允许被调用方在判定过程中增删，
        // 否则同一次请求里两次 evaluate 会得出不同结论（并发审计最忌这个）。
        this.licenses = licenses == null ? List.of() : List.copyOf(licenses);
        this.memberships = memberships == null ? List.of() : List.copyOf(memberships);
        this.complianceChecks = complianceChecks == null ? List.of() : List.copyOf(complianceChecks);
        this.storedAdmissionState = storedAdmissionState;
    }

    public Long getDealerId() {
        return dealerId;
    }

    public List<DealerLicense> getLicenses() {
        return licenses;
    }

    public List<DealerAuctionMembership> getMemberships() {
        return memberships;
    }

    public List<DealerComplianceCheck> getComplianceChecks() {
        return complianceChecks;
    }

    public AdmissionState getStoredAdmissionState() {
        return storedAdmissionState;
    }

    /**
     * 当前许可：状态 ACTIVE 的那条（多条时取 {@code issued_on} 最新，再取 id 最大）。
     *
     * <p>故意<b>不</b>在这里过滤到期日：过期但状态仍为 ACTIVE 的记录必须被闸门看到，
     * 否则「到期扫描作业还没跑」的车商会被判成「无许可记录」。两种情况的错误码相同，
     * 但运维含义完全不同——前者是作业没跑，后者是根本没登记过证。</p>
     */
    public DealerLicense getActiveLicense() {
        DealerLicense best = null;
        for (DealerLicense l : licenses) {
            if (l.getStatus() == null || !l.getStatus().isValid()) {
                continue;
            }
            if (best == null) {
                best = l;
                continue;
            }
            // 比较顺序：issued_on 新者优先；同日比 id 大者优先（后登记者为准）
            LocalDate a = l.getIssuedOn();
            LocalDate b = best.getIssuedOn();
            if (a != null && (b == null || a.isAfter(b))) {
                best = l;
            } else if (a != null && b != null && a.isEqual(b)
                    && l.getId() != null && best.getId() != null && l.getId() > best.getId()) {
                best = l;
            }
        }
        return best;
    }

    /** 该拍场是否有有效会员（放款闸门的 G-A2 指定场版本，委托给策略类保持单一实现）。 */
    public boolean hasValidMembershipFor(String auctionHouseCode, LocalDate bizDate) {
        for (DealerAuctionMembership m : memberships) {
            if (m.isFor(auctionHouseCode) && m.isValidOn(bizDate)) {
                return true;
            }
        }
        return false;
    }
}
