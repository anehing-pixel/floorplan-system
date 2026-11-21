package com.company.floorplan.loan.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 借据 —— 债务的事实来源与贷后当前状态载体。
 *
 * <p><b>流程唯一读依赖</b>：放款拦截、能否冻结、能否处置只读本表单行；
 * 过程记在 loan_status_history / overdue_records，聚合结果投影到 dealer_risk_profile。
 * 画像表不参与任何流程判定，本类也不持有指向画像的引用。</p>
 *
 * <p><b>契约字段</b>：id / vin / advance_id / dealer_id / status / principal / due_date / created_at
 * 已冻结于 docs/接口契约-V16借据.md，payment agent 按此编码，勿改。</p>
 */
public class Loan {

    /** 借据主键。 */
    private Long id;
    /** 借据编号。 */
    private String loanNo;
    /** 资金来源凭证 → advances.id；银行回调 PAID 后才建据。 */
    private Long advanceId;
    /** 车架号：VIN 唯一指向一笔未结清借据。 */
    private String vin;
    /** 经销商 ID（画像按此聚合；不等于 dealers.risk_grade）。 */
    private Long dealerId;
    private Long creditLineId;
    private Long purchaseOrderId;

    /** 借据本金（整数日元）。 */
    private long principal;
    /** 合同到期日（与 maturityDate 同值，契约暴露名）。 */
    private LocalDate dueDate;
    /** 借据状态，取值见 {@link LoanStatus}。 */
    private String status;
    /** 创建时间。 */
    private LocalDateTime createdAt;

    private LocalDateTime disbursedAt;
    /** 设计稿原字段名，与 dueDate 同值，保留兼容引用。 */
    private LocalDate maturityDate;
    private int gracePeriodDays;
    private BigDecimal overdueRate;
    private String termSource;
    private String termConfirmedBy;
    private LocalDateTime termConfirmedAt;

    /** 剩余本金（四级冲抵最后一级）。 */
    private long principalOutstanding;
    private long interestOutstanding;
    private long penaltyOutstanding;
    private long feeOutstanding;

    /** 当前逾期天数，由 DPD job 唯一写入。 */
    private int currentDpd;
    /** M1/M2/M3/M4，未逾期为 null。 */
    private String currentStageCode;
    private LocalDate overdueSince;
    /** 历史 DPD 峰值，只增不减。 */
    private int maxDpdEver;
    private int overdueCount;
    private LocalDateTime currentStageEnteredAt;

    private int deductionFailStreak;
    private int deductionFailTotal;
    private LocalDateTime lastDeductionFailAt;
    private String lastDeductionFailCode;

    /** 违约标记：仅人工确认后写 true，系统建议不改此列。 */
    private boolean defaultFlag;
    private LocalDateTime defaultedAt;
    private String defaultReasonCode;
    private LocalDateTime defaultCuredAt;

