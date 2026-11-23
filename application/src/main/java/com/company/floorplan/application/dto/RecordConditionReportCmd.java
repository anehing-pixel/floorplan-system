package com.company.floorplan.application.dto;

import java.time.LocalDate;

/**
 * 车况票登记命令（USED R2，UC-R2-2）。
 *
 * <p>{@code reportType} 用 {@code String} 而不是枚举：本命令的入参可能直接来自入站报文
 * （R11 的 I-xx 消息），而「未知的票型要不要降级成自盘」是业务裁决，
 * 降级动作必须有用例层的日志与审计，不能靠 Jackson 反序列化静默完成。
 * 解析与校验在 {@code UsedVehicleMasterApplicationService} 里做。</p>
 *
 * @param vin            車台番号
 * @param reportNo       外部票号；非空时构成幂等键 {@code CRR:{reportNo}}（D06 §2.5）
 * @param reportType     AUCTION_INSPECTION / WMS_INBOUND_RECHECK / OWN_AUDIT
 * @param inspectedOn    检查日
 * @param inspector      检查人／机构
 * @param structureCondition フレーム状況原文
 * @param repairHistory  票面修復歴（NONE/MINOR/MAJOR/UNKNOWN）
 * @param accidentHistoryFlag 事故歴
 * @param modificationFlag    改造
 * @param interiorCondition   内装原文
 * @param exteriorCondition   外装原文
 * @param odometerKm     票面/实车里程观测值
 * @param inspectionExpiryDate 票面车検満了日
 * @param recycleTicketFlag    リサイクル券
 * @param compulsoryInsuranceExpiry 自賠責到期日
 * @param automobileTaxStatus  自動車税原文
 * @param photoPaths     照片路径（JSON 数组文本）
 * @param attachmentPath 附件
 * @param attachmentDigest 附件摘要
 */
public record RecordConditionReportCmd(
        String vin,
        String reportNo,
        String reportType,
        LocalDate inspectedOn,
        String inspector,
        String structureCondition,
        String repairHistory,
        Boolean accidentHistoryFlag,
        Boolean modificationFlag,
        String interiorCondition,
        String exteriorCondition,
        Integer odometerKm,
        LocalDate inspectionExpiryDate,
        Boolean recycleTicketFlag,
        LocalDate compulsoryInsuranceExpiry,
        String automobileTaxStatus,
        String photoPaths,
        String attachmentPath,
        String attachmentDigest) {
}
