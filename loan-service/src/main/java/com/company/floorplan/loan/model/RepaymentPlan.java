package com.company.floorplan.loan.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 还款计划期次（V16 repayment_plans，设计稿原名 repayment_schedules）。
 *
 * <p><b>到期的唯一来源</b>：没有还款计划就没有"到期"概念。到期未足额是借据进 OVERDUE 的
 * 唯一入口（ADR-008 §2.5），与库龄递减 aging_plans 是两种形态，不可混用。</p>
 */
public class RepaymentPlan {

    private Long id;
    private Long loanId;
    /** 期号（1 起）。 */
    private int periodNo;
    /** 应还日：逾期起算点 = 本日 + 宽限期。 */
    private LocalDate dueDate;
    private long principalDue;
    private long interestDue;
    private long totalDue;
    private long principalPaid;
    private long interestPaid;
    private LocalDateTime paidAt;
    /** 取值见 {@link RepaymentPlanStatus}。 */
    private String status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getLoanId() { return loanId; }
    public void setLoanId(Long loanId) { this.loanId = loanId; }
    public int getPeriodNo() { return periodNo; }
    public void setPeriodNo(int periodNo) { this.periodNo = periodNo; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public long getPrincipalDue() { return principalDue; }
    public void setPrincipalDue(long principalDue) { this.principalDue = principalDue; }
    public long getInterestDue() { return interestDue; }
    public void setInterestDue(long interestDue) { this.interestDue = interestDue; }
    public long getTotalDue() { return totalDue; }
    public void setTotalDue(long totalDue) { this.totalDue = totalDue; }
    public long getPrincipalPaid() { return principalPaid; }
    public void setPrincipalPaid(long principalPaid) { this.principalPaid = principalPaid; }
    public long getInterestPaid() { return interestPaid; }
    public void setInterestPaid(long interestPaid) { this.interestPaid = interestPaid; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