    /** 乐观锁版本号。 */
    private int version;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getLoanNo() { return loanNo; }
    public void setLoanNo(String loanNo) { this.loanNo = loanNo; }
    public Long getAdvanceId() { return advanceId; }
    public void setAdvanceId(Long advanceId) { this.advanceId = advanceId; }
    public String getVin() { return vin; }
    public void setVin(String vin) { this.vin = vin; }
    public Long getDealerId() { return dealerId; }
    public void setDealerId(Long dealerId) { this.dealerId = dealerId; }
    public Long getCreditLineId() { return creditLineId; }
    public void setCreditLineId(Long creditLineId) { this.creditLineId = creditLineId; }
    public Long getPurchaseOrderId() { return purchaseOrderId; }
    public void setPurchaseOrderId(Long purchaseOrderId) { this.purchaseOrderId = purchaseOrderId; }
    public long getPrincipal() { return principal; }
    public void setPrincipal(long principal) { this.principal = principal; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getDisbursedAt() { return disbursedAt; }
    public void setDisbursedAt(LocalDateTime disbursedAt) { this.disbursedAt = disbursedAt; }
    public LocalDate getMaturityDate() { return maturityDate; }
    public void setMaturityDate(LocalDate maturityDate) { this.maturityDate = maturityDate; }
    public int getGracePeriodDays() { return gracePeriodDays; }
    public void setGracePeriodDays(int gracePeriodDays) { this.gracePeriodDays = gracePeriodDays; }
    public BigDecimal getOverdueRate() { return overdueRate; }
    public void setOverdueRate(BigDecimal overdueRate) { this.overdueRate = overdueRate; }
    public String getTermSource() { return termSource; }
    public void setTermSource(String termSource) { this.termSource = termSource; }
    public String getTermConfirmedBy() { return termConfirmedBy; }
    public void setTermConfirmedBy(String termConfirmedBy) { this.termConfirmedBy = termConfirmedBy; }
    public LocalDateTime getTermConfirmedAt() { return termConfirmedAt; }
    public void setTermConfirmedAt(LocalDateTime termConfirmedAt) { this.termConfirmedAt = termConfirmedAt; }
    public long getPrincipalOutstanding() { return principalOutstanding; }
    public void setPrincipalOutstanding(long principalOutstanding) { this.principalOutstanding = principalOutstanding; }
    public long getInterestOutstanding() { return interestOutstanding; }
    public void setInterestOutstanding(long interestOutstanding) { this.interestOutstanding = interestOutstanding; }
    public long getPenaltyOutstanding() { return penaltyOutstanding; }
    public void setPenaltyOutstanding(long penaltyOutstanding) { this.penaltyOutstanding = penaltyOutstanding; }
    public long getFeeOutstanding() { return feeOutstanding; }
    public void setFeeOutstanding(long feeOutstanding) { this.feeOutstanding = feeOutstanding; }
    public int getCurrentDpd() { return currentDpd; }
    public void setCurrentDpd(int currentDpd) { this.currentDpd = currentDpd; }
    public String getCurrentStageCode() { return currentStageCode; }
    public void setCurrentStageCode(String currentStageCode) { this.currentStageCode = currentStageCode; }
    public LocalDate getOverdueSince() { return overdueSince; }
    public void setOverdueSince(LocalDate overdueSince) { this.overdueSince = overdueSince; }
    public int getMaxDpdEver() { return maxDpdEver; }
    public void setMaxDpdEver(int maxDpdEver) { this.maxDpdEver = maxDpdEver; }
    public int getOverdueCount() { return overdueCount; }
    public void setOverdueCount(int overdueCount) { this.overdueCount = overdueCount; }
    public LocalDateTime getCurrentStageEnteredAt() { return currentStageEnteredAt; }
    public void setCurrentStageEnteredAt(LocalDateTime currentStageEnteredAt) { this.currentStageEnteredAt = currentStageEnteredAt; }
    public int getDeductionFailStreak() { return deductionFailStreak; }
    public void setDeductionFailStreak(int deductionFailStreak) { this.deductionFailStreak = deductionFailStreak; }
    public int getDeductionFailTotal() { return deductionFailTotal; }
    public void setDeductionFailTotal(int deductionFailTotal) { this.deductionFailTotal = deductionFailTotal; }
    public LocalDateTime getLastDeductionFailAt() { return lastDeductionFailAt; }
    public void setLastDeductionFailAt(LocalDateTime lastDeductionFailAt) { this.lastDeductionFailAt = lastDeductionFailAt; }
    public String getLastDeductionFailCode() { return lastDeductionFailCode; }
    public void setLastDeductionFailCode(String lastDeductionFailCode) { this.lastDeductionFailCode = lastDeductionFailCode; }
    public boolean isDefaultFlag() { return defaultFlag; }
    public void setDefaultFlag(boolean defaultFlag) { this.defaultFlag = defaultFlag; }
    public LocalDateTime getDefaultedAt() { return defaultedAt; }
    public void setDefaultedAt(LocalDateTime defaultedAt) { this.defaultedAt = defaultedAt; }
    public String getDefaultReasonCode() { return defaultReasonCode; }
    public void setDefaultReasonCode(String defaultReasonCode) { this.defaultReasonCode = defaultReasonCode; }
    public LocalDateTime getDefaultCuredAt() { return defaultCuredAt; }
    public void setDefaultCuredAt(LocalDateTime defaultCuredAt) { this.defaultCuredAt = defaultCuredAt; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
