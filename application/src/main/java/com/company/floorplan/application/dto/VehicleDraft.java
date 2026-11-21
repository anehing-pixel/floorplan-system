package com.company.floorplan.application.dto;

import java.math.BigDecimal;

/** 批量到货建账时的单台车辆草稿。 */
public class VehicleDraft {

    /** 车架号（必填，单台核算的业务主键）。 */
    private String vin;

    /** 品牌。 */
    private String brand;

    /** 车型。 */
    private String model;

    /** 年款。 */
    private Integer modelYear;

    /** 里程数。 */
    private Integer mileage;

    /** 车身颜色。 */
    private String color;

    /** 车辆类别（新车 / 二手车 / 商用车）。 */
    private String vehicleType;

    /** 厂家发票金额（整数日元，必填）。 */
    private long invoiceAmount;

    /**
     * 垫款比例；为空时使用系统默认比例（预设 0.90）。
     * 超过配置的上限（预设 1.0）将被拒绝（ADVANCE_001）。
     */
    private BigDecimal advanceRatio;

    public VehicleDraft() {
    }

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getModelYear() {
        return modelYear;
    }

    public void setModelYear(Integer modelYear) {
        this.modelYear = modelYear;
    }

    public Integer getMileage() {
        return mileage;
    }

    public void setMileage(Integer mileage) {
        this.mileage = mileage;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getVehicleType() {
        return vehicleType;
    }

    public void setVehicleType(String vehicleType) {
        this.vehicleType = vehicleType;
    }

    public long getInvoiceAmount() {
        return invoiceAmount;
    }

    public void setInvoiceAmount(long invoiceAmount) {
        this.invoiceAmount = invoiceAmount;
    }

    public BigDecimal getAdvanceRatio() {
        return advanceRatio;
    }

    public void setAdvanceRatio(BigDecimal advanceRatio) {
        this.advanceRatio = advanceRatio;
    }
}
