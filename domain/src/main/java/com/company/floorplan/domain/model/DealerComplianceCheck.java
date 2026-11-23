package com.company.floorplan.domain.model;

import java.time.LocalDate;

/**
 * 车商合规核查记录（USED R1，{@code dealer_compliance_checks}）。
 *
 * <p>一条记录＝「某类型在某日查过一次，结论是什么，凭什么说的」。
 * 历史不覆盖：同一类型可以有多条，闸门取<b>未过期的 PASS 中最新一条</b>，
 * 这样重新核查不必删旧行，旧结论也留得住（全库禁 DELETE 的口径）。</p>
 *
 * <p><b>有效期缺失即不成立</b>：{@code valid_until IS NULL} 不判通过（fail-closed，
 * V41 头注同款默认）。理由是合规结论本质是「一段时间内的判断」，没有期限就无法回答
 * 「这条结论现在还成立吗」——让它默认成立，等于一次核查永久有效。</p>
 */
public class DealerComplianceCheck {

    /** 主键。 */
    private Long id;

    /** 所属车商。 */
    private Long dealerId;

    /** 核查类型（四类之一）。 */
    private ComplianceCheckType checkType;

    /** 核查日。 */
    private LocalDate checkedOn;

    /** 结论有效期末日；null 表示未给出期限 ⟹ 不判通过。 */
    private LocalDate validUntil;

    /** 结论。UNKNOWN 按不通过（P-7）。 */
    private ComplianceResult result;

    /** 结论来源（决定是否需要证据与第二人）。 */
    private ComplianceSource source;

    /** 对外的请求编号（I-11 入站时是幂等键 {@code CHK:{partner}:{requestNo}} 的组成）。 */
    private String requestNo;

    /** 证据路径（报告/回执文件）。 */
    private String evidencePath;

    /** 备注。 */
    private String note;

    /** 执行人（第一人的登录名，来自 {@code AuditActor}）。 */
    private String checkedBy;

    /** 第二签认人（{@code source=MANUAL} 时必填，V76 的 CHECK 兜底）。 */
    private String coSignedBy;

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

    public ComplianceCheckType getCheckType() {
        return checkType;
    }

    public void setCheckType(ComplianceCheckType checkType) {
        this.checkType = checkType;
    }

    public LocalDate getCheckedOn() {
        return checkedOn;
    }

    public void setCheckedOn(LocalDate checkedOn) {
        this.checkedOn = checkedOn;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public void setValidUntil(LocalDate validUntil) {
        this.validUntil = validUntil;
    }

    public ComplianceResult getResult() {
        return result;
    }

    public void setResult(ComplianceResult result) {
        this.result = result;
    }

    public ComplianceSource getSource() {
        return source;
    }

    public void setSource(ComplianceSource source) {
        this.source = source;
    }

    public String getRequestNo() {
        return requestNo;
    }

    public void setRequestNo(String requestNo) {
        this.requestNo = requestNo;
    }

    public String getEvidencePath() {
        return evidencePath;
    }

    public void setEvidencePath(String evidencePath) {
        this.evidencePath = evidencePath;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getCheckedBy() {
        return checkedBy;
    }

    public void setCheckedBy(String checkedBy) {
        this.checkedBy = checkedBy;
    }

    public String getCoSignedBy() {
        return coSignedBy;
    }

    public void setCoSignedBy(String coSignedBy) {
        this.coSignedBy = coSignedBy;
    }

    /**
     * 业务日上是否可作为「该项已通过」的依据（G-A3 的单条判定）。
     *
     * <p>四个条件全部成立：类型在、结论 PASS、核查日不晚于业务日、有效期存在且未过。
     * 任一不满足即视为该项未通过；UNKNOWN 走 {@link ComplianceResult#isPassing()} 同一扇门。</p>
     */
    public boolean isPassingOn(LocalDate bizDate) {
        if (checkType == null || result == null || !result.isPassing()) {
            return false;
        }
        if (checkedOn == null || checkedOn.isAfter(bizDate)) {
            // 未来日期的结论不能用于今天的判定（数据录错或时钟漂移都不能造成提前放行）
            return false;
        }
        return validUntil != null && !validUntil.isBefore(bizDate);
    }

    /** 人工录入且缺第二人：D07 §3.8 要求二人签认，判定与库 CHECK 同口径。 */
    public boolean isManualWithoutCoSign() {
        return source == ComplianceSource.MANUAL
                && (coSignedBy == null || coSignedBy.isBlank() || coSignedBy.equals(checkedBy));
    }
}
