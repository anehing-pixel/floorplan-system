package com.company.floorplan.domain.model;

import java.time.LocalDate;

/**
 * 展期续贷请求（业务闭环 P1：延长结算窗口）。
 *
 * <p><b>业务定位</b>：车商零售成交后，因客户贷款审批、过户等客观原因，
 * 无法在结算窗口内结清时，可申请展期，将 {@code settle_due_date} 顺延
 * {@code extDays} 天。展期后即视为"新的到期日"，不再触发超窗口告警
 * （除非再次超过新到期日）。</p>
 *
 * <p><b>status</b>：{@code APPROVED}(已批准) / {@code REJECTED}(驳回)。</p>
 */
public class ExtensionRequest {

    /** 主键。 */
    private Long id;

    /** 展期单号（业务唯一）。 */
    private String requestNo;

    /** 车架号。 */
    private String vin;

    /** 展期天数。 */
    private int extDays;

    /** 申请原因。 */
    private String reason;

    /** 状态。 */
    private String status;

    /** 原结算到期日。 */
    private LocalDate originalDue;

    /** 展期后新到期日。 */
    private LocalDate newDue;

    public ExtensionRequest() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRequestNo() {
        return requestNo;
    }

    public void setRequestNo(String requestNo) {
        this.requestNo = requestNo;
    }

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public int getExtDays() {
        return extDays;
    }

    public void setExtDays(int extDays) {
        this.extDays = extDays;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDate getOriginalDue() {
        return originalDue;
    }

    public void setOriginalDue(LocalDate originalDue) {
        this.originalDue = originalDue;
    }

    public LocalDate getNewDue() {
        return newDue;
    }

    public void setNewDue(LocalDate newDue) {
        this.newDue = newDue;
    }
}
