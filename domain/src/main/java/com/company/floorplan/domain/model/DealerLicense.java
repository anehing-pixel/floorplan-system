package com.company.floorplan.domain.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 车商许可证实体（USED R1，{@code dealer_licenses}）。
 *
 * <p>{@code DealerQualification} 聚合下的实体（D03 §2.1）。不变式：
 * 同一 {@code dealerId} ＋同一许可类型同时<b>最多一条 ACTIVE</b>，由库内条件唯一索引
 * {@code uk_dealer_license_active ON (dealer_id, license_type) WHERE status='ACTIVE'}（V45）
 * 硬性保证。所以<b>登记新证必须先把旧 ACTIVE 行降级为 SUPERSEDED 再 INSERT</b>——
 * 顺序反了会直接撞唯一索引，这是索引给的顺序约束，不是代码风格选择。
 * 另有 {@code uk_dlic_dealer_type_no(dealer_id, license_type, license_no)} 保证同一证号不重复登记，
 * 它正是幂等键 {@code DLIC:{dealerId}:{type}:{no}} 的落库对应物。</p>
 */
public class DealerLicense {

    /** 主键。 */
    private Long id;

    /** 所属车商（{@code dealers.id}）。 */
    private Long dealerId;

    /** 许可类型；当前取值域只有 FURIGUSHO。 */
    private LicenseType licenseType;

    /** 許可番号（格式由车商提交，系统只存不改）。 */
    private String licenseNo;

    /** 管辖公安委員会（发证机关）。 */
    private String competentAuthority;

    /** 交付日（发证日）。 */
    private LocalDate issuedOn;

    /**
     * 有效期末日；{@code null} 表示「無期限」（古物商許可通常無期限）。
     *
     * <p>注意这里的 null <b>不是</b>「未知」而是「不过期」，与合规核查的
     * {@code valid_until IS NULL}（fail-closed，视为无效）方向相反。
     * 两者的差别见 {@link #isValidOn}：许可是登记机关核发的权利，无期限就是永久有效；
     * 合规是「一段时间内的核查结论」，没有有效期就等于无法判断结论是否还成立。</p>
     */
    private LocalDate expiresOn;

    /** 状态。 */
    private LicenseStatus status;

    /** 证据文件路径（証書扫描件在附件库的 key）。 */
    private String evidencePath;

    /** 证据内容摘要（SHA-256，防扫描件被替换后无人发现）。 */
    private String evidenceDigest;

    /** 核验人。 */
    private String verifiedBy;

    /** 核验时间。 */
    private OffsetDateTime verifiedAt;

    // 本表没有 version 乐观锁列（V45 未建）：许可的并发写风险由「换发即降级旧证」的
    // 单一入口 + (dealer_id, license_type, license_no) 唯一索引兜住，不做行级版本。

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDealerId() {
        return dealerId;
    }

    public void setDealerId(Long dealerId) {
        this.dealerId = dealerId;
    }

    public LicenseType getLicenseType() {
        return licenseType;
    }

    public void setLicenseType(LicenseType licenseType) {
        this.licenseType = licenseType;
    }

    public String getLicenseNo() {
        return licenseNo;
    }

    public void setLicenseNo(String licenseNo) {
        this.licenseNo = licenseNo;
    }

    public String getCompetentAuthority() {
        return competentAuthority;
    }

    public void setCompetentAuthority(String competentAuthority) {
        this.competentAuthority = competentAuthority;
    }

    public LocalDate getIssuedOn() {
        return issuedOn;
    }

    public void setIssuedOn(LocalDate issuedOn) {
        this.issuedOn = issuedOn;
    }

    public LocalDate getExpiresOn() {
        return expiresOn;
    }

    public void setExpiresOn(LocalDate expiresOn) {
        this.expiresOn = expiresOn;
    }

    public LicenseStatus getStatus() {
        return status;
    }

    public void setStatus(LicenseStatus status) {
        this.status = status;
    }

    public String getEvidencePath() {
        return evidencePath;
    }

    public void setEvidencePath(String evidencePath) {
        this.evidencePath = evidencePath;
    }

    public String getEvidenceDigest() {
        return evidenceDigest;
    }

    public void setEvidenceDigest(String evidenceDigest) {
        this.evidenceDigest = evidenceDigest;
    }

    public String getVerifiedBy() {
        return verifiedBy;
    }

    public void setVerifiedBy(String verifiedBy) {
        this.verifiedBy = verifiedBy;
    }

    public OffsetDateTime getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(OffsetDateTime verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    /** 许可号是否有效：非空且非全空白（空白串不算号，防「录入了一条没有号的证」）。 */
    public boolean hasLicenseNo() {
        return licenseNo != null && !licenseNo.isBlank();
    }

    /**
     * 业务日 {@code bizDate} 上是否为「可支撑准入」的许可（G-A1）。
     *
     * <p>三件事同时成立才算：类型是古物商許可、状态 ACTIVE、未过期
     * （{@code expires_on} 为 null 表示無期限，或 {@code expires_on >= bizDate}）。
     * 用 {@code >=} 而不是 {@code >}：有效期末日当天仍然有效。</p>
     */
    public boolean isValidOn(LocalDate bizDate) {
        if (licenseType != LicenseType.FURIGUSHO || status == null || !status.isValid()) {
            return false;
        }
        if (!hasLicenseNo()) {
            return false;
        }
        return expiresOn == null || !expiresOn.isBefore(bizDate);
    }

    /**
     * 距到期还有几天；{@code null} 表示無期限（不参与预警）。
     *
     * <p>负数表示已过期。判定 {@code 0 <= d <= alertDays} 即为 EXPIRING。</p>
     */
    public Long daysToExpiry(LocalDate bizDate) {
        // 無期限的许可永远不预警：古物商許可本身就没有到期日，硬造一个天数会把预警工单刷满
        if (expiresOn == null) {
            return null;
        }
        return java.time.temporal.ChronoUnit.DAYS.between(bizDate, expiresOn);
    }
}
