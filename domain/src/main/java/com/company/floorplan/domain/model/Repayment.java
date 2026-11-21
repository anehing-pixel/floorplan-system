package com.company.floorplan.domain.model;

import java.time.LocalDateTime;

/** 还款核销记录。金额一律整数日元。 */
public class Repayment {

    /** 主键。 */
    private Long id;

    /** 还款单号（业务唯一）。 */
    private String repaymentNo;

    /** 车架号 —— 关联到单台台账。 */
    private String vin;

    /** 偿还的本金（整数日元）。 */
    private long principal;

    /** 偿还的利息（整数日元）—— 以计息流水汇总值为准。 */
    private long interest;

    /** 还款总额 = 本金 + 利息（整数日元）。 */
    private long totalAmount;

    /**
     * 还款类型（业务闭环 P1：区分整台结清与提前部分还款）。
     *
     * <p><b>取值</b>：{@code FULL}(整台结清) / {@code PARTIAL}(提前部分还款)。
     * 整台结清会解除担保 + 回补额度；部分还款只扣本金、不解除担保、不回补额度，
     * 因此必须区分，否则对账与额度占用会出现偏差。</p>
     */
    private String repaymentType;

    /** 结清时间。 */
    private LocalDateTime settledAt;

    /** 操作人 ID（内控追溯：谁执行了这笔结清）。 */
    private Long operatorId;

    /** 幂等键（数据库唯一索引），防重复还款。 */
    private String idempotencyKey;

    public Repayment() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRepaymentNo() {
        return repaymentNo;
    }

    public void setRepaymentNo(String repaymentNo) {
        this.repaymentNo = repaymentNo;
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

    public String getRepaymentType() {
        return repaymentType;
    }

    public void setRepaymentType(String repaymentType) {
        this.repaymentType = repaymentType;
    }

    public LocalDateTime getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(LocalDateTime settledAt) {
        this.settledAt = settledAt;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
