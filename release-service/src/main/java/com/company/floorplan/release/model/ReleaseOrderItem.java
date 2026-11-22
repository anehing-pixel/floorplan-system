package com.company.floorplan.release.model;

import java.time.Instant;

/**
 * 指令释放项明细（对应 V19 的 {@code release_order_items} + V24 的 {@code custodian_id}）。
 *
 * <p><b>接收方在明细上，不在主表上</b>：一张指令可能同时放车与放证，两条线交给
 * 不同机构（经销商库 / 凭证保管点）。{@code custodianId} 为本项的实际接收方；
 * 为 {@code null} 时表示继承 {@link ReleaseOrder#getCustodianId()} 主接收方，
 * 兼容 V24 之前只在主表传 ID 的调用方。</p>
 */
public class ReleaseOrderItem {

    private Long id;
    private Long orderId;
    private String vin;
    private ReleaseItemType itemType;
    private String itemNo;
    private Long custodianId;
    private boolean released;
    private Instant releasedAt;
    private Instant createdAt;

    public ReleaseOrderItem() {
    }

    public ReleaseOrderItem(String vin, ReleaseItemType itemType, String itemNo) {
        this.vin = vin;
        this.itemType = itemType;
        this.itemNo = itemNo;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public String getVin() { return vin; }
    public void setVin(String vin) { this.vin = vin; }

    public ReleaseItemType getItemType() { return itemType; }
    public void setItemType(ReleaseItemType itemType) { this.itemType = itemType; }

    public String getItemNo() { return itemNo; }
    public void setItemNo(String itemNo) { this.itemNo = itemNo; }

    public Long getCustodianId() { return custodianId; }
    public void setCustodianId(Long custodianId) { this.custodianId = custodianId; }

    public boolean isReleased() { return released; }
    public void setReleased(boolean released) { this.released = released; }

    public Instant getReleasedAt() { return releasedAt; }
    public void setReleasedAt(Instant releasedAt) { this.releasedAt = releasedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
