package com.company.floorplan.loan.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 逾期记录（事实流水，append-only 语义）。
 *
 * <p><b>一笔借据同时只有一条未结逾期记录</b>（uk_overdue_loan）。跨 M1→M4 是同一条记录的
 * currentStageCode / maxStageCode 变化，不是新开一条——否则催收工单会重复派发。</p>
 *
 * <p>三列规则快照（stageRuleVersion / dayBasisSnapshot / stageThresholdSnapshot）保证
 * 规则被改之后，历史逾期凭快照仍能复现当时的判定口径。</p>
 */
public class OverdueRecord {

    private Long id;
    private String overdueNo;
    private Long loanId;
    /** 冗余 VIN，便于盘点/催收直接查。 */
    private String vin;
    private Long dealerId;
    /** 触发逾期的还款计划期次 id。 */
    private Long scheduleId;
    /** 逾期起始日（首个未足额期的 due_date + 宽限期）。 */
    private LocalDate overdueDate;
    private int dpd;
    /** 当前阶段 M1/M2/M3/M4。 */
    private String currentStageCode;
    /** 本次逾期达到过的最高阶段。 */
    private String maxStageCode;
    private String stageRuleVersion;
    private String dayBasisSnapshot;
    /** JSON 快照，如 {"M1":30,"M2":60,"M3":90}。 */
    private String stageThresholdSnapshot;
    /** 逾期未还金额（整数日元）。 */
    private long outstanding;
    /** 取值见 {@link OverdueRecordStatus}。 */
    private String status;
    private LocalDateTime escalatedAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOverdueNo() { return overdueNo; }
    public void setOverdueNo(String overdueNo) { this.overdueNo = overdueNo; }
    public Long getLoanId() { return loanId; }
    public void setLoanId(Long loanId) { this.loanId = loanId; }
    public String getVin() { return vin; }
    public void setVin(String vin) { this.vin = vin; }
    public Long getDealerId() { return dealerId; }
    public void setDealerId(Long dealerId) { this.dealerId = dealerId; }
    public Long getScheduleId() { return scheduleId; }
    public void setScheduleId(Long scheduleId) { this.scheduleId = scheduleId; }
    public LocalDate getOverdueDate() { return overdueDate; }
    public void setOverdueDate(LocalDate overdueDate) { this.overdueDate = overdueDate; }
    public int getDpd() { return dpd; }
    public void setDpd(int dpd) { this.dpd = dpd; }
    public String getCurrentStageCode() { return currentStageCode; }
    public void setCurrentStageCode(String currentStageCode) { this.currentStageCode = currentStageCode; }
    public String getMaxStageCode() { return maxStageCode; }
    public void setMaxStageCode(String maxStageCode) { this.maxStageCode = maxStageCode; }
    public String getStageRuleVersion() { return stageRuleVersion; }
    public void setStageRuleVersion(String stageRuleVersion) { this.stageRuleVersion = stageRuleVersion; }
    public String getDayBasisSnapshot() { return dayBasisSnapshot; }
    public void setDayBasisSnapshot(String dayBasisSnapshot) { this.dayBasisSnapshot = dayBasisSnapshot; }
    public String getStageThresholdSnapshot() { return stageThresholdSnapshot; }
    public void setStageThresholdSnapshot(String stageThresholdSnapshot) { this.stageThresholdSnapshot = stageThresholdSnapshot; }
    public long getOutstanding() { return outstanding; }
    public void setOutstanding(long outstanding) { this.outstanding = outstanding; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getEscalatedAt() { return escalatedAt; }
    public void setEscalatedAt(LocalDateTime escalatedAt) { this.escalatedAt = escalatedAt; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
