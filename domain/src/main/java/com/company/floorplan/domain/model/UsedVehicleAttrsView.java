package com.company.floorplan.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code vehicle_units} 上 USED 侧列的读视图（V50 那批 + 里程三列）。
 *
 * <p>与 {@link UsedVehicleAttributes} 的差别只有方向：那个是写入载体，本 record 是读取结果，
 * 因此多带三列里程（写入它们走 {@code applyMileageMaster} 而不是属性更新）
 * 与 {@code inspection_certificate_id}（当前在场证的指针，由証書登记动作维护）。</p>
 *
 * <p>为什么要单独建视图而不给 {@link VehicleUnit} 加字段：LEGACY 那个实体被建账、利息、
 * 库龄、处置全链路共用，加 USED 字段等于让每条 LEGACY 查询都要多读十几个 NULL 列，
 * 也让「LEGACY 建账行为一条都不改」这条 V50 硬约束变得无法静态核对。</p>
 *
 * @param mileageSample 把本行的里程三列还原成策略可读的样本（勾稽判定的主数据侧输入）
 */
public record UsedVehicleAttrsView(
        String registrationPlate,
        LocalDate firstRegisteredOn,
        LocalDate inspectionExpiryDate,
        Integer inspectionRemainingMonths,
        RepairHistory repairHistory,
        String repairHistoryDesc,
        Boolean accidentHistoryFlag,
        BigDecimal auctionGrade,
        String saleChannel,
        String warehouseCustodianCode,
        String binLocation,
        Long auctionLotId,
        Long inspectionCertificateId,
        String mileageSource,
        Integer mileage,
        LocalDate mileageCapturedOn) {

    /** 主数据侧的里程样本（{@code evidenceRef} 固定为 {@code VEHICLE_UNIT}，便于差异行回指来源）。 */
    public MileageSample mileageSample() {
        return MileageSample.ofMaster(mileage, mileageSource, mileageCapturedOn);
    }
}
