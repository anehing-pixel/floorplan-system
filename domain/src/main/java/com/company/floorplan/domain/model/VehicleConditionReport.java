package com.company.floorplan.domain.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 車況票实体（R2.2；表 {@code vehicle_condition_reports}，V51 / D04 §3.8，append-only）。
 *
 * <p><b>只能新增，不能改</b>：票面观察是历史事实，更正的写法是补录一张新票
 * （{@code report_type} 相同、{@code inspected_on} 更新），不是 UPDATE 旧票。
 * 本类因此不提供 {@code updatedAt}——V51 的列里也没有该列，这不是省略而是约束的表达。</p>
 *
 * <p><b>与主数据的关系（R2.3 勾稽）</b>：{@code odometerKm} 是实车观测值，
 * 主数据 {@code vehicle_units.mileage} 是权威值，两者不同是常态；
 * 差异超阈值时建工单、<b>不覆盖主数据</b>（D06 §2.3「不一致 = 建差异，不覆盖」）。
 * 因此本类不提供任何"把票面值写回主数据"的方法。</p>
 *
 * <p>{@code sourceMessageId} 是逻辑引用（integration schema 的报文 ID，D04 §4），
 * 跨 schema 不建物理 FK；{@code reportNo} 承载幂等（D00 §13 的 {@code CRR:{report_no}}）。</p>
 */
public class VehicleConditionReport {

    /** 主键。 */
    private Long id;
    /** 车辆 VIN。 */
    private String vin;
    /** 票面番号（幂等锚点）。 */
    private String reportNo;
    /** 票种（検査票／入库复核／自盘）。 */
    private ConditionReportType reportType;
    /** 检查日（索引 {@code idx_vcr_vin_date} 的排序列）。 */
    private LocalDate inspectedOn;
    /** 检查人。 */
    private String inspector;
    /** 车架状况（骨格状态，自由文本列，本包不为其造枚举）。 */
    private String structureCondition;
    /** 修復歴观测值。 */
    private RepairHistoryFlag repairHistory;
    /** 事故歴观测值。 */
    private Boolean accidentHistory;
    /** 改装观测值。 */
    private Boolean modificationFlag;
    /** 内装状态。 */
    private String interiorCondition;
    /** 外装状态。 */
    private String exteriorCondition;
    /** 实车里程观测（勾稽的右操作数）。 */
    private Integer odometerKm;
    /** 票面車検満了日。 */
    private LocalDate inspectionExpiryDate;
    /** リサイクル券。 */
    private Boolean recycleTicketFlag;
    /** 自賠責保険満了日。 */
    private LocalDate compulsoryInsuranceExpiry;
    /** 自動車税状况。 */
    private String automobileTaxStatus;
    /** 4 方位照片路径（JSON 数组文本）。 */
    private String photoPaths;
    /** 附件路径。 */
    private String attachmentPath;
    /** 附件摘要。 */
    private String attachmentDigest;
    /** 来源报文 ID（逻辑引用 integration schema）。 */
    private Long sourceMessageId;
    /** 创建时间。 */
    private Instant createdAt;
    /** 创建人。 */
    private String createdBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getVin() { return vin; }
    public void setVin(String vin) { this.vin = vin; }

    public String getReportNo() { return reportNo; }
    public void setReportNo(String reportNo) { this.reportNo = reportNo; }

    public ConditionReportType getReportType() { return reportType; }
    public void setReportType(ConditionReportType reportType) { this.reportType = reportType; }

    public LocalDate getInspectedOn() { return inspectedOn; }
    public void setInspectedOn(LocalDate inspectedOn) { this.inspectedOn = inspectedOn; }

    public String getInspector() { return inspector; }
    public void setInspector(String inspector) { this.inspector = inspector; }

    public String getStructureCondition() { return structureCondition; }
    public void setStructureCondition(String structureCondition) { this.structureCondition = structureCondition; }

    public RepairHistoryFlag getRepairHistory() { return repairHistory; }
    public void setRepairHistory(RepairHistoryFlag repairHistory) { this.repairHistory = repairHistory; }

    public Boolean getAccidentHistory() { return accidentHistory; }
    public void setAccidentHistory(Boolean accidentHistory) { this.accidentHistory = accidentHistory; }

    public Boolean getModificationFlag() { return modificationFlag; }
    public void setModificationFlag(Boolean modificationFlag) { this.modificationFlag = modificationFlag; }

    public String getInteriorCondition() { return interiorCondition; }
    public void setInteriorCondition(String interiorCondition) { this.interiorCondition = interiorCondition; }

    public String getExteriorCondition() { return exteriorCondition; }
    public void setExteriorCondition(String exteriorCondition) { this.exteriorCondition = exteriorCondition; }

    public Integer getOdometerKm() { return odometerKm; }
    public void setOdometerKm(Integer odometerKm) { this.odometerKm = odometerKm; }

    public LocalDate getInspectionExpiryDate() { return inspectionExpiryDate; }
    public void setInspectionExpiryDate(LocalDate inspectionExpiryDate) { this.inspectionExpiryDate = inspectionExpiryDate; }

    public Boolean getRecycleTicketFlag() { return recycleTicketFlag; }
    public void setRecycleTicketFlag(Boolean recycleTicketFlag) { this.recycleTicketFlag = recycleTicketFlag; }

    public LocalDate getCompulsoryInsuranceExpiry() { return compulsoryInsuranceExpiry; }
    public void setCompulsoryInsuranceExpiry(LocalDate compulsoryInsuranceExpiry) { this.compulsoryInsuranceExpiry = compulsoryInsuranceExpiry; }

    public String getAutomobileTaxStatus() { return automobileTaxStatus; }
    public void setAutomobileTaxStatus(String automobileTaxStatus) { this.automobileTaxStatus = automobileTaxStatus; }

    public String getPhotoPaths() { return photoPaths; }
    public void setPhotoPaths(String photoPaths) { this.photoPaths = photoPaths; }

    public String getAttachmentPath() { return attachmentPath; }
    public void setAttachmentPath(String attachmentPath) { this.attachmentPath = attachmentPath; }

    public String getAttachmentDigest() { return attachmentDigest; }
    public void setAttachmentDigest(String attachmentDigest) { this.attachmentDigest = attachmentDigest; }

    public Long getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(Long sourceMessageId) { this.sourceMessageId = sourceMessageId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
