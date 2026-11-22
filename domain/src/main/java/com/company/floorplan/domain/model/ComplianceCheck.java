package com.company.floorplan.domain.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 合规核查实体（R1.2；表 {@code dealer_compliance_checks}，V45 / D04 §3.3）。
 * <p>文档侧原词是「コンプライアンス確認」，本包一律落到中文注释里，代码标识符用 {@code Compliance*}。</p>
 *
 * <p><b>一次核查一行，追加不覆盖</b>：核查是有期限的事实（{@code validUntil}），
 * 复检就插新行；把旧行就地改成新结论会让"当时依据什么放行"不可复现——
 * 这与 {@code vehicle_condition_reports} 的 append-only 理由是同一条。</p>
 *
 * <p><b>{@code requestNo} 是幂等锚点</b>：D00 §13 的 {@code CHK:{partner}:{requestNo}}
 * 要求同一外部请求重投不产生第二行，V45 的 {@code uk_dchk_request_no}（部分唯一，
 * {@code WHERE request_no IS NOT NULL}）是它的兜底。人工录入（{@code source=MANUAL}）
 * 没有外部请求号，该列留空，幂等由应用层用「同 type + 同 checkedOn」判重。</p>
 */
public class ComplianceCheck {

    /** 主键。 */
    private Long id;
    /** 所属车商。 */
    private Long dealerId;
    /** 核查种类（反社会势力／AML／本人确认／征信）。 */
    private ComplianceCheckType checkType;
    /** 核查日。 */
    private LocalDate checkedOn;
    /** 结论有效期止；为空表示文档未给该类型的有效期口径（判定 fail-closed，见策略注释）。 */
    private LocalDate validUntil;
    /** 结论（PASS／FAIL／UNKNOWN，UNKNOWN 计不通过）。 */
    private ComplianceResult result;
    /** 实施路径。 */
    private ComplianceCheckSource source;
    /** 外部请求号（幂等键成分，与 {@code uk_dchk_request_no} 对应）。 */
    private String requestNo;
    /** 证据文件路径（库里列名 evidence_path）。 */
    private String evidencePath;
    /** 备注。 */
    private String note;
    /** 核查人。D04 §3.3 注明它是 {@code COMPLIANCE_CHECK_DONE} 审计主体。 */
    private String checkedBy;
    /** 创建时间。 */
    private Instant createdAt;
    /** 更新时间。 */
    private Instant updatedAt;
    /** 创建人。 */
    private String createdBy;
    /** 更新人。 */
    private String updatedBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDealerId() { return dealerId; }
    public void setDealerId(Long dealerId) { this.dealerId = dealerId; }

    public ComplianceCheckType getCheckType() { return checkType; }
    public void setCheckType(ComplianceCheckType checkType) { this.checkType = checkType; }

    public LocalDate getCheckedOn() { return checkedOn; }
    public void setCheckedOn(LocalDate checkedOn) { this.checkedOn = checkedOn; }

    public LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDate validUntil) { this.validUntil = validUntil; }

    public ComplianceResult getResult() { return result; }
    public void setResult(ComplianceResult result) { this.result = result; }

    public ComplianceCheckSource getSource() { return source; }
    public void setSource(ComplianceCheckSource source) { this.source = source; }

    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }

    public String getEvidencePath() { return evidencePath; }
    public void setEvidencePath(String evidencePath) { this.evidencePath = evidencePath; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public String getCheckedBy() { return checkedBy; }
    public void setCheckedBy(String checkedBy) { this.checkedBy = checkedBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    /**
     * 该结论在指定业务日是否仍然有效且通过。
     *
     * <p>三个条件同时成立才算数：结论 {@code PASS}（UNKNOWN 按不通过，D04 §3.3）、
     * 有 {@code validUntil}、{@code bizDate <= validUntil}。
     * <b>{@code validUntil} 为空的行一律不算有效</b>：文档没给「某类核查多久要复检」的口径
     * （该天数是未签字参数 {@code compliance-valid-days[D-02]}），此时把"有效"判成无限期
     * 等于凭空发明一条放行规则。代价如实登记：录入时必须显式填 {@code validUntil}，
     * 否则该车商在 G-A3 上必然不过——这是有意的 fail-closed，
     * 已列入实现说明的待签字清单（D-02 一签，应用层即可按天数自动推导）。</p>
     *
     * @param bizDate 业务日
     * @return 有效且通过返回 true
     */
    public boolean isValidPass(LocalDate bizDate) {
        return result != null && result.isPass()
                && validUntil != null && bizDate != null && !bizDate.isAfter(validUntil);
    }
}
