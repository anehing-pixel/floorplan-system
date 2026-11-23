package com.company.floorplan.domain.model;

import java.time.LocalDate;

/**
 * 车商的拍卖场会员关系（USED R1，{@code dealer_auction_memberships}）。
 *
 * <p>一个车商可以在多个拍场各有一条会员（R1.1 验收：USS 与 TAA 并存），
 * 所以有效性的问法永远是「对哪个 {@code auctionHouseCode}」而不是「有没有会员」。
 * {@code DealerAdmissionPolicy} 里两条判定共用本实体：</p>
 * <ul>
 *   <li>准入态重算（无具体拍场上下文）⇒ 只要<b>存在任一</b>有效会员就过 G-A2；</li>
 *   <li>放款闸门（申请指向某拍场）⇒ 必须<b>该场</b>有效，否则 QUAL_002。</li>
 * </ul>
 * <p>两者口径不同是有意为之：前者回答「这个车商是不是行业内的活跃参与者」，
 * 后者回答「这笔落札的钱能不能付给这个场」。把前者写成后者，
 * 会让只在 TAA 有会员的车商连 USS 的老库存申请都被拒。</p>
 */
public class DealerAuctionMembership {

    /** 主键。 */
    private Long id;

    /** 所属车商。 */
    private Long dealerId;

    /** 拍卖场代码（关联 {@code auction_houses.auction_house_code}）。 */
    private String auctionHouseCode;

    /** 该场的会员番号。 */
    private String memberNo;

    /** 会员等级（拍场自定，如正会員/準会員）。 */
    private String memberGrade;

    /** 入会日。 */
    private LocalDate joinedOn;

    /** 有效期末日；{@code null} 表示無期限（正社員格通常无到期日）。 */
    private LocalDate expiresOn;

    /** 状态。 */
    private MembershipStatus status;

    /** 证据文件路径（会員証／拍场在籍証明）。 */
    private String evidencePath;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDealerId() {
        return dealerId;
    }

    public void setDealerId(Long dealerId) {
        this.dealerId = dealerId;
    }

    public String getAuctionHouseCode() {
        return auctionHouseCode;
    }

    public void setAuctionHouseCode(String auctionHouseCode) {
        this.auctionHouseCode = auctionHouseCode;
    }

    public String getMemberNo() {
        return memberNo;
    }

    public void setMemberNo(String memberNo) {
        this.memberNo = memberNo;
    }

    public String getMemberGrade() {
        return memberGrade;
    }

    public void setMemberGrade(String memberGrade) {
        this.memberGrade = memberGrade;
    }

    public LocalDate getJoinedOn() {
        return joinedOn;
    }

    public void setJoinedOn(LocalDate joinedOn) {
        this.joinedOn = joinedOn;
    }

    public LocalDate getExpiresOn() {
        return expiresOn;
    }

    public void setExpiresOn(LocalDate expiresOn) {
        this.expiresOn = expiresOn;
    }

    public MembershipStatus getStatus() {
        return status;
    }

    public void setStatus(MembershipStatus status) {
        this.status = status;
    }

    public String getEvidencePath() {
        return evidencePath;
    }

    public void setEvidencePath(String evidencePath) {
        this.evidencePath = evidencePath;
    }

    /** 业务日上是否有效：ACTIVE ＋ 有会员号 ＋ 未过期（末日当天仍有效）。 */
    public boolean isValidOn(LocalDate bizDate) {
        if (status == null || !status.isValid()) {
            return false;
        }
        if (memberNo == null || memberNo.isBlank()) {
            return false;
        }
        return expiresOn == null || !expiresOn.isBefore(bizDate);
    }

    /** 是否指向指定拍场。 */
    public boolean isFor(String houseCode) {
        return auctionHouseCode != null && auctionHouseCode.equalsIgnoreCase(houseCode);
    }

    /** 距到期天数；無期限返回 null。 */
    public Long daysToExpiry(LocalDate bizDate) {
        if (expiresOn == null) {
            return null;
        }
        return java.time.temporal.ChronoUnit.DAYS.between(bizDate, expiresOn);
    }
}
