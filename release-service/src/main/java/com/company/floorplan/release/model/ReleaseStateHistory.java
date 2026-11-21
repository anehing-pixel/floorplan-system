package com.company.floorplan.release.model;

import java.time.Instant;

/**
 * 指令状态流转历史（对应 V19 的 {@code release_state_history}）。
 *
 * <p>只追加、不修改：每次状态迁移写一行，回答"谁在何时把状态从 A 推到 B"。
 * {@code fromStatus == null} 表示建单（无前驱状态）。</p>
 */
public class ReleaseStateHistory {

    private Long id;
    private Long orderId;
    private ReleaseStatus fromStatus;
    private ReleaseStatus toStatus;
    private String action;
    private String operator;
    private String reason;
    private Instant createdAt;

    public ReleaseStateHistory() {
    }

    public ReleaseStateHistory(Long orderId, ReleaseStatus fromStatus, ReleaseStatus toStatus,
                               String action, String operator, String reason) {
        this.orderId = orderId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.action = action;
        this.operator = operator;
        this.reason = reason;
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

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
