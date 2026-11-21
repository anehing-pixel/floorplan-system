package com.company.floorplan.domain.model;

import java.time.LocalDateTime;

/** 风险预警。脱信销售（OOT_SUSPECTED）恒为 HIGH。 */
public class Alert {

    /** 主键。 */
    private Long id;

    /** 关联经销商（车辆类告警同时带 vin）。 */
    private Long dealerId;

    /** 关联车辆（可为空，如额度类告警）。 */
    private String vin;

    /**
     * 预警类型：
     * OOT_SUSPECTED(脱信销售嫌疑) / OVERDUE(逾期) / AGING(库龄达阈值) /
     * AUDIT_MISMATCH(盘点差异) / AUDIT_UNEXPECTED(账外车辆) / CREDIT_FROZEN(额度冻结)。
     */
    private String alertType;

    /**
     * 严重级别：HIGH / MEDIUM / LOW。
     *
     * <p><b>约定</b>：脱信销售（OOT_SUSPECTED）恒为 HIGH——
     * 车已售却不还款，是行业最严重的违约。</p>
     */
    private String severity;

    /** 处理状态：OPEN(未处理) / HANDLED(已处理) / CLOSED(已关闭)。 */
    private String status;

    /** 触发依据明细（JSON/文本），供风控复核"为什么报警"。 */
    private String detail;

    /** 产生时间。 */
    private LocalDateTime createdAt;

    public Alert() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDealerId() {
        return dealerId;
    }

    public void setDealerId(Long dealerId) {
        this.dealerId = dealerId;
    }

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public String getAlertType() {
        return alertType;
    }

    public void setAlertType(String alertType) {
        this.alertType = alertType;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
