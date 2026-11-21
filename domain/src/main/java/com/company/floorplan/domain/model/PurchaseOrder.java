package com.company.floorplan.domain.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 采购订单主单（ADR-007 的锚点）。
 *
 * <p><b>它是融资放款的前置主单，不是一个"待解析的文本文件"</b>——
 * 两条导入通道（API 直连 / Excel 批量）产出同一份主单，下游无感知。</p>
 *
 * <p>打款对象由本单决定，不由系统规则决定：订单携带 {@code supplierType} 与
 * 收款账户引用，{@link #derivePayeeMode()} 由类型派生。</p>
 */
public class PurchaseOrder {

    private Long id;
    /** 采购订单号（上游单号）：幂等锚点。 */
    private String orderNo;
    private Long dealerId;
    private Long creditLineId;
    /** DEALER_SELF 场景无外部上游，可空。 */
    private Long supplierId;
    /** 决定打款模式。 */
    private SupplierType supplierType;
    private Long payeeAccountId;
    /** 下单时的账户事实快照（不可变）。 */
    private String payeeAccountSnapshot;
    private long totalAmount;
    private int vehicleCount;
    private LocalDate purchasedAt;
    /** 契约/発注書/請求書 号：只留号 + 原件附件，不解析入库。 */
    private String evidenceNo;
    /** 原始凭证附件外键。 */
    private Long evidenceAttachmentId;
    private PurchaseOrderStatus status;
    private IngestChannel ingestChannel;
    private Long importBatchId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 本单项下车辆（从台账关联查询得到，非独立字段）。 */
    private List<VehicleUnit> vehicles = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }

    public Long getDealerId() { return dealerId; }
    public void setDealerId(Long dealerId) { this.dealerId = dealerId; }

    public Long getCreditLineId() { return creditLineId; }
    public void setCreditLineId(Long creditLineId) { this.creditLineId = creditLineId; }

    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }

    public SupplierType getSupplierType() { return supplierType; }
    public void setSupplierType(SupplierType supplierType) { this.supplierType = supplierType; }

    public Long getPayeeAccountId() { return payeeAccountId; }
    public void setPayeeAccountId(Long payeeAccountId) { this.payeeAccountId = payeeAccountId; }

    public String getPayeeAccountSnapshot() { return payeeAccountSnapshot; }
    public void setPayeeAccountSnapshot(String payeeAccountSnapshot) { this.payeeAccountSnapshot = payeeAccountSnapshot; }

    public long getTotalAmount() { return totalAmount; }
    public void setTotalAmount(long totalAmount) { this.totalAmount = totalAmount; }

    public int getVehicleCount() { return vehicleCount; }
    public void setVehicleCount(int vehicleCount) { this.vehicleCount = vehicleCount; }

    public LocalDate getPurchasedAt() { return purchasedAt; }
    public void setPurchasedAt(LocalDate purchasedAt) { this.purchasedAt = purchasedAt; }

    public String getEvidenceNo() { return evidenceNo; }
    public void setEvidenceNo(String evidenceNo) { this.evidenceNo = evidenceNo; }

    public Long getEvidenceAttachmentId() { return evidenceAttachmentId; }
    public void setEvidenceAttachmentId(Long evidenceAttachmentId) { this.evidenceAttachmentId = evidenceAttachmentId; }

    public PurchaseOrderStatus getStatus() { return status; }
    public void setStatus(PurchaseOrderStatus status) { this.status = status; }

    public IngestChannel getIngestChannel() { return ingestChannel; }
    public void setIngestChannel(IngestChannel ingestChannel) { this.ingestChannel = ingestChannel; }

    public Long getImportBatchId() { return importBatchId; }
    public void setImportBatchId(Long importBatchId) { this.importBatchId = importBatchId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public List<VehicleUnit> getVehicles() { return vehicles; }
    public void setVehicles(List<VehicleUnit> vehicles) { this.vehicles = vehicles; }

    /**
     * 打款模式（派生值，不可人工覆盖）。
     *
     * @return 由 {@code supplierType} 推导
     */
    public PayeeMode derivePayeeMode() {
        return supplierType == null ? PayeeMode.DIRECT_PAY : supplierType.derivePayeeMode();
    }

    /**
     * 是否为代付上游场景。
     *
     * @return true 表示走代付链路（需三方委托付款协议）
     */
    public boolean isDirectPay() {
        return derivePayeeMode() == PayeeMode.DIRECT_PAY;
    }
}
