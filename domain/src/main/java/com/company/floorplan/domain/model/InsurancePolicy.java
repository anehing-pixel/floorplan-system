package com.company.floorplan.domain.model;

import java.time.LocalDate;

/**
 * 车辆保险单（业务闭环 P1：车辆损毁 / 盗抢保障）。
 *
 * <p><b>业务定位</b>：库存车辆可能因事故、火灾、盗抢等导致担保物减损。
 * 要求车商为在库车辆投保，并以金融公司为受益人（质押/保险金请求权）。
 * 出险后通过{@code claim()}记录理赔，理赔款优先冲抵垫款。</p>
 *
 * <p><b>status</b>：{@code ACTIVE}(有效) / {@code CLAIMED}(已理赔) / {@code LAPSED}(失效)。</p>
 *
 * <p><b>金额</b>：{@link #insuredAmount} 整数日元。</p>
 */
public class InsurancePolicy {

    /** 主键。 */
    private Long id;

    /** 保单号（业务唯一）。 */
    private String policyNo;

    /** 车架号。 */
    private String vin;

    /** 保险公司。 */
    private String insurer;

    /** 投保金额（整数日元）。 */
    private long insuredAmount;

    /** 保险起期。 */
    private LocalDate startDate;

    /** 保险止期。 */
    private LocalDate endDate;

    /** 状态。 */
    private String status;

    /** 理赔金额（已理赔时填写）。 */
    private long claimedAmount;

    public InsurancePolicy() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPolicyNo() {
        return policyNo;
    }

    public void setPolicyNo(String policyNo) {
        this.policyNo = policyNo;
    }

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public String getInsurer() {
        return insurer;
    }

    public void setInsurer(String insurer) {
        this.insurer = insurer;
    }

    public long getInsuredAmount() {
        return insuredAmount;
    }

    public void setInsuredAmount(long insuredAmount) {
        this.insuredAmount = insuredAmount;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getClaimedAmount() {
        return claimedAmount;
    }

    public void setClaimedAmount(long claimedAmount) {
        this.claimedAmount = claimedAmount;
    }
}
