package com.company.floorplan.collateral.model;

import java.time.LocalDateTime;

/** 权属流水一行：对应 collateral_registration_history 的追加记录。 */
public class RegistrationItem {

    private Long id;
    private String vin;
    private RegistrationEventType eventType;
    private String regNo;
    private String ownerName;
    private RegistrationStatus status;
    private Long amount;
    private String source;
    private String note;
    private LocalDateTime occurredAt;

    /** 从当前权属快照生成一条流水。 */
    public static RegistrationItem of(String vin, RegistrationEventType eventType, Registration current,
                                      String source, String note) {
        RegistrationItem item = new RegistrationItem();
        item.vin = vin;
        item.eventType = eventType;
        item.regNo = current.getRegNo();
        item.ownerName = current.getOwnerName();
        item.status = current.getStatus();
        item.amount = current.getAmount();
        item.source = source;
        item.note = note;
        item.occurredAt = LocalDateTime.now();
        return item;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getVin() { return vin; }
    public void setVin(String vin) { this.vin = vin; }
    public RegistrationEventType getEventType() { return eventType; }
    public void setEventType(RegistrationEventType eventType) { this.eventType = eventType; }
    public String getRegNo() { return regNo; }
    public void setRegNo(String regNo) { this.regNo = regNo; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public RegistrationStatus getStatus() { return status; }
    public void setStatus(RegistrationStatus status) { this.status = status; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
}
