package com.company.floorplan.domain.model;

import java.time.LocalDateTime;

/**
 * 权属 / 抵押 / 查封登记核对记录（业务闭环 P0-③ 重复融资拦截）。
 *
 * <p><b>业务定位</b>：建账前必须确认该车未被重复融资——
 * 既不能在<b>本系统</b>已存在（{@code vehicle_units} 唯一约束兜底），
 * 也不能<b>在他处</b>已抵押/查封。日本本地多数无开放接口，
 * 由人工/厂家门户核对后<b>录入本表</b>；若 {@link #encumbered} 为 true，
 * 建账阶段直接拒绝（抛 {@code VEHICLE_005}）。</p>
 *
 * <p><b>checkSource</b>：{@code MANUAL}(人工录入) / {@code OEM_PORTAL}(厂家门户) /
 * {@code REGISTRY_API}(登记系统接口，若客户确认有)。</p>
 */
public class FinancingLienCheck {

    /** 主键。 */
    private Long id;

    /** 车架号。 */
    private String vin;

    /** 核对来源。 */
    private String checkSource;

    /**
     * 是否在他处抵押/查封（true = 存在权属负担，禁止重复融资）。
     */
    private boolean encumbered;

    /** 核对时间。 */
    private LocalDateTime checkedAt;

    /** 备注（如登记编号、来源系统单号）。 */
    private String note;

    /** 操作人。 */
    private String operator;

    public FinancingLienCheck() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public String getCheckSource() {
        return checkSource;
    }

    public void setCheckSource(String checkSource) {
        this.checkSource = checkSource;
    }

    public boolean isEncumbered() {
        return encumbered;
    }

    public void setEncumbered(boolean encumbered) {
        this.encumbered = encumbered;
    }

    public LocalDateTime getCheckedAt() {
        return checkedAt;
    }

    public void setCheckedAt(LocalDateTime checkedAt) {
        this.checkedAt = checkedAt;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }
}
