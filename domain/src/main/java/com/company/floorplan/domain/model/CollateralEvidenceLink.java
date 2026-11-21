package com.company.floorplan.domain.model;

import java.time.LocalDateTime;

/**
 * 担保物证据链条目（合同·登记·放款·盘点·回款·处置按 VIN 串起来）。
 *
 * <p>一条记录 = 一个证据点。串联同一 VIN 的全部条目，
 * 即构成该台车的完整证据链——这是日本让渡担保实务中
 * 担保权人举证能力的来源，也是 2025 年新法施行后
 * 清算与顺位争议中的关键防线。</p>
 */
public class CollateralEvidenceLink {

    /** 主键。 */
    private Long id;

    /** 所属台账 ID。 */
    private Long ledgerId;

    /** 车架号。 */
    private String vin;

    /** 证据类型（EvidenceType 枚举名）。 */
    private String evidenceType;

    /** 证据来源类型：INTERNAL 内部单据 / ATTACHMENT 附件。 */
    private String refType;

    /** 证据引用：内部单据存单号，附件存 attachments.id。 */
    private String refId;

    /** 冗余直连附件 ID（便于按附件反查证据链）。 */
    private Long attachmentId;

    /** 事件发生时间。 */
    private LocalDateTime eventAt;

    /** 备注。 */
    private String note;

    /** 记录人。 */
    private String recordedBy;

    /** 记录时间。 */
    private LocalDateTime createdAt;

    public CollateralEvidenceLink() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getLedgerId() {
        return ledgerId;
    }

    public void setLedgerId(Long ledgerId) {
        this.ledgerId = ledgerId;
    }

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public String getEvidenceType() {
        return evidenceType;
    }

    public void setEvidenceType(String evidenceType) {
        this.evidenceType = evidenceType;
    }

    public String getRefType() {
        return refType;
    }

    public void setRefType(String refType) {
        this.refType = refType;
    }

    public String getRefId() {
        return refId;
    }

    public void setRefId(String refId) {
        this.refId = refId;
    }

    public Long getAttachmentId() {
        return attachmentId;
    }

    public void setAttachmentId(Long attachmentId) {
        this.attachmentId = attachmentId;
    }

    public LocalDateTime getEventAt() {
        return eventAt;
    }

    public void setEventAt(LocalDateTime eventAt) {
        this.eventAt = eventAt;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getRecordedBy() {
        return recordedBy;
    }

    public void setRecordedBy(String recordedBy) {
        this.recordedBy = recordedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
