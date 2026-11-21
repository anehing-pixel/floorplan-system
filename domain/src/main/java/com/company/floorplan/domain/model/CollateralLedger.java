package com.company.floorplan.domain.model;

import java.time.LocalDateTime;

/**
 * 担保物台账（一台车一行，按 VIN 唯一）。
 *
 * <p><b>核心设计：四个状态独立成列，不压成单一状态机</b>。
 * 它们的时点不同、可以并存：</p>
 * <pre>
 *   financed 已放款 ──→ sold 已售出 ──→ repaid 已回款 ──→ released 已解除担保
 *        （垫款 PAID）    （标记售出）      （还款核销）      （结清/处置）
 * </pre>
 * <p>典型中间态：<b>已放款 + 已售出 + 未回款</b>——车已卖给终端客户、
 * 钱还没到账（结算窗口内）。这正是"售出即还"机制要盯的状态，
 * 也是担保敞口最大的时候。若压成单一状态机，这个中间态就表达不出来，
 * 风险清单也筛不出来。</p>
 *
 * <p><b>与 vehicle_units 的分工</b>：{@code vehicle_units} 是<b>车辆台账</b>
 * （车辆本身在业务上走到哪一步），本表是<b>担保物台账</b>
 * （这台车的担保权益状态与凭证）。两者 1:1（按 VIN），但关注点不同：
 * 车辆可能已结清（车辆台账终态）而担保登记尚未抹消——
 * 这在旧法依赖判例的日本实务下是真实存在的敞口，
 * 只有独立台账才看得见。</p>
 */
public class CollateralLedger {

    /** 主键。 */
    private Long id;

    /** 车架号：台账唯一键（一台一行）。 */
    private String vin;

    /** 归属经销商 ID。 */
    private Long dealerId;

    /* ---- 四个独立状态 ---- */

    /** 已放款：垫款 PAID（银行回调确认）时置真。 */
    private boolean financed;

    /** 放款确认时间（证据链起点）。 */
    private LocalDateTime financedAt;

    /** 关联垫款单号（证据链锚点）。 */
    private String advanceNo;

    /** 已售出：标记售出时置真，同时触发库存释放规则。 */
    private boolean sold;

    /** 售出时间。 */
    private LocalDateTime soldAt;

    /** 标记售出的操作人。 */
    private String soldFlaggedBy;

    /** 已回款：还款核销时置真。 */
    private boolean repaid;

    /** 回款时间。 */
    private LocalDateTime repaidAt;

    /** 累计回款额（日元）。 */
    private Long repaidAmount;

    /** 已解除担保：结清或处置完成后置真。 */
    private boolean released;

    /** 解除担保时间。 */
    private LocalDateTime releasedAt;

    /** 解除原因：SETTLED 结清 / DISPOSED 处置 / WRITTEN_OFF 核销。 */
    private String releaseReason;

    /* ---- 担保物属性 ---- */

    /** 担保方式（SecurityType 枚举名）。 */
    private String securityType;

    /** 担保状态（SecurityStatus 枚举名）。 */
    private String securityStatus;

    /* ---- 库存释放规则 ---- */

    /**
     * 要求的回款账户引用码。
     *
     * <p>经销商卖出一台车后，系统自动扣减担保物清单；
     * 回款须进入指定账户或直接用于还款。此处存<b>引用码</b>不存明文账号
     * （与 ADR-007 账户纪律一致）。</p>
     */
    private String requiredAccountRef;

    /** 回款账户是否已核验。 */
    private boolean accountChecked;

    /** 账户核验时间。 */
    private LocalDateTime accountCheckedAt;

    /** 账户核验人。 */
    private String accountCheckedBy;

    /* ---- 盘点核查痕迹 ---- */

    /** 最近盘点日。 */
    private java.time.LocalDate lastAuditDate;

    /** 盘点差异次数。 */
    private int auditMismatchCount;

    /** 最近盘点人。 */
    private String lastAuditBy;

    /* ---- 处置痕迹 ---- */

    /** 关联处置工单号。 */
    private String disposalCaseNo;

    /** 处置完成时间。 */
    private LocalDateTime disposedAt;

    /* ---- 待办 ---- */

    /** 存在待处理的登记/抹消事项。 */
    private boolean registrationDue;

    /** 待办说明（如"抹消登记待共同申请"）。 */
    private String registrationNote;

    /** 建档时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;

    public CollateralLedger() {
    }

    /**
     * 是否处于"已售未回款"的敞口状态。
     *
     * <p>这是库存融资最需要盯的状态：车已交付终端客户，钱还没到账，
     * 担保物清单已扣减但担保权益尚未随回款解除。
     * 若超期未回款即升级为 OOT（脱信销售）风险。</p>
     *
     * @return true 表示敞口中
     */
    public boolean isSoldUnpaid() {
        return sold && !repaid;
    }

    /**
     * 是否已完成整个担保闭环（放款 → 售出 → 回款 → 解除）。
     *
     * @return true 表示闭环完成
     */
    public boolean isClosed() {
        return financed && sold && repaid && released;
    }

