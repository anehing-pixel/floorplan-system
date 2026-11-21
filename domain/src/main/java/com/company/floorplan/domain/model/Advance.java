package com.company.floorplan.domain.model;

import java.time.LocalDateTime;

/** 垫款记录。金额一律整数日元。 */
public class Advance {

    /** 主键。 */
    private Long id;

    /** 垫款单号（业务唯一，用于对账与对外展示）。 */
    private String advanceNo;

    /** 车架号 —— 关联到单台台账。 */
    private String vin;

    /** 占用的额度 ID（用于还款时的额度回补）。 */
    private Long creditLineId;

    /** 垫款金额（整数日元）。金融公司代付给厂家的购车款，形成对车商的债权。 */
    private long amount;

    /** 实际付款时间。 */
    private LocalDateTime paidAt;

    /** 状态：PENDING 待支付 / PROCESSING 放款中 / PAID 已付款 / FAILED 失败 / VOIDED 已作废 / REDEEMED 已清偿 / REVERSED 已冲正。 */
    private AdvanceStatus status;

    /**
     * 终结时间：进入 VOIDED / REDEEMED / REVERSED 的时刻。
     *
     * <p><b>为什么要单独记</b>：状态只说明"终结了"，说不出"什么时候终结的"。
     * 而额度占用时长、同一台车两次融资的间隔，都要靠这个时间算——
     * 尤其是撤销后重申请，必须能证明前一笔确实在重申请之前就已作废。</p>
     */
    private LocalDateTime closedAt;

    /**
     * 终结原因，用于区分同一终态下的不同业务事实。
     *
     * <p>取值：{@code DEALER_CANCELLED}（经销商主动撤销）/ {@code REVIEW_REJECTED}（风控驳回）
     * / {@code TIMEOUT}（超时未放款）/ {@code SOLD_SETTLED}（售出结清）
     * / {@code EARLY_REDEEMED}（质押中提前赎回）。</p>
     *
     * <p><b>为什么必须区分</b>：VOIDED 与 REDEEMED 的资金事实截然不同
     * （前者从未付款，后者付过又还清）。若只有一个笼统的原因，
     * 财务对账时垫款累计发生额会凭空少掉一整笔。</p>
     */
    private String closedReason;

    /** 外部支付流水号 —— 与银行汇出明细对账的关键字段。 */
    private String paymentRef;

    /** 幂等键（数据库唯一索引），防重复垫款。 */
    private String idempotencyKey;

    /**
     * 关联采购订单 ID（ADR-007 决策一）。
     *
     * <p>垫款挂在采购订单上——打款对象由订单决定（上游类型 + 收款账户）。</p>
     */
    private Long purchaseOrderId;

    /** 放款时冻结的收款上游 ID（不可变事实）。 */
    private Long payeeSupplierId;

    /** 放款时冻结的收款上游类型（不可变事实）。 */
    private String payeeSupplierType;

    /** 放款时冻结的收款账户快照（银行/户名/掩码，不可变事实）。 */
    private String payeeAccountSnapshot;

    /**
     * 垫款申请人（自然人标识，来自服务端验签的令牌身份）。
     *
     * <p><b>为什么记自然人而不只记角色</b>：一人多岗时，
     * 同一个"业务经办岗"可能既发起又复核——角色看不出差异，
     * 但审计要能回答"这笔到底是谁经手的"。三个字段
     * （applicant / reviewer / disburser）构成完整的责任链。</p>
     */
    private String applicant;

    /** 垫款复核人（自然人，须与 applicant 不同）。 */
    private String reviewer;

    /** 复核时间。 */
    private LocalDateTime reviewedAt;

    /** 支付人（自然人，须与 applicant、reviewer 均不同）。 */
    private String disburser;

    public Advance() {
        this.status = AdvanceStatus.PENDING;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAdvanceNo() {
        return advanceNo;
    }

    public void setAdvanceNo(String advanceNo) {
        this.advanceNo = advanceNo;
    }

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public Long getCreditLineId() {
        return creditLineId;
    }

    public void setCreditLineId(Long creditLineId) {
        this.creditLineId = creditLineId;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(LocalDateTime paidAt) {
        this.paidAt = paidAt;
    }

    public AdvanceStatus getStatus() {
        return status;
    }

    public void setStatus(AdvanceStatus status) {
        this.status = status;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(LocalDateTime closedAt) {
        this.closedAt = closedAt;
    }

    public String getClosedReason() {
        return closedReason;
    }

    public void setClosedReason(String closedReason) {
        this.closedReason = closedReason;
    }

    public String getPaymentRef() {
        return paymentRef;
    }

    public void setPaymentRef(String paymentRef) {
        this.paymentRef = paymentRef;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Long getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public void setPurchaseOrderId(Long purchaseOrderId) {
        this.purchaseOrderId = purchaseOrderId;
    }

    public Long getPayeeSupplierId() {
        return payeeSupplierId;
    }

    public void setPayeeSupplierId(Long payeeSupplierId) {
        this.payeeSupplierId = payeeSupplierId;
    }

    public String getPayeeSupplierType() {
        return payeeSupplierType;
    }

    public void setPayeeSupplierType(String payeeSupplierType) {
        this.payeeSupplierType = payeeSupplierType;
    }

    public String getPayeeAccountSnapshot() {
        return payeeAccountSnapshot;
    }

    public void setPayeeAccountSnapshot(String payeeAccountSnapshot) {
        this.payeeAccountSnapshot = payeeAccountSnapshot;
    }

    public String getApplicant() {
        return applicant;
    }

    public void setApplicant(String applicant) {
        this.applicant = applicant;
    }

    public String getReviewer() {
        return reviewer;
    }

    public void setReviewer(String reviewer) {
        this.reviewer = reviewer;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getDisburser() {
        return disburser;
    }

    public void setDisburser(String disburser) {
        this.disburser = disburser;
    }
}
