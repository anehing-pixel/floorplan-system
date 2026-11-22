package com.company.floorplan.collateral.model;

import java.time.LocalDateTime;

/**
 * 某 VIN 的当前担保权属——权威表 {@code collateral_registration} 的一行。
 *
 * <p>ADR-Q27：本对象只表达 V18 投影表的"当前事实"，不含判定规则。
 * 门禁判定由 service 直读 V10 权威源完成；三源策略降级为对账诊断，不改本表。</p>
 */
public class Registration {

    private String vin;
    private String ownerName;
    private String regNo;
    private RegistrationStatus status = RegistrationStatus.NONE;
    private Long amount;
    private LocalDateTime registeredAt;
    private LocalDateTime dischargedAt;
    private String source;
    private LocalDateTime updatedAt;

    public Registration() {
    }

    /** 查无记录时的返回值：门禁据此 fail-closed 拒付。 */
    public static Registration none(String vin) {
        Registration r = new Registration();
        r.vin = vin;
        r.status = RegistrationStatus.NONE;
        return r;
    }

    /** 登记成功：置 REGISTERED，抹消时间清空。 */
    public static Registration registered(String vin, String ownerName, String regNo, Long amount,
                                          LocalDateTime registeredAt, String source) {
        Registration r = new Registration();
        r.vin = vin;
        r.ownerName = ownerName;
        r.regNo = regNo;
        r.amount = amount;
        r.status = RegistrationStatus.REGISTERED;
        r.registeredAt = registeredAt == null ? LocalDateTime.now() : registeredAt;
        r.dischargedAt = null;
        r.source = source;
        r.updatedAt = LocalDateTime.now();
        return r;
    }

    /** 抹消：返回副本，不改原对象（便于流水记录变更前状态）。 */
    public Registration discharged(LocalDateTime at) {
        Registration r = copyOf(this);
        r.status = RegistrationStatus.CANCELLED;
        r.dischargedAt = at == null ? LocalDateTime.now() : at;
        r.updatedAt = LocalDateTime.now();
        return r;
    }

    /** 用策略裁决结果覆盖当前权属（仅当策略启用时由 service 调用）。 */
    public Registration adopt(String status, String regNo, String ownerName, Long amount,
                              String source, LocalDateTime registeredAt) {
        Registration r = copyOf(this);
        r.status = RegistrationStatus.fromCode(status);
        r.regNo = regNo;
        r.ownerName = ownerName;
        r.amount = amount;
        r.source = source;
        if (registeredAt != null) {
            r.registeredAt = registeredAt;
        }
        r.updatedAt = LocalDateTime.now();
        return r;
    }

    public static Registration copyOf(Registration o) {
        Registration r = new Registration();
        r.vin = o.vin;
        r.ownerName = o.ownerName;
        r.regNo = o.regNo;
        r.status = o.status;
        r.amount = o.amount;
        r.registeredAt = o.registeredAt;
        r.dischargedAt = o.dischargedAt;
        r.source = o.source;
        r.updatedAt = o.updatedAt;
        return r;
    }

    public String getVin() { return vin; }
    public void setVin(String vin) { this.vin = vin; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public String getRegNo() { return regNo; }
    public void setRegNo(String regNo) { this.regNo = regNo; }
    public RegistrationStatus getStatus() { return status; }
    public void setStatus(RegistrationStatus status) { this.status = status; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public LocalDateTime getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(LocalDateTime registeredAt) { this.registeredAt = registeredAt; }
    public LocalDateTime getDischargedAt() { return dischargedAt; }
    public void setDischargedAt(LocalDateTime dischargedAt) { this.dischargedAt = dischargedAt; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
