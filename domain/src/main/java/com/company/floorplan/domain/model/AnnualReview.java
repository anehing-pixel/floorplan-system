package com.company.floorplan.domain.model;

import java.time.LocalDateTime;

/**
 * 额度年审任务（业务闭环 ⑪ 额度年审与冻结恢复）。
 *
 * <p><b>业务定位</b>：循环授信额度需定期（年度/半年度）复核经销商经营与信用状况。
 * 年审启动时<b>冻结额度</b>（{@code CreditLine.freeze}），复核通过则解冻恢复，
 * 不通过则维持冻结或下调。这是"额度生命周期"的常规闭环，区别于违约冻结。</p>
 *
 * <p><b>status</b>：{@code PENDING}(待启动) / {@code IN_PROGRESS}(复核中) /
 * {@code PASSED}(通过，解冻) / {@code REJECTED}(不通过，维持冻结)。</p>
 */
public class AnnualReview {

    /** 主键。 */
    private Long id;

    /** 年审单号（业务唯一）。 */
    private String reviewNo;

    /** 经销商 ID。 */
    private Long dealerId;

    /** 关联额度 ID。 */
    private Long creditLineId;

    /** 复核周期：{@code ANNUAL}(年度) / {@code HALF_YEARLY}(半年度)。 */
    private String reviewCycle;

    /** 状态。 */
    private String status;

    /** 年审冻结金额（整数日元，启动时记录用于解冻回补）。 */
    private long frozenAmount;

    /** 复核结论备注。 */
    private String resultNote;

    /** 启动时间。 */
    private LocalDateTime initiatedAt;

    /** 完成时间。 */
    private LocalDateTime completedAt;

    public AnnualReview() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getReviewNo() {
        return reviewNo;
    }

    public void setReviewNo(String reviewNo) {
        this.reviewNo = reviewNo;
    }

    public Long getDealerId() {
        return dealerId;
    }

    public void setDealerId(Long dealerId) {
        this.dealerId = dealerId;
    }

    public Long getCreditLineId() {
        return creditLineId;
    }

    public void setCreditLineId(Long creditLineId) {
        this.creditLineId = creditLineId;
    }

    public String getReviewCycle() {
        return reviewCycle;
    }

    public void setReviewCycle(String reviewCycle) {
        this.reviewCycle = reviewCycle;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getFrozenAmount() {
        return frozenAmount;
    }

    public void setFrozenAmount(long frozenAmount) {
        this.frozenAmount = frozenAmount;
    }

    public String getResultNote() {
        return resultNote;
    }

    public void setResultNote(String resultNote) {
        this.resultNote = resultNote;
    }

    public LocalDateTime getInitiatedAt() {
        return initiatedAt;
    }

    public void setInitiatedAt(LocalDateTime initiatedAt) {
        this.initiatedAt = initiatedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
