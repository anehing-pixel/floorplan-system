package com.company.floorplan.release.model;

import java.time.Instant;

/**
 * 出库指令聚合根（对应 V19 的 {@code release_orders}）。
 *
 * <p><b>不变量</b>：状态只能由 {@code ReleaseStateMachine} 校验后流转；
 * 本类的 {@code setStatus} 仅供持久化层回填，业务代码不得直接调用。
 * 与 {@code VehicleStatus} 的做法一致——白名单是唯一合法入口，
 * 绕过它的直接赋值属于破坏不变量。</p>
 *
 * <p><b>时间类型用 {@link Instant}</b>：表列是 {@code TIMESTAMPTZ}（绝对时间点），
 * 用 {@code LocalDateTime} 会在跨时区/夏令时上产生歧义。
 * Jackson 展示层按 application.yml 的 Asia/Tokyo 时区格式化。</p>
 */
public class ReleaseOrder {

    private Long id;
    private String orderNo;
    private Long redemptionId;
    private Long dealerId;
    private String vin;
    private String certificateNo;
    private Long custodianId;
    private String releaseItems;
    private Instant validUntil;
    private ReleaseStatus status;
    private Instant sentAt;
    private Instant confirmedAt;
    private Instant releasedAt;
    private Instant lienDischargedAt;
    private Instant cancelledAt;
    private Instant issuedAt;
    private String issuedBy;
    private Instant acknowledgedAt;
    private String rejectedReason;
    private String deliveryChannel;
    private Instant deliveredAt;
    private Long receiptId;
    private Instant createdAt;

    public ReleaseOrder() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }

    public Long getRedemptionId() { return redemptionId; }
    public void setRedemptionId(Long redemptionId) { this.redemptionId = redemptionId; }

    public Long getDealerId() { return dealerId; }
    public void setDealerId(Long dealerId) { this.dealerId = dealerId; }

    public String getVin() { return vin; }
    public void setVin(String vin) { this.vin = vin; }

    public String getCertificateNo() { return certificateNo; }
    public void setCertificateNo(String certificateNo) { this.certificateNo = certificateNo; }

    public Long getCustodianId() { return custodianId; }
    public void setCustodianId(Long custodianId) { this.custodianId = custodianId; }

    public String getReleaseItems() { return releaseItems; }
    public void setReleaseItems(String releaseItems) { this.releaseItems = releaseItems; }

    public Instant getValidUntil() { return validUntil; }
    public void setValidUntil(Instant validUntil) { this.validUntil = validUntil; }

    public ReleaseStatus getStatus() { return status; }
    public void setStatus(ReleaseStatus status) { this.status = status; }

    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }

    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }

    public Instant getReleasedAt() { return releasedAt; }
    public void setReleasedAt(Instant releasedAt) { this.releasedAt = releasedAt; }

    public Instant getLienDischargedAt() { return lienDischargedAt; }
    public void setLienDischargedAt(Instant lienDischargedAt) { this.lienDischargedAt = lienDischargedAt; }

    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }

    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }

    public String getIssuedBy() { return issuedBy; }
    public void setIssuedBy(String issuedBy) { this.issuedBy = issuedBy; }

    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public void setAcknowledgedAt(Instant acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }

    public String getRejectedReason() { return rejectedReason; }
    public void setRejectedReason(String rejectedReason) { this.rejectedReason = rejectedReason; }

    public String getDeliveryChannel() { return deliveryChannel; }
    public void setDeliveryChannel(String deliveryChannel) { this.deliveryChannel = deliveryChannel; }

    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }

    public Long getReceiptId() { return receiptId; }
    public void setReceiptId(Long receiptId) { this.receiptId = receiptId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
