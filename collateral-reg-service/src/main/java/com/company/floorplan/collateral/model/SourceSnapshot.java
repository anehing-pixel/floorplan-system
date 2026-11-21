package com.company.floorplan.collateral.model;

import java.time.LocalDateTime;

/** 三源上报快照一行：对应 collateral_source_snapshot，供冲突裁决留痕。 */
public class SourceSnapshot {

    private Long id;
    private String vin;
    /** 来源编码，见 SourceSystem.code()；未知源也可存，裁决时忽略。 */
    private String source;
    private RegistrationStatus status;
    private String regNo;
    private String ownerName;
    private Long amount;
    /** 原始上报报文（JSON 文本），争议时自证。 */
    private String rawPayload;
    private LocalDateTime capturedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getVin() { return vin; }
    public void setVin(String vin) { this.vin = vin; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public RegistrationStatus getStatus() { return status; }
    public void setStatus(RegistrationStatus status) { this.status = status; }
    public String getRegNo() { return regNo; }
    public void setRegNo(String regNo) { this.regNo = regNo; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public String getRawPayload() { return rawPayload; }
    public void setRawPayload(String rawPayload) { this.rawPayload = rawPayload; }
    public LocalDateTime getCapturedAt() { return capturedAt; }
    public void setCapturedAt(LocalDateTime capturedAt) { this.capturedAt = capturedAt; }
}
