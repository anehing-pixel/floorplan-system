package com.company.floorplan.domain.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 車検証实体（USED R2.5 / R5.1，{@code inspection_certificates}，V52/V53）。
 *
 * <p><b>本表不是权属权威源</b>。日本二手车的权属结论读
 * {@code collateral_registration_batches}（ADR-Q27 唯一权威源，V54 只给它加
 * {@code authoritative_source_type} 列），本实体是「INSPECTION_CERT 这一路证据」的载体。
 * 把「证在我手上」读成「车是我的是融资方的」是 USED 域最容易犯的错，
 * 所以 V52 头注把这件事写在表注释上，本类的注释也复述一遍。</p>
 *
 * <p>三条不变式（都在库里有对应约束，代码只是不让它们被绕到）：
 * <ul>
 *   <li>一车一证：同 VIN 同时最多一张 {@code status.isActive()} 的证
 *       （V53 {@code uk_cert_active_vin}），违反回 {@link ErrorCode#CERT_002}；</li>
 *   <li>状态只能经 {@code CertificateStateMachine} 流转（V52 列注释明文禁止裸 UPDATE）；</li>
 *   <li>进入 PLEDGED/RELEASED/LOST 必须带时间戳（V53 {@code ck_cert_status_timestamps}）。</li>
 * </ul>
 * </p>
 */
public class VehicleCertificate {

    /** 主键。 */
    private Long id;

    /** 幂等锚点 {@code CERT-{certificateNo}}（D00 §13.1，V53 前由 {@code uk_cert_key} 唯一）。 */
    private String certKey;

    /** 証書番号（車検証番号）；与 VIN 的一致性由 {@code CERT_003} 校验。 */
    private String certificateNo;

    /** 車台番号（17 位），逻辑引用 {@code vehicle_units.vin}。 */
    private String vin;

    /** 登録番号（ナンバー），与 VIN 一起构成对外可核对的两把钥匙。 */
    private String registrationPlate;

    /** 所有者（让渡担保结构下常≠使用者，必须分列存）。 */
    private String ownerName;

    /** 所有者住址。 */
    private String ownerAddress;

    /** 使用者（通常＝车商）。 */
    private String userName;

    /** 使用者住址。 */
    private String userAddress;

    /** 初度登録年月。 */
    private LocalDate firstRegisteredOn;

    /** 車検満了日；库内 NOT NULL——没有车検到期日的证不能作为建账依据。 */
    private LocalDate inspectionExpiryDate;

    /** 発行年月日；库内 NOT NULL。 */
    private LocalDate issueDate;

    /** 本证承载的担保形态；[待定源 D-03] 未签 ⟹ 只存事实不设默认。 */
    private CertificateSecurityType securityType;

    /** 抵当権有无（票面事实，与 {@code encumbranceStatus} 的聚合结论分列）。 */
    private Boolean mortgageFlag;

    /** 权利瑕疵聚合结论；除 NONE 外一律硬阻断设质（D03 §2.5 I3 / P-7）。 */
    private EncumbranceType encumbranceStatus;

    /** 保管者代码（逻辑引用 {@code custodians.code}，V65/W-U4 扩列，无物理 FK）。 */
    private String holderCustodianCode;

    /** 保管者类型。 */
    private String holderType;

    /** 保管位置（仓库/金库描述）。 */
    private String custodyLocation;

    /** 状态（六态）。 */
    private CertificateStatus status;

    /** 换证链：新证指向旧证；旧证同时置 REISSUED（UC-R2-5 的「变更有历史」）。 */
    private Long predecessorId;

    /** 设质时间（进入 PLEDGED 必填）。 */
    private OffsetDateTime pledgedAt;

    /** 交还时间（进入 RELEASED 必填）。 */
    private OffsetDateTime releasedAt;

    /** 灭失申报时间（进入 LOST 必填）。 */
    private OffsetDateTime lostAt;

    /** 放证线关联指令号（逻辑引用 {@code release_orders.order_no}，写侧属 R7）。 */
    private String releaseOrderNo;

    /** 扫描件路径。 */
    private String evidencePath;

    /** 扫描件摘要。 */
    private String evidenceDigest;

    /** 创建时间。 */
    private OffsetDateTime createdAt;

    /** 更新时间。 */
    private OffsetDateTime updatedAt;

    /** 乐观锁版本（V52 默认 0）。 */
    private int version;

    /** 车检是否在 {@code bizDate} 当天仍有效。 */
    public boolean isInspectionValidOn(LocalDate bizDate) {
        return inspectionExpiryDate != null && !inspectionExpiryDate.isBefore(bizDate);
    }

    /** 距车検満了日还有多少个<b>日历天</b>；{@code bizDate} 晚于満了日时为负。 */
    public long inspectionRemainingDays(LocalDate bizDate) {
        if (inspectionExpiryDate == null) {
            return Long.MIN_VALUE;
        }
        return java.time.temporal.ChronoUnit.DAYS.between(bizDate, inspectionExpiryDate);
    }

    /** 瑕疵是否放行设质/放款（未回填 {@code null} 与 UNKNOWN 一样阻断，P-7 fail-closed）。 */
    public boolean isEncumbranceClear() {
        return encumbranceStatus != null && encumbranceStatus.isClear();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCertKey() {
        return certKey;
    }

    public void setCertKey(String certKey) {
        this.certKey = certKey;
    }

    public String getCertificateNo() {
        return certificateNo;
    }

    public void setCertificateNo(String certificateNo) {
        this.certificateNo = certificateNo;
    }

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public String getRegistrationPlate() {
        return registrationPlate;
    }

    public void setRegistrationPlate(String registrationPlate) {
        this.registrationPlate = registrationPlate;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getOwnerAddress() {
        return ownerAddress;
    }

    public void setOwnerAddress(String ownerAddress) {
        this.ownerAddress = ownerAddress;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUserAddress() {
        return userAddress;
    }

    public void setUserAddress(String userAddress) {
        this.userAddress = userAddress;
    }

    public LocalDate getFirstRegisteredOn() {
        return firstRegisteredOn;
    }

    public void setFirstRegisteredOn(LocalDate firstRegisteredOn) {
        this.firstRegisteredOn = firstRegisteredOn;
    }

    public LocalDate getInspectionExpiryDate() {
        return inspectionExpiryDate;
    }

    public void setInspectionExpiryDate(LocalDate inspectionExpiryDate) {
        this.inspectionExpiryDate = inspectionExpiryDate;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public void setIssueDate(LocalDate issueDate) {
        this.issueDate = issueDate;
    }

    public CertificateSecurityType getSecurityType() {
        return securityType;
    }

    public void setSecurityType(CertificateSecurityType securityType) {
        this.securityType = securityType;
    }

    public Boolean getMortgageFlag() {
        return mortgageFlag;
    }

    public void setMortgageFlag(Boolean mortgageFlag) {
        this.mortgageFlag = mortgageFlag;
    }

    public EncumbranceType getEncumbranceStatus() {
        return encumbranceStatus;
    }

    public void setEncumbranceStatus(EncumbranceType encumbranceStatus) {
        this.encumbranceStatus = encumbranceStatus;
    }

    public String getHolderCustodianCode() {
        return holderCustodianCode;
    }

    public void setHolderCustodianCode(String holderCustodianCode) {
        this.holderCustodianCode = holderCustodianCode;
    }

    public String getHolderType() {
        return holderType;
    }

    public void setHolderType(String holderType) {
        this.holderType = holderType;
    }

    public String getCustodyLocation() {
        return custodyLocation;
    }

    public void setCustodyLocation(String custodyLocation) {
        this.custodyLocation = custodyLocation;
    }

    public CertificateStatus getStatus() {
        return status;
    }

    public void setStatus(CertificateStatus status) {
        this.status = status;
    }

    public Long getPredecessorId() {
        return predecessorId;
    }

    public void setPredecessorId(Long predecessorId) {
        this.predecessorId = predecessorId;
    }

    public OffsetDateTime getPledgedAt() {
        return pledgedAt;
    }

    public void setPledgedAt(OffsetDateTime pledgedAt) {
        this.pledgedAt = pledgedAt;
    }

    public OffsetDateTime getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(OffsetDateTime releasedAt) {
        this.releasedAt = releasedAt;
    }

    public OffsetDateTime getLostAt() {
        return lostAt;
    }

    public void setLostAt(OffsetDateTime lostAt) {
        this.lostAt = lostAt;
    }

    public String getReleaseOrderNo() {
        return releaseOrderNo;
    }

    public void setReleaseOrderNo(String releaseOrderNo) {
        this.releaseOrderNo = releaseOrderNo;
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

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }
}
