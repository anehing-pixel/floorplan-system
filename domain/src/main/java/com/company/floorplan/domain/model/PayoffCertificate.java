package com.company.floorplan.domain.model;

import java.time.LocalDateTime;

/**
 * 结清证明（业务闭环 P1：出具给车商的书面凭证）。
 *
 * <p><b>业务定位</b>：一台车整台结清（还款核销 + 解除担保 + 额度回补）后，
 * 金融公司应出具结清证明，载明本金、利息、合计与结清日，供车商办理解押、
 * 过户与后续处置。这是"闭环最后一里"——旧版缺失，本次补全。</p>
 *
 * <p><b>金额</b>：{@link #principal}/{@link #interest}/{@link #totalAmount} 整数日元。</p>
 */
public class PayoffCertificate {

    /** 主键。 */
    private Long id;

    /** 证明编号（业务唯一）。 */
    private String certNo;

    /** 车架号。 */
    private String vin;

    /** 本金（结清时未偿本金）。 */
    private long principal;

    /** 利息（按计息流水汇总）。 */
    private long interest;

    /** 合计 = 本金 + 利息。 */
    private long totalAmount;

    /** 出具时间。 */
    private LocalDateTime issuedAt;

    /** 操作人。 */
    private String operator;

    public PayoffCertificate() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCertNo() {
        return certNo;
    }

    public void setCertNo(String certNo) {
        this.certNo = certNo;
    }

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public long getPrincipal() {
        return principal;
    }

    public void setPrincipal(long principal) {
        this.principal = principal;
    }

    public long getInterest() {
        return interest;
    }

    public void setInterest(long interest) {
        this.interest = interest;
    }

    public long getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(long totalAmount) {
        this.totalAmount = totalAmount;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(LocalDateTime issuedAt) {
        this.issuedAt = issuedAt;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }
}
