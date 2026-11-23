package com.company.floorplan.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * USED 主数据属性（{@code vehicle_units} 上由 V50 新增的这批列的写入载体）。
 *
 * <p><b>为什么不直接往 {@link VehicleUnit} 上加字段</b>：那个实体带 LEGACY 状态机与乐观锁、
 * 被新车建账/利息/库龄全链路共用，把 USED 的十来个属性塞进去会让 L4 回归路径
 * （V50 头注硬约束 1：「既有新车建账的默认行为一条都不改」）也带上 USED 语义。
 * 所以 USED 属性独立成 record，由 {@code UsedVehicleMasterRepository#updateUsedAttributes}
 * 单独一条 UPDATE 写，LEGACY 的 insert 路径零改动。</p>
 *
 * <p><b>里程三兄弟不在这里</b>（{@code mileage} / {@code mileage_source} /
 * {@code mileage_captured_on}）：里程的权威写入只能经 {@code MileageResolutionPolicy}
 * 判定后走 {@code applyMileageMaster}——V50 的 {@code ck_vu_mileage_unknown} 要求
 * {@code UNKNOWN} 与 {@code mileage IS NULL} 成对出现，随手 UPDATE 很容易写出矛盾行。</p>
 *
 * <p>金额基准四列（{@code valuation_id} / {@code prudent_value} / {@code ltv_*}）也不在这里：
 * 它们属 R3/R4 估值分档的产物，由那边的仓储写。本 record 一次带上就会变成
 * 「R2 也能改估值」的第二条写入路径。</p>
 *
 * @param registrationPlate         登録番号（ナンバー），V50 列 {@code registration_plate}
 * @param firstRegisteredOn         初度登録年月，V50 列 {@code first_registered_on}
 * @param inspectionExpiryDate      車検満了日的<b>展示值</b>（权威在 {@link VehicleCertificate}），
 *                                  V50 列 {@code inspection_expiry_date}
 * @param inspectionRemainingMonths 車検残存月数——<b>录入值，不由到期日推导</b>。
 *                                  V50 列注释的理由：起算日（今天/建账日/入库日）与取整方向都未定源，
 *                                  在应用层写死一个算法等于替业务定了「残存 1 个月」是 29 天还是 31 天。
 *                                  {@code null}＝未采集，不等于 0。
 * @param repairHistory             修復歴枚举（判定读它）
 * @param repairHistoryDesc         修復歴摘要文本（只是说明，不参与判定）
 * @param accidentHistoryFlag       事故歴标志
 * @param auctionGrade              拍卖场評価点（NUMERIC(3,1)，如 4.5）；本列是事实记录，不含任何分档推导
 * @param saleChannel               销售渠道（RETAIL / WHOLESALE_RESELL / AUCTION_RELIST，V50 CHECK 三值）
 * @param warehouseCustodianCode    实控位置：监管仓代码（逻辑引用 {@code custodians.code}，R6 的比对键）
 * @param binLocation               库位
 * @param auctionLotId              落札场次引用（逻辑引用 {@code auction_lots.id}，R4 才建，故允许 null）
 */
public record UsedVehicleAttributes(
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
        Long auctionLotId) {
}
