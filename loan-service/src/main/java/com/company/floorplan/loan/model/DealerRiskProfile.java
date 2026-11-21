package com.company.floorplan.loan.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 经销商贷后画像 —— 只放聚合指标，不参与任何流程判定。
 *
 * <p><b>三条铁律</b>：① 贷后流程只读 loans，禁止读本表；② 本表不得反向写 loans；
 * ③ 必须可全量重算且带 dataAsOf（数据截止业务日）。停算 48 小时不应影响放款/冻结/催收/处置。</p>
 *
 * <p><b>⚠️ riskTier ≠ riskGrade</b>：本表的 riskTier 是由贷后数据算出的画像分层 A/B/C/D；
 * dealers.risk_grade 是授信评级（影响准入与利率），由人工评定，留在 credit 服务。两套指标并存，严禁混用。</p>
 */
public class DealerRiskProfile {

    private Long id;
    /** 一个经销商一行。 */
    private Long dealerId;

    private int activeLoanCount;
    private long outstandingTotal;
    private BigDecimal creditUsageRatio;
    private LocalDate oldestDueDate;

    /** 当前逾期笔数，按借据计数（不是按车）。 */
    private int currentOverdueCount;
    private long currentOverdueAmount;
    private String currentWorstStage;
    /** MAX(loans.max_dpd_ever)。 */
    private int maxOverdueDays;
    private int overdueCount12m;
    private BigDecimal avgOverdueDays12m;
    private String worstStage12m;

    private int defaultsLast12m;
    private int defaultsTotal;
    private long defaultAmount12m;
    private LocalDateTime lastDefaultAt;
    private Integer daysSinceLastDefault;

    private int deductionFailCount30d;
    /** 分母为 0 时置 null 而非 0。 */
    private BigDecimal deductionFailRate30d;
    private int deductionFailStreakMax;

    /** 贷后画像分层 A/B/C/D（非授信评级）。 */
    private String riskTier;
    private String tierReason;
    private String tierRuleCode;
    private String tierRuleVersion;
    private LocalDateTime scoredAt;

    /** 数据截止业务日：任何画像查询都要能回答"截至哪天"。 */
    private LocalDate dataAsOf;
    private LocalDateTime computedAt;
    private String computedBy;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDealerId() { return dealerId; }
    public void setDealerId(Long dealerId) { this.dealerId = dealerId; }
    public int getActiveLoanCount() { return activeLoanCount; }
    public void setActiveLoanCount(int activeLoanCount) { this.activeLoanCount = activeLoanCount; }
    public long getOutstandingTotal() { return outstandingTotal; }
    public void setOutstandingTotal(long outstandingTotal) { this.outstandingTotal = outstandingTotal; }
    public BigDecimal getCreditUsageRatio() { return creditUsageRatio; }
    public void setCreditUsageRatio(BigDecimal creditUsageRatio) { this.creditUsageRatio = creditUsageRatio; }
    public LocalDate getOldestDueDate() { return oldestDueDate; }
    public void setOldestDueDate(LocalDate oldestDueDate) { this.oldestDueDate = oldestDueDate; }
    public int getCurrentOverdueCount() { return currentOverdueCount; }
    public void setCurrentOverdueCount(int currentOverdueCount) { this.currentOverdueCount = currentOverdueCount; }
    public long getCurrentOverdueAmount() { return currentOverdueAmount; }
    public void setCurrentOverdueAmount(long currentOverdueAmount) { this.currentOverdueAmount = currentOverdueAmount; }
    public String getCurrentWorstStage() { return currentWorstStage; }
    public void setCurrentWorstStage(String currentWorstStage) { this.currentWorstStage = currentWorstStage; }
    public int getMaxOverdueDays() { return maxOverdueDays; }
    public void setMaxOverdueDays(int maxOverdueDays) { this.maxOverdueDays = maxOverdueDays; }
    public int getOverdueCount12m() { return overdueCount12m; }
    public void setOverdueCount12m(int overdueCount12m) { this.overdueCount12m = overdueCount12m; }
    public BigDecimal getAvgOverdueDays12m() { return avgOverdueDays12m; }
    public void setAvgOverdueDays12m(BigDecimal avgOverdueDays12m) { this.avgOverdueDays12m = avgOverdueDays12m; }
    public String getWorstStage12m() { return worstStage12m; }
    public void setWorstStage12m(String worstStage12m) { this.worstStage12m = worstStage12m; }
    public int getDefaultsLast12m() { return defaultsLast12m; }
    public void setDefaultsLast12m(int defaultsLast12m) { this.defaultsLast12m = defaultsLast12m; }
    public int getDefaultsTotal() { return defaultsTotal; }
    public void setDefaultsTotal(int defaultsTotal) { this.defaultsTotal = defaultsTotal; }
    public long getDefaultAmount12m() { return defaultAmount12m; }
    public void setDefaultAmount12m(long defaultAmount12m) { this.defaultAmount12m = defaultAmount12m; }
    public LocalDateTime getLastDefaultAt() { return lastDefaultAt; }
    public void setLastDefaultAt(LocalDateTime lastDefaultAt) { this.lastDefaultAt = lastDefaultAt; }
    public Integer getDaysSinceLastDefault() { return daysSinceLastDefault; }
    public void setDaysSinceLastDefault(Integer daysSinceLastDefault) { this.daysSinceLastDefault = daysSinceLastDefault; }
    public int getDeductionFailCount30d() { return deductionFailCount30d; }
    public void setDeductionFailCount30d(int deductionFailCount30d) { this.deductionFailCount30d = deductionFailCount30d; }
    public BigDecimal getDeductionFailRate30d() { return deductionFailRate30d; }
    public void setDeductionFailRate30d(BigDecimal deductionFailRate30d) { this.deductionFailRate30d = deductionFailRate30d; }
    public int getDeductionFailStreakMax() { return deductionFailStreakMax; }
    public void setDeductionFailStreakMax(int deductionFailStreakMax) { this.deductionFailStreakMax = deductionFailStreakMax; }
    public String getRiskTier() { return riskTier; }
    public void setRiskTier(String riskTier) { this.riskTier = riskTier; }
    public String getTierReason() { return tierReason; }
    public void setTierReason(String tierReason) { this.tierReason = tierReason; }
    public String getTierRuleCode() { return tierRuleCode; }
    public void setTierRuleCode(String tierRuleCode) { this.tierRuleCode = tierRuleCode; }
    public String getTierRuleVersion() { return tierRuleVersion; }
    public void setTierRuleVersion(String tierRuleVersion) { this.tierRuleVersion = tierRuleVersion; }
    public LocalDateTime getScoredAt() { return scoredAt; }
    public void setScoredAt(LocalDateTime scoredAt) { this.scoredAt = scoredAt; }
    public LocalDate getDataAsOf() { return dataAsOf; }
    public void setDataAsOf(LocalDate dataAsOf) { this.dataAsOf = dataAsOf; }
    public LocalDateTime getComputedAt() { return computedAt; }
    public void setComputedAt(LocalDateTime computedAt) { this.computedAt = computedAt; }
    public String getComputedBy() { return computedBy; }
    public void setComputedBy(String computedBy) { this.computedBy = computedBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
