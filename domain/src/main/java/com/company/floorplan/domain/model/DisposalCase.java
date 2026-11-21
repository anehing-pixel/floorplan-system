package com.company.floorplan.domain.model;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 违约处置工单（业务闭环 P0-② 违约处置闭环）。
 *
 * <p><b>业务定位</b>：脱信销售（OOT）/ 长期逾期 / 盘点缺失等严重违约发生后，
 * 必须有一个从"识别 → 收车 → 变现/残值核算 → 损失认定"的闭环，
 * 而不是只发一条告警就结束（旧版仅 {@code alerts} 告警，是本次重点补全项）。</p>
 *
 * <p><b>金额约定</b>：{@link #proceeds}（变现所得）、{@link #lossAmount}（认定损失）
 * 一律整数日元（long）。</p>
 *
 * <p><b>不变量</b>：状态流转受 {@link DisposalStatus#canTransitTo(DisposalStatus)} 约束，
 * 非法流转抛 {@code DISPOSAL_002}。</p>
 */
public class DisposalCase {

    /** 主键。 */
    private Long id;

    /** 处置单号（业务唯一，用于对账与对外展示）。 */
    private String caseNo;

    /** 车架号。 */
    private String vin;

    /** 经销商 ID。 */
    private Long dealerId;

    /** 关联额度 ID（处置回款用于回补额度）。 */
    private Long creditLineId;

    /**
     * 违约原因：{@code OOT}(脱信销售) / {@code OVERDUE}(长期逾期) /
     * {@code AUDIT_MISSING}(盘点缺失)。
     */
    private String reason;

    /** 工单状态（见 {@link DisposalStatus}）。 */
    private DisposalStatus status;

    /** 收车时间。 */
    private LocalDateTime repossessedAt;

    /** 变现所得（整数日元，拍卖/二手处置回款）。 */
    private long proceeds;

    /** 认定损失（整数日元 = 应收 − 变现所得，若为正）。 */
    private long lossAmount;

    /** 开单时间。 */
    private LocalDateTime openedAt;

    /** 结案时间。 */
    private LocalDateTime closedAt;

    public DisposalCase() {
    }

    /** 统一状态流转入口：校验白名单后切换。 */
    private void transit(DisposalStatus target) {
        if (status == null) {
            status = DisposalStatus.OPEN;
        }
        if (!this.status.canTransitTo(target)) {
            throw new BusinessException(ErrorCode.DISPOSAL_002, Map.of(
                    "caseNo", caseNo, "currentStatus", String.valueOf(this.status), "targetStatus", String.valueOf(target)));
        }
        this.status = target;
    }

    /** 开单（默认 OPEN）。 */
    public void open() {
        this.status = DisposalStatus.OPEN;
        this.openedAt = LocalDateTime.now();
    }

    /** 收车：OPEN → REPOSSESSED。 */
    public void repossess() {
        transit(DisposalStatus.REPOSSESSED);
        this.repossessedAt = LocalDateTime.now();
    }

    /** 进入变现：REPOSSESSED → LIQUIDATING。 */
    public void startLiquidating() {
        transit(DisposalStatus.LIQUIDATING);
    }

    /**
     * 结案：根据变现所得是否满足应收，形成"收回"或"损失"终态。
     *
     * @param receivable 该台应收总额（本金+利息，整数日元）
     * @param proceeds   实际变现所得（整数日元）
     */
    public void close(long receivable, long proceeds) {
        this.proceeds = proceeds;
        long actualLoss = Math.max(0L, receivable - proceeds);
        this.lossAmount = actualLoss;
        if (actualLoss == 0L) {
            transit(DisposalStatus.CLOSED_RECOVERED);
        } else {
            transit(DisposalStatus.CLOSED_LOSS);
        }
        this.closedAt = LocalDateTime.now();
    }

    // ===================== getter / setter =====================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCaseNo() {
        return caseNo;
    }

    public void setCaseNo(String caseNo) {
        this.caseNo = caseNo;
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

    public Long getCreditLineId() {
        return creditLineId;
    }

    public void setCreditLineId(Long creditLineId) {
        this.creditLineId = creditLineId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public DisposalStatus getStatus() {
        return status;
    }

    public void setStatus(DisposalStatus status) {
        this.status = status;
    }

    public LocalDateTime getRepossessedAt() {
        return repossessedAt;
    }

    public void setRepossessedAt(LocalDateTime repossessedAt) {
        this.repossessedAt = repossessedAt;
    }

    public long getProceeds() {
        return proceeds;
    }

    public void setProceeds(long proceeds) {
        this.proceeds = proceeds;
    }

    public long getLossAmount() {
        return lossAmount;
    }

    public void setLossAmount(long lossAmount) {
        this.lossAmount = lossAmount;
    }

    public LocalDateTime getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(LocalDateTime openedAt) {
        this.openedAt = openedAt;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(LocalDateTime closedAt) {
        this.closedAt = closedAt;
    }
}
