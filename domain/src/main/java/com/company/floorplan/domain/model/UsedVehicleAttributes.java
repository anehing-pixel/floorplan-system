package com.company.floorplan.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * USED 车辆属性视图（R2.1 建账；列规格 = D04 §5.1，落库在 V50 给
 * {@code vehicle_units} 加的列上）。
 *
 * <p><b>为什么不把这些列塞进现有 {@code VehicleUnit}</b>：{@code VehicleUnit} 与
 * {@code JdbcVehicleUnitRepository} 是 LEGACY 建账/放款/结清全链路共用的类型与语句。
 * 往实体加 USED 字段就要改那份 INSERT/SELECT 列表，L4「LEGACY 建账回归不破」的门禁
 * 本轮又不允许跑（用户指令：不编译、不冒烟、不连库）——在这种条件下改共用类，
 * 等于把未验证的风险塞进新车链路。本类与 {@code UsedVehicleAttributesRepository}
 * 只读写 V50 新增列，按 {@code vin} 定位同一行，LEGACY 的语句一行不动。</p>
 *
 * <p><b>金额基准（R3.1）在本类里只体现为"可写入的字段定义"</b>：
 * {@code valuationId} / {@code prudentValue} / {@code ltvRatioSnapshot} 等列由 W-U2 的算法侧写入；
 * 本包不实现任何估值或 LTV 计算（口径唯一出处是 D08），也不给它们填默认值。</p>
 *
 * <p><b>{@code vehicleType} 保持字符串</b>：既有链路
 * （{@code VehicleDraft#setVehicleType(String)} → {@code VehicleController#batchIngest}）
 * 传的就是字符串，本包引入 {@code VehicleType} 枚举会连带改动 §6 保护区的调用链，
 * 因此路由键仍以字符串 + V40 的 {@code ck_vu_vehicle_type} 双层约束表达。</p>
 */
public class UsedVehicleAttributes {

    /** 定位键：VIN（{@code vehicle_units.vin}，唯一）。 */
    private String vin;

    /** 车辆域路由键：USED 分支写 {@code USED}。 */
    private String vehicleType;

    /** 登録番号（车牌）。 */
    private String registrationPlate;

    /** 初度登録年月（车龄输入）。 */
    private LocalDate firstRegisteredOn;

    /** 車検満了日。 */
    private LocalDate inspectionExpiryDate;

    /** 車検残存月数（快照列：由 {@code inspectionExpiryDate} 与业务日派生后落库）。 */
    private Integer inspectionRemainingMonths;

    /** 里程权威值（既有列 {@code vehicle_units.mileage}，本包不另建 mileage_km）。 */
    private Integer mileage;

    /** 里程单位，本期恒 {@code KM}。 */
    private String mileageUnit;

    /** 里程来源。 */
    private MileageSource mileageSource;

    /** 里程采集时点。 */
    private LocalDate mileageCapturedOn;

    /** 修復歴枚举值（判定读它，不读摘要）。 */
    private RepairHistoryFlag repairHistory;

    /** 修復歴摘要文本。 */
    private String repairHistoryDesc;

    /** 事故歴。 */
    private Boolean accidentHistoryFlag;

    /** 拍卖场評価点（如 4.5；纯事实记录，不含分档推导）。 */
    private BigDecimal auctionGrade;

    /** 来源落札对象（逻辑引用 {@code auction_lots.id}，W-U2 的 V60）。 */
    private Long auctionLotId;

    /** 售出渠道（结算窗口与回款路径的分支键，推导归 W-U3/W-U6）。 */
    private SaleChannel saleChannel;

    /** 一车一证指向的当前有効車検証 ID（V53 已加物理 FK）。 */
    private Long inspectionCertificateId;

    /** 实控位置：监管仓代码（逻辑引用 {@code custodians}，W-U4 的 V65）。 */
    private String warehouseCustodianCode;

    /** 实控位置：车位。 */
    private String binLocation;

    /** WMS 入库确认时间（写入口属 R11 入站，本包只读）。 */
    private Instant wmsConfirmedAt;

    /** 金额基准来源记录 ID（逻辑引用 {@code vehicle_valuations}，W-U2 的 V60）。 */
    private Long valuationId;

    /** 审慎值（放款时点快照，JPY 整数）。 */
    private Long prudentValue;

    /**
     * LTV 比率的展示投影。
     *
     * <p><b>非权威</b>：判定、对账与断言读 {@code advances.ltv_ratio_snapshot}（V58），
     * 因为同 VIN 多笔垫款时车辆侧这一份会被第二笔覆盖（V50 列注释原文）。
     * 本字段不得出现在任何谓词里。</p>
     */
    private BigDecimal ltvRatioSnapshot;

    /** 当时命中的 LTV 政策代码。 */
    private String ltvPolicyCode;

    /** 当时命中的 LTV 政策版本。 */
    private Integer ltvPolicyVersion;

    /** 乐观锁版本（既有列 {@code vehicle_units.version}，本包写 USED 列时一并 CAS）。 */
    private Integer version;

    public String getVin() { return vin; }
    public void setVin(String vin) { this.vin = vin; }

    public String getVehicleType() { return vehicleType; }
    public void setVehicleType(String vehicleType) { this.vehicleType = vehicleType; }

    public String getRegistrationPlate() { return registrationPlate; }
    public void setRegistrationPlate(String registrationPlate) { this.registrationPlate = registrationPlate; }

    public LocalDate getFirstRegisteredOn() { return firstRegisteredOn; }
    public void setFirstRegisteredOn(LocalDate firstRegisteredOn) { this.firstRegisteredOn = firstRegisteredOn; }

    public LocalDate getInspectionExpiryDate() { return inspectionExpiryDate; }
    public void setInspectionExpiryDate(LocalDate inspectionExpiryDate) { this.inspectionExpiryDate = inspectionExpiryDate; }

    public Integer getInspectionRemainingMonths() { return inspectionRemainingMonths; }
    public void setInspectionRemainingMonths(Integer inspectionRemainingMonths) { this.inspectionRemainingMonths = inspectionRemainingMonths; }

    public Integer getMileage() { return mileage; }
    public void setMileage(Integer mileage) { this.mileage = mileage; }

    public String getMileageUnit() { return mileageUnit; }
    public void setMileageUnit(String mileageUnit) { this.mileageUnit = mileageUnit; }

    public MileageSource getMileageSource() { return mileageSource; }
    public void setMileageSource(MileageSource mileageSource) { this.mileageSource = mileageSource; }

    public LocalDate getMileageCapturedOn() { return mileageCapturedOn; }
    public void setMileageCapturedOn(LocalDate mileageCapturedOn) { this.mileageCapturedOn = mileageCapturedOn; }

    public RepairHistoryFlag getRepairHistory() { return repairHistory; }
    public void setRepairHistory(RepairHistoryFlag repairHistory) { this.repairHistory = repairHistory; }

    public String getRepairHistoryDesc() { return repairHistoryDesc; }
    public void setRepairHistoryDesc(String repairHistoryDesc) { this.repairHistoryDesc = repairHistoryDesc; }

    public Boolean getAccidentHistoryFlag() { return accidentHistoryFlag; }
    public void setAccidentHistoryFlag(Boolean accidentHistoryFlag) { this.accidentHistoryFlag = accidentHistoryFlag; }

    public BigDecimal getAuctionGrade() { return auctionGrade; }
    public void setAuctionGrade(BigDecimal auctionGrade) { this.auctionGrade = auctionGrade; }

    public Long getAuctionLotId() { return auctionLotId; }
    public void setAuctionLotId(Long auctionLotId) { this.auctionLotId = auctionLotId; }

    public SaleChannel getSaleChannel() { return saleChannel; }
    public void setSaleChannel(SaleChannel saleChannel) { this.saleChannel = saleChannel; }

    public Long getInspectionCertificateId() { return inspectionCertificateId; }
    public void setInspectionCertificateId(Long inspectionCertificateId) { this.inspectionCertificateId = inspectionCertificateId; }

    public String getWarehouseCustodianCode() { return warehouseCustodianCode; }
    public void setWarehouseCustodianCode(String warehouseCustodianCode) { this.warehouseCustodianCode = warehouseCustodianCode; }

    public String getBinLocation() { return binLocation; }
    public void setBinLocation(String binLocation) { this.binLocation = binLocation; }

    public Instant getWmsConfirmedAt() { return wmsConfirmedAt; }
    public void setWmsConfirmedAt(Instant wmsConfirmedAt) { this.wmsConfirmedAt = wmsConfirmedAt; }

    public Long getValuationId() { return valuationId; }
    public void setValuationId(Long valuationId) { this.valuationId = valuationId; }

    public Long getPrudentValue() { return prudentValue; }
    public void setPrudentValue(Long prudentValue) { this.prudentValue = prudentValue; }

    public BigDecimal getLtvRatioSnapshot() { return ltvRatioSnapshot; }
    public void setLtvRatioSnapshot(BigDecimal ltvRatioSnapshot) { this.ltvRatioSnapshot = ltvRatioSnapshot; }

    public String getLtvPolicyCode() { return ltvPolicyCode; }
    public void setLtvPolicyCode(String ltvPolicyCode) { this.ltvPolicyCode = ltvPolicyCode; }

    public Integer getLtvPolicyVersion() { return ltvPolicyVersion; }
    public void setLtvPolicyVersion(Integer ltvPolicyVersion) { this.ltvPolicyVersion = ltvPolicyVersion; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
