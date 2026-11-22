package com.company.floorplan.domain.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 拍卖场会员资格实体（R1.1；表 {@code dealer_auction_memberships}，V45 / D04 §3.2）。
 *
 * <p><b>按拍卖场分别</b>（D03 §2.1）：同一车商允许在 USS 与 TAA 各持一个会员号，
 * 唯一性是 {@code (dealer_id, auction_house_code, member_no)} 三元组
 * （V45 的表级约束 {@code uk_dmem_dealer_house_no}），不是"一个车商一条会员记录"。
 * 幂等键 {@code DMEM:{dealerId}:{house}:{memberNo}}（D00 §13）与这条唯一键一一对应。</p>
 *
 * <p><b>没有 {@code version}</b>：本表的变更是"改状态/改期限"的单列更新，
 * 由 D04 §3.2 的列规格决定；不存在需要 CAS 的并发写语义。</p>
 *
 * <p><b>本包无状态机</b>：与 {@link LicenseStatus} 同理（不禁止订正），
 * 判定统一走 {@link AuctionMembershipStatus#isUsable()}。</p>
 */
public class AuctionMembership {

    /** 主键。 */
    private Long id;
    /** 所属车商（FK → {@code dealers.id}）。 */
    private Long dealerId;
    /** 拍卖场代码（逻辑引用 {@code auction_houses}，该表在 W-U2 的 V60，本包不建物理 FK）。 */
    private String auctionHouseCode;
    /** 会员号。 */
    private String memberNo;
    /** 会员等级（会场侧分类，本包不据此判定）。 */
    private String memberGrade;
    /** 加入日。 */
    private LocalDate joinedOn;
    /** 有效期限（年会费周期等）。 */
    private LocalDate expiresOn;
    /** 状态。 */
    private AuctionMembershipStatus status;
    /** 証拠文件路径。 */
    private String evidencePath;
    /** 创建时间。 */
    private Instant createdAt;
    /** 更新时间。 */
    private Instant updatedAt;
    /** 创建人。 */
    private String createdBy;
    /** 更新人。 */
    private String updatedBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDealerId() { return dealerId; }
    public void setDealerId(Long dealerId) { this.dealerId = dealerId; }

    public String getAuctionHouseCode() { return auctionHouseCode; }
    public void setAuctionHouseCode(String auctionHouseCode) { this.auctionHouseCode = auctionHouseCode; }

    public String getMemberNo() { return memberNo; }
    public void setMemberNo(String memberNo) { this.memberNo = memberNo; }

    public String getMemberGrade() { return memberGrade; }
    public void setMemberGrade(String memberGrade) { this.memberGrade = memberGrade; }

    public LocalDate getJoinedOn() { return joinedOn; }
    public void setJoinedOn(LocalDate joinedOn) { this.joinedOn = joinedOn; }

    public LocalDate getExpiresOn() { return expiresOn; }
    public void setExpiresOn(LocalDate expiresOn) { this.expiresOn = expiresOn; }

    public AuctionMembershipStatus getStatus() { return status; }
    public void setStatus(AuctionMembershipStatus status) { this.status = status; }

    public String getEvidencePath() { return evidencePath; }
    public void setEvidencePath(String evidencePath) { this.evidencePath = evidencePath; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
