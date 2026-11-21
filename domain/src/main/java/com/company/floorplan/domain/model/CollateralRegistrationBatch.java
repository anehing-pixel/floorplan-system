package com.company.floorplan.domain.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 担保登记批次（一次法务局登记，涵盖一批车）。
 *
 * <p><b>为什么是"批次"而不是"一台车一笔"</b>：日本实务是
 * <b>批量登记</b>——审核人员与经销商签<b>一笔</b>让渡担保合同，
 * 向东京法务局动产登记课做<b>一次</b>登记，取得<b>一个</b>登记编号，
 * 再把这个编号与合同文件管理到这一批车上。
 * 所以<b>登记编号、担保合同、登记事项证明书都是批次级的</b>，
 * 逐台车只是"属于哪一批"的明细。</p>
 *
 * <p><b>两类依据缺一不可</b>：</p>
 * <ul>
 *   <li><b>担保合同</b>（{@code contractAttachmentId}）——担保权成立的<b>实体依据</b>；
 *       没有合同，登记编号再漂亮也主张不了担保权；</li>
 *   <li><b>登记编号 + 登记事项证明书</b>——担保权成立的<b>公示依据</b>；
 *       没有登记，第三方无法查证，对抗要件不成立。</li>
 * </ul>
 *
 * <p><b>法律要点（决定字段设计，勿随意删列）</b>：</p>
 * <ul>
 *   <li>让渡担保登记有 <b>5 年期限且不可续展</b>——到期须重新设定，
 *       故 {@code expiresAt} 必存；</li>
 *   <li>抹消登记<b>需权利人与登记义务人共同申请</b>——
 *       故抹消是三段式（待申请 → 已提交 → 完成），不是一步到位；</li>
 *   <li>2025 年新法（2025-05-30 成立、2025-06-06 公布，
 *       <b>施行日最晚 2027-12-06</b>，现尚未施行）
 *       明确<b>占有改定劣后于登记</b>（同法第 36 条）——
 *       故 {@code perfectionMethod} 单列，可算顺位风险。</li>
 * </ul>
 */
public class CollateralRegistrationBatch {

    /** 主键。 */
    private Long id;

    /** 内部批次号（系统生成，非法务局编号）。 */
    private String batchNo;

    /** 法务省登记编号（字符串；东京法务局动产登记课出具）。 */
    private String registrationNo;

    /** 担保方式（SecurityType 枚举名）。 */
    private String registrationType;

    /** 对抗要件方式（PerfectionMethod 枚举名）。 */
    private String perfectionMethod;

    /** 登记机关（如"東京法務局 動産登記課"）。 */
    private String registryOffice;

    /** 登记日。 */
    private LocalDateTime registeredAt;

    /** 登记失效日（5 年期限，不可续展）。 */
    private LocalDateTime expiresAt;

    /* ---- 担保合同（批次级，S3 存证） ---- */

    /** 担保合同附件 ID（对象存储）。 */
    private Long contractAttachmentId;

    /** 合同编号。 */
    private String contractNo;

    /** 合同签署日期。 */
    private LocalDateTime contractSignedAt;

    /* ---- 登记事项证明书（批次级） ---- */

    /** 登记事项证明书附件 ID。 */
    private Long certificateAttachmentId;

    /** 证明书编号。 */
    private String certificateNo;

    /** 证明书上传时间。 */
    private LocalDateTime certificateUploadedAt;

    /* ---- 抹消登记 ---- */

    /** 抹消状态（CancellationStatus 枚举名）。 */
    private String cancellationStatus;

    /** 抹消登记编号。 */
    private String cancellationNo;

    /** 提交抹消申请时间。 */
    private LocalDateTime cancellationAppliedAt;

    /** 抹消完成时间。 */
    private LocalDateTime cancellationCompletedAt;

    /** 抹消凭证附件 ID。 */
    private Long cancellationCertificateAttachmentId;

    /** 发起抹消待办的操作人。 */
    private String cancellationRequestedBy;

    /** 抹消待办说明。 */
    private String cancellationNote;

    /* ---- 其他 ---- */

    /** 顺位（1 为优先）。 */
    private Integer priorityRank;

    /** 共同申请对方（登记义务人＝经销商）。 */
    private String coSignedBy;

    /** 本批次车数。 */
    private int vehicleCount;

    /** 建档人。 */
    private String createdBy;

    /** 建档时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;

    public CollateralRegistrationBatch() {
    }

    /**
     * 登记是否即将/已经到期（5 年期限不可续展）。
     *
     * @param now      当前时点
     * @param warnDays 预警提前天数
     * @return true 表示在预警窗口内或已过期
     */
    public boolean isExpiringSoon(LocalDateTime now, int warnDays) {
        if (expiresAt == null) {
            return false;
        }
        return !expiresAt.isAfter(now.plusDays(warnDays));
    }

    /**
     * 担保合同是否已归档。
     *
     * <p><b>合同缺位就是担保权缺位</b>——这是本批次最严重的凭证缺口，
     * 比证明书缺失更致命：没有合同，登记编号主张不了担保权。</p>
     *
     * @return true 表示合同已归档
     */
    public boolean hasContract() {
        return contractAttachmentId != null || (contractNo != null && !contractNo.isBlank());
    }

