package com.company.floorplan.release.model;

import java.time.Instant;

/**
 * 指令状态流转历史（对应 V19 的 {@code release_state_history} + V24 的
 * {@code confirmation_authority}）。
 *
 * <p>只追加、不修改：每次状态迁移写一行，回答"谁在何时把状态从 A 推到 B"。
 * {@code fromStatus == null} 表示建单（无前驱状态）。</p>
 *
 * <p><b>确认权威与执行接收方解耦</b>：日本渠道没有第三方实体监管，起步靠人工转交 +
 * 我方见证代录。{@code confirmationAuthority} 记录该次推进"凭什么有权确认"
 * （接收方回执 / 我方见证 / 凭证交出），与 {@code release_receipts} 里的执行方分开，
 * 避免经销商自报回执就能把状态推到 RELEASED。</p>
 */
public class ReleaseStateHistory {

    private Long id;
    private Long orderId;
    private ReleaseStatus fromStatus;
    private ReleaseStatus toStatus;
    private String action;
    private String operator;
    private String reason;
    private String confirmationAuthority;
    private Instant createdAt;

    public ReleaseStateHistory() {
    }

    /** 常规推进（确认权威为空）。 */
    public ReleaseStateHistory(Long orderId, ReleaseStatus fromStatus, ReleaseStatus toStatus,
                               String action, String operator, String reason) {
        this(orderId, fromStatus, toStatus, action, operator, reason, null);
    }

    /** 带确认权威的推进（CONFIRM 动作使用）。 */
    public ReleaseStateHistory(Long orderId, ReleaseStatus fromStatus, ReleaseStatus toStatus,
                               String action, String operator, String reason,
                               String confirmationAuthority) {
        this.orderId = orderId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.action = action;
        this.operator = operator;
        this.reason = reason;
        this.confirmationAuthority = confirmationAuthority;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public ReleaseStatus getFromStatus() { return fromStatus; }
    public void setFromStatus(ReleaseStatus fromStatus) { this.fromStatus = fromStatus; }

    public ReleaseStatus getToStatus() { return toStatus; }
    public void setToStatus(ReleaseStatus toStatus) { this.toStatus = toStatus; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getConfirmationAuthority() { return confirmationAuthority; }
    public void setConfirmationAuthority(String confirmationAuthority) {
        this.confirmationAuthority = confirmationAuthority;
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
