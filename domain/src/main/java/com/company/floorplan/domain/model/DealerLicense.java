package com.company.floorplan.domain.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 許可证实体（USED 域 R1.1；表 {@code dealer_licenses}，V45 / D04 §3.1）。
 *
 * <p><b>1..n 历史、有效唯一</b>（D03 §2.1）：同一 {@code dealerId} 可以留多行（换证、更新、
 * 撤销都要留痕），但同时最多只有一行 {@code status='ACTIVE'}，由 V45 的部分唯一索引
 * {@code uk_dealer_license_active (dealer_id, license_type) WHERE status='ACTIVE'} 守住——
 * <b>这条不变式不在本类里用 if 判断</b>，因为应用层的"先查再插"在并发下不成立，
 * 唯一索引才是唯一可靠表达（与 ADR-010 同 VIN 多笔垫款的处理同构）。</p>
 *
 * <p><b>没有 {@code version} 列</b>：D04 §3.1 未给本表乐观锁列，本表也没有"同一行被两方并发改"的
 * 业务动作（改许可＝写新行 + 把旧行置 {@code SUPERSEDED}）。因此仓储更新走普通 UPDATE，
 * 不伪造 CAS。有 {@code version} 的是 {@code dealers} / {@code dealer_score_reviews}
 * / {@code inspection_certificates}。</p>
 *
 * <p><b>领域纯度</b>：本类不碰 Spring、不碰 JDBC、不读配置，也不在 setter 里做业务判断
 * （照 {@code Supplier}/{@code Dealer} 的既有风格）。判定集中在
 * {@code DealerAdmissionPolicy}，避免"实体里一个 isExpired、策略里另一个 isExpired"。</p>
 */
public class DealerLicense {

    /** 主键（BIGSERIAL）。 */
    private Long id;

    /** 所属车商（物理 FK → {@code dealers.id}，D04 §3.1）。 */
    private Long dealerId;

    /** 許可類型；本包唯一合法值是 {@code FURIGUSHO}（V45 的 CHECK）。 */
    private DealerLicenseType licenseType;

    /** 許可番号。与 {@code dealerId}/{@code licenseType} 一起构成幂等键 {@code DLIC:{dealerId}:{type}:{no}}。 */
    private String licenseNo;

    /** 管轄公安委员会（D03 §2.1 的成员 {@code competentAuthority}）。 */
    private String competentAuthority;

    /** 交付日。 */
    private LocalDate issuedOn;

    /** 有效期限。可为空：届出中尚未记载期限的行按"未知"处理，判定侧见 {@code DealerAdmissionPolicy}。 */
    private LocalDate expiresOn;

    /** 生命周期状态（台账词汇，不是状态机）。 */
    private LicenseStatus status;

    /** 証拠文件路径（本地归档）。 */
    private String evidencePath;

    /** 証拠摘要（SHA-256 十六进制），用于事后证明归档未被替换。 */
    private String evidenceDigest;

    /** 核查人。D04 §3.1 注明它是 {@code COMPLIANCE_CHECK_DONE} 审计的主体字段。 */
    private String verifiedBy;

    /** 核查时点。 */
    private Instant verifiedAt;

    /** 创建时间（DB 默认 {@code now()}）。 */
    private Instant createdAt;

    /** 更新时间（DB 默认 {@code now()}）。 */
    private Instant updatedAt;

    /** 创建人（{@code AuditActor} 解析出的自然人标识）。 */
    private String createdBy;

    /** 更新人。 */
    private String updatedBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDealerId() { return dealerId; }
    public void setDealerId(Long dealerId) { this.dealerId = dealerId; }

    public DealerLicenseType getLicenseType() { return licenseType; }
    public void setLicenseType(DealerLicenseType licenseType) { this.licenseType = licenseType; }

    public String getLicenseNo() { return licenseNo; }
    public void setLicenseNo(String licenseNo) { this.licenseNo = licenseNo; }

    public String getCompetentAuthority() { return competentAuthority; }
    public void setCompetentAuthority(String competentAuthority) { this.competentAuthority = competentAuthority; }

    public LocalDate getIssuedOn() { return issuedOn; }
    public void setIssuedOn(LocalDate issuedOn) { this.issuedOn = issuedOn; }

    public LocalDate getExpiresOn() { return expiresOn; }
    public void setExpiresOn(LocalDate expiresOn) { this.expiresOn = expiresOn; }

    public LicenseStatus getStatus() { return status; }
    public void setStatus(LicenseStatus status) { this.status = status; }

    public String getEvidencePath() { return evidencePath; }
    public void setEvidencePath(String evidencePath) { this.evidencePath = evidencePath; }

    public String getEvidenceDigest() { return evidenceDigest; }
    public void setEvidenceDigest(String evidenceDigest) { this.evidenceDigest = evidenceDigest; }

    public String getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(String verifiedBy) { this.verifiedBy = verifiedBy; }

    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