    /**
     * 登记事项证明书是否已归档。
     *
     * @return true 表示证明书已归档
     */
    public boolean hasCertificate() {
        return certificateAttachmentId != null || (certificateNo != null && !certificateNo.isBlank());
    }

    /**
     * 抹消是否真正完成（含凭证归档）。
     *
     * @return true 表示状态已完成且抹消凭证已归档
     */
    public boolean isCancellationCompletedWithProof() {
        return CancellationStatus.DONE.name().equals(cancellationStatus)
                && cancellationCertificateAttachmentId != null;
    }

    /**
     * 本批次是否存在凭证缺口（合同或证明书缺失）。
     *
     * @return 缺口说明；无缺口返回 null
     */
    public String evidenceGap() {
        List<String> missing = new ArrayList<>();
        if (!hasContract()) {
            missing.add("担保合同未归档（实体依据缺失）");
        }
        if (!hasCertificate()) {
            missing.add("登记事项证明书未归档（公示依据缺失）");
        }
        if (CancellationStatus.DONE.name().equals(cancellationStatus)
                && cancellationCertificateAttachmentId == null) {
            missing.add("抹消已完成但抹消凭证未归档");
        }
        return missing.isEmpty() ? null : String.join("；", missing);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public String getRegistrationNo() {
        return registrationNo;
    }

    public void setRegistrationNo(String registrationNo) {
        this.registrationNo = registrationNo;
    }

    public String getRegistrationType() {
        return registrationType;
    }

    public void setRegistrationType(String registrationType) {
        this.registrationType = registrationType;
    }

    public String getPerfectionMethod() {
        return perfectionMethod;
    }

    public void setPerfectionMethod(String perfectionMethod) {
        this.perfectionMethod = perfectionMethod;
    }

    public String getRegistryOffice() {
        return registryOffice;
    }

    public void setRegistryOffice(String registryOffice) {
        this.registryOffice = registryOffice;
    }

    public LocalDateTime getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(LocalDateTime registeredAt) {
        this.registeredAt = registeredAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Long getContractAttachmentId() {
        return contractAttachmentId;
    }

    public void setContractAttachmentId(Long contractAttachmentId) {
        this.contractAttachmentId = contractAttachmentId;
    }

    public String getContractNo() {
        return contractNo;
    }

    public void setContractNo(String contractNo) {
        this.contractNo = contractNo;
    }

    public LocalDateTime getContractSignedAt() {
        return contractSignedAt;
    }

    public void setContractSignedAt(LocalDateTime contractSignedAt) {
        this.contractSignedAt = contractSignedAt;
    }

    public Long getCertificateAttachmentId() {
        return certificateAttachmentId;
    }

    public void setCertificateAttachmentId(Long certificateAttachmentId) {
        this.certificateAttachmentId = certificateAttachmentId;
    }

    public String getCertificateNo() {
        return certificateNo;
    }

    public void setCertificateNo(String certificateNo) {
        this.certificateNo = certificateNo;
    }

    public LocalDateTime getCertificateUploadedAt() {
        return certificateUploadedAt;
    }

    public void setCertificateUploadedAt(LocalDateTime certificateUploadedAt) {
        this.certificateUploadedAt = certificateUploadedAt;
    }

    public String getCancellationStatus() {
        return cancellationStatus;
    }

    public void setCancellationStatus(String cancellationStatus) {
        this.cancellationStatus = cancellationStatus;
    }

    public String getCancellationNo() {
        return cancellationNo;
    }

    public void setCancellationNo(String cancellationNo) {
        this.cancellationNo = cancellationNo;
    }

    public LocalDateTime getCancellationAppliedAt() {
        return cancellationAppliedAt;
    }

    public void setCancellationAppliedAt(LocalDateTime cancellationAppliedAt) {
        this.cancellationAppliedAt = cancellationAppliedAt;
    }

    public LocalDateTime getCancellationCompletedAt() {
        return cancellationCompletedAt;
    }

    public void setCancellationCompletedAt(LocalDateTime cancellationCompletedAt) {
        this.cancellationCompletedAt = cancellationCompletedAt;
    }

    public Long getCancellationCertificateAttachmentId() {
        return cancellationCertificateAttachmentId;
    }

    public void setCancellationCertificateAttachmentId(Long cancellationCertificateAttachmentId) {
        this.cancellationCertificateAttachmentId = cancellationCertificateAttachmentId;
    }

    public String getCancellationRequestedBy() {
        return cancellationRequestedBy;
    }

    public void setCancellationRequestedBy(String cancellationRequestedBy) {
        this.cancellationRequestedBy = cancellationRequestedBy;
    }

    public String getCancellationNote() {
        return cancellationNote;
    }

    public void setCancellationNote(String cancellationNote) {
        this.cancellationNote = cancellationNote;
    }

    public Integer getPriorityRank() {
        return priorityRank;
    }

    public void setPriorityRank(Integer priorityRank) {
        this.priorityRank = priorityRank;
    }

    public String getCoSignedBy() {
        return coSignedBy;
    }

    public void setCoSignedBy(String coSignedBy) {
        this.coSignedBy = coSignedBy;
    }

    public int getVehicleCount() {
        return vehicleCount;
    }

    public void setVehicleCount(int vehicleCount) {
        this.vehicleCount = vehicleCount;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