    /**
     * 台账健康度：是否有未闭环的待办。
     *
     * @return 待办说明；无待办返回 null
     */
    public String healthNote() {
        if (registrationDue) {
            return registrationNote;
        }
        if (sold && !repaid) {
            return "已售未回款：回款须进入指定账户或用于还款";
        }
        if (financed && !released && !sold) {
            return "已放款未售出：担保物仍在库";
        }
        return null;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public Long getDealerId() {
        return dealerId;
    }

    public void setDealerId(Long dealerId) {
        this.dealerId = dealerId;
    }

    public boolean isFinanced() {
        return financed;
    }

    public void setFinanced(boolean financed) {
        this.financed = financed;
    }

    public LocalDateTime getFinancedAt() {
        return financedAt;
    }

    public void setFinancedAt(LocalDateTime financedAt) {
        this.financedAt = financedAt;
    }

    public String getAdvanceNo() {
        return advanceNo;
    }

    public void setAdvanceNo(String advanceNo) {
        this.advanceNo = advanceNo;
    }

    public boolean isSold() {
        return sold;
    }

    public void setSold(boolean sold) {
        this.sold = sold;
    }

    public LocalDateTime getSoldAt() {
        return soldAt;
    }

    public void setSoldAt(LocalDateTime soldAt) {
        this.soldAt = soldAt;
    }

    public String getSoldFlaggedBy() {
        return soldFlaggedBy;
    }

    public void setSoldFlaggedBy(String soldFlaggedBy) {
        this.soldFlaggedBy = soldFlaggedBy;
    }

    public boolean isRepaid() {
        return repaid;
    }

    public void setRepaid(boolean repaid) {
        this.repaid = repaid;
    }

    public LocalDateTime getRepaidAt() {
        return repaidAt;
    }

    public void setRepaidAt(LocalDateTime repaidAt) {
        this.repaidAt = repaidAt;
    }

    public Long getRepaidAmount() {
        return repaidAmount;
    }

    public void setRepaidAmount(Long repaidAmount) {
        this.repaidAmount = repaidAmount;
    }

    public boolean isReleased() {
        return released;
    }

    public void setReleased(boolean released) {
        this.released = released;
    }

    public LocalDateTime getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(LocalDateTime releasedAt) {
        this.releasedAt = releasedAt;
    }

    public String getReleaseReason() {
        return releaseReason;
    }

    public void setReleaseReason(String releaseReason) {
        this.releaseReason = releaseReason;
    }

    public String getSecurityType() {
        return securityType;
    }

    public void setSecurityType(String securityType) {
        this.securityType = securityType;
    }

    public String getSecurityStatus() {
        return securityStatus;
    }

    public void setSecurityStatus(String securityStatus) {
        this.securityStatus = securityStatus;
    }

    public String getRequiredAccountRef() {
        return requiredAccountRef;
    }

    public void setRequiredAccountRef(String requiredAccountRef) {
        this.requiredAccountRef = requiredAccountRef;
    }

    public boolean isAccountChecked() {
        return accountChecked;
    }

    public void setAccountChecked(boolean accountChecked) {
        this.accountChecked = accountChecked;
    }

    public LocalDateTime getAccountCheckedAt() {
        return accountCheckedAt;
    }

    public void setAccountCheckedAt(LocalDateTime accountCheckedAt) {
        this.accountCheckedAt = accountCheckedAt;
    }

    public String getAccountCheckedBy() {
        return accountCheckedBy;
    }

    public void setAccountCheckedBy(String accountCheckedBy) {
        this.accountCheckedBy = accountCheckedBy;
    }

    public java.time.LocalDate getLastAuditDate() {
        return lastAuditDate;
    }

    public void setLastAuditDate(java.time.LocalDate lastAuditDate) {
        this.lastAuditDate = lastAuditDate;
    }

    public int getAuditMismatchCount() {
        return auditMismatchCount;
    }

    public void setAuditMismatchCount(int auditMismatchCount) {
        this.auditMismatchCount = auditMismatchCount;
    }

    public String getLastAuditBy() {
        return lastAuditBy;
    }

    public void setLastAuditBy(String lastAuditBy) {
        this.lastAuditBy = lastAuditBy;
    }

    public String getDisposalCaseNo() {
        return disposalCaseNo;
    }

    public void setDisposalCaseNo(String disposalCaseNo) {
        this.disposalCaseNo = disposalCaseNo;
    }

    public LocalDateTime getDisposedAt() {
        return disposedAt;
    }

    public void setDisposedAt(LocalDateTime disposedAt) {
        this.disposedAt = disposedAt;
    }

    public boolean isRegistrationDue() {
        return registrationDue;
    }

    public void setRegistrationDue(boolean registrationDue) {
        this.registrationDue = registrationDue;
    }

    public String getRegistrationNote() {
        return registrationNote;
    }

    public void setRegistrationNote(String registrationNote) {
        this.registrationNote = registrationNote;
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
