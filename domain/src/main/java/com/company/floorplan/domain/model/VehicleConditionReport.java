package com.company.floorplan.domain.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 车况票（USED R2.2 / R6.4，{@code vehicle_condition_reports}，V51）。
 *
 * <p>用 record 而非常规实体：V51 表注释写明<b>本表只追加不修改</b>
 * （「更正＝新出一张票，历史票保留」），没有可变状态就没有可变对象。
 * 同一 VIN 多票是正常态，取最新靠 {@code idx_vcr_vin_date}。</p>
 *
 * <p>{@code structureCondition / interiorCondition / exteriorCondition / automobileTaxStatus}
 * 是原始文本（{@code String}）而不是枚举——V51 刻意不给它们建 CHECK，注释理由是
 * 「取值集未定源 ⟹ 不建约束，避免自造枚举」。这里跟随库内口径，不在 Java 侧偷偷收窄。</p>
 *
 * @param id            主键
 * @param vin           車台番号（逻辑引用，<b>不建物理 FK</b>：検査票常在落札当晚先于建账到达）
 * @param reportNo      外部票号；非空时兼作幂等键段 {@code CRR:{reportNo}}（V51 部分唯一索引）
 * @param reportType    三型之一，见 {@link ConditionReportType}
 * @param inspectedOn   检查日（同日多票按 id 后到为准）
 * @param inspector     检查人／机构（票面记载，自由文本）
 * @param structureCondition フレーム状況原文
 * @param repairHistory 票面修復歴（取严动作在应用层，本列只存票面事实）
 * @param accidentHistoryFlag 事故歴标志
 * @param modificationFlag    改造标志
 * @param interiorCondition   内装状態原文
 * @param exteriorCondition   外装状態原文
 * @param odometerKm    票面/实车里程观测值——与主数据勾稽的输入，<b>不直接回写主数据</b>
 * @param inspectionExpiryDate 票面车検満了日
 * @param recycleTicketFlag    リサイクル券持有（过户前置要件）
 * @param compulsoryInsuranceExpired 自賠責保険到期日
 * @param automobileTaxStatus  自動車税状况原文
 * @param photoPaths    4 方位照片路径（JSON 数组文本，一期本地文件）
 * @param attachmentPath 附件路径
 * @param attachmentDigest 附件摘要
 * @param sourceMessageId 入站报文溯源（R11，逻辑引用，无 FK）
 * @param createdAt     落库时间
 * @param createdBy     录入人（令牌自然人，不从请求体自报）
 */
public record VehicleConditionReport(
        Long id,
        String vin,
        String reportNo,
        ConditionReportType reportType,
        LocalDate inspectedOn,
        String inspector,
        String structureCondition,
        RepairHistory repairHistory,
        Boolean accidentHistoryFlag,
        Boolean modificationFlag,
        String interiorCondition,
        String exteriorCondition,
        Integer odometerKm,
        LocalDate inspectionExpiryDate,
        Boolean recycleTicketFlag,
        LocalDate compulsoryInsuranceExpired,
        String automobileTaxStatus,
        String photoPaths,
        String attachmentPath,
        String attachmentDigest,
        Long sourceMessageId,
        OffsetDateTime createdAt,
        String createdBy) {

    /** 该票能否作为里程观测参与勾稽（没读到数的票只留证据，不产生差异）。 */
    public boolean hasOdometerReading() {
        return odometerKm != null;
    }

    /** 幂等键段（D00 §13.1）：自盘票无外部票号时返回 {@code null}，由调用方改用显式 Idempotency-Key。 */
    public String idempotencySegment() {
        return reportNo == null || reportNo.isBlank() ? null : "CRR:" + reportNo;
    }
}
