package com.company.floorplan.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * USED 建账命令（USED R2，UC-R2-1，D06 §2.1「必填 14 项」）。
 *
 * <p>放在 application 层而不是复用 web 的请求体：用例输入形状属应用契约
 * （HTTP、USED 导入模板 UC-R2-4、拍卖落札自动建账 R4 都要走同一个命令），
 * 换入口不该改用例签名。与 {@link LicenseDraft} 同一理由。</p>
 *
 * <p><b>必填项的判定写在用例里，不在这里</b>（record 不做校验是刻意的）：
 * 「哪些字段缺了就<b>不能建账</b>」是业务口径，将来导入路径也要复用同一套判定，
 * 放在构造器里就会变成只有 HTTP 入口才校验。</p>
 *
 * @param vin             車台番号（幂等键 {@code VEH:{vin}}，D06 §2.5）
 * @param dealerId        所属车商
 * @param vehicleType     分流键；本命令只接受 {@code USED}（缺失即 400，D06 §2 验收点）
 * @param purchaseChannel 购入渠道；USED 域合法三值 AUCTION / TRADE_IN / WHOLESALE
 * @param brand           メーカー
 * @param model           车型
 * @param modelYear       年式
 * @param color           车身色
 * @param invoiceAmount   发票/落札价（JPY 整数）；<b>USED 域不得作为放款基准</b>（V50 列注释）
 * @param supplierName    卖家（拍卖场/批售商/前车主）
 * @param registrationPlate 登録番号（ナンバー）
 * @param firstRegisteredOn 初度登録年月
 * @param inspectionExpiryDate 車検満了日
 * @param inspectionRemainingMonths 車検残存月数（录入值，允许 null）
 * @param mileageKm       里程
 * @param mileageSource   里程来源（五值，见 {@code MileageSource}）；传字符串而非枚举，
 *                        非法词要由用例翻成 {@code VALID_001}（回「哪个字段、能填什么」），
 *                        不能让 Jackson 在反序列化阶段抛它自己的错
 * @param mileageCapturedOn 里程采集时点
 * @param repairHistory   修復歴（字符串，理由同 {@code mileageSource}）；
 *                        {@code null}＝从未采集，与显式 {@code UNKNOWN}（票面写明不明）是两回事
 * @param repairHistoryDesc 修復歴摘要
 * @param accidentHistoryFlag 事故歴
 * @param auctionGrade    評価点（NUMERIC(3,1)）
 * @param auctionLotId    落札引用（R4 未建，允许 null）
 * @param saleChannel     销售渠道
 * @param warehouseCustodianCode 监管仓
 * @param binLocation     库位
 */
public record RegisterUsedVehicleCmd(
        String vin,
        Long dealerId,
        String vehicleType,
        String purchaseChannel,
        String brand,
        String model,
        Integer modelYear,
        String color,
        Long invoiceAmount,
        String supplierName,
        String registrationPlate,
        LocalDate firstRegisteredOn,
        LocalDate inspectionExpiryDate,
        Integer inspectionRemainingMonths,
        Integer mileageKm,
        String mileageSource,
        LocalDate mileageCapturedOn,
        String repairHistory,
        String repairHistoryDesc,
        Boolean accidentHistoryFlag,
        BigDecimal auctionGrade,
        Long auctionLotId,
        String saleChannel,
        String warehouseCustodianCode,
        String binLocation) {
}
