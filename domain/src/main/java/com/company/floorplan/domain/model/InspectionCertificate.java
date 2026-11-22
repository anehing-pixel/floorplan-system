package com.company.floorplan.domain.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 有効車検証实体（R2.5 一车一证与换证链；表 {@code inspection_certificates}，V52 / D04 §3.9）。
 *
 * <p><b>本类是 UC-R2-5 需要的列视图，不是全列映射</b>：V52 的
 * {@code security_type} / {@code mortgage_flag} / {@code holder_custodian_code} /
 * {@code holder_type} / {@code custody_location} / {@code release_order_no} /
 * {@code pledged_at} / {@code released_at} / {@code lost_at} 属设质、保管、放证链路
 * （R5 / R7，D09），写入方在 W-U4。本包刻意不映射它们，理由有两个：
 * ① 不去扩 {@code SecurityType} 那个 LEGACY 共用枚举（V52 的 {@code ck_cert_security_type}
 * 已按「全枚举并集」写死，取值扩展需 D00 §8.2 先改）；
 * ② {@code pledged_at}/{@code released_at} 与状态转移的配对约束（V53 的
 * {@code ck_cert_status_timestamps}）由那条链路负责，本包提前写入只会造出半套语义。</p>
 *
 * <p><b>两条不变式的落点</b>：
 * ① 一车一证＝V53 的 {@code uk_cert_active_vin}（部分唯一，
 * {@code WHERE status NOT IN ('REISSUED','LOST')}）；
 * ② 换证链＝{@code predecessor_id} 自引用（V52 的 {@code fk_cert_predecessor}）
 * ＋ V53 的 {@code ck_cert_predecessor_not_self}。</p>
 *
 * <p><b>{@code certKey}</b>：写入幂等锚点（D00 §13 的 {@code CERT-{certificate_no}}），
 * 与 {@code uk_cert_key} 配对；{@code certificate_no} 本身不设唯一约束，
 * 因为换证前的同一张证可能先以旧状态行存在，唯一性由 {@code cert_key} 表达更稳。</p>
 */
public class InspectionCertificate {

    /** 主键。 */
    private Long id;
    /** 幂等键（{@code CERT-{certificate_no}}）。 */
    private String certKey;
    /** 証書番号（车检证票面编号）。 */
    private String certificateNo;
    /** 车辆 VIN。 */
    private String vin;
    /** 登録番号（车牌）。 */
    private String registrationPlate;
    /** 所有者氏名（車検証上の所有者）。 */
    private String ownerName;
    /** 使用者氏名（車検証上の使用者）。 */
    private String userName;
    /** 車検満了日（V52 定义为 {@code NOT NULL}：没有満了日的证书无法参与任何判定）。 */
    private LocalDate inspectionExpiryDate;
    /** 交付日（V52 定义为 {@code NOT NULL}）。 */
    private LocalDate issueDate;
    /** 状态（六态，转移见 {@code CertificateStateMachine}）。 */
    private CertificateStatus status;
    /** 换证链：本证的前身（旧证）ID；首张证为 {@code null}。 */
    private Long predecessorId;
    /** 証拠路径。 */
    private String evidencePath;
    /** 创建时间。 */
    private Instant createdAt;
    /** 更新时间。 */
    private Instant updatedAt;
    /** 乐观锁版本（V52 有本列，状态变更必须带 CAS）。 */
    private Integer version;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCertKey() { return certKey; }
    public void setCertKey(String certKey) { this.certKey = certKey; }

    public String getCertificateNo() { return certificateNo; }
    public void setCertificateNo(String certificateNo) { this.certificateNo = certificateNo; }

    public String getVin() { return vin; }
    public void setVin(String vin) { this.vin = vin; }

    public String getRegistrationPlate() { return registrationPlate; }
    public void setRegistrationPlate(String registrationPlate) { this.registrationPlate = registrationPlate; }

    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public LocalDate getInspectionExpiryDate() { return inspectionExpiryDate; }
    public void setInspectionExpiryDate(LocalDate inspectionExpiryDate) { this.inspectionExpiryDate = inspectionExpiryDate; }

    public LocalDate getIssueDate() { return issueDate; }
    public void setIssueDate(LocalDate issueDate) { this.issueDate = issueDate; }

    public CertificateStatus getStatus() { return status; }
    public void setStatus(CertificateStatus status) { this.status = status; }

    public Long getPredecessorId() { return predecessorId; }
    public void setPredecessorId(Long predecessorId) { this.predecessorId = predecessorId; }

    public String getEvidencePath() { return evidencePath; }
    public void setEvidencePath(String evidencePath) { this.evidencePath = evidencePath; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
