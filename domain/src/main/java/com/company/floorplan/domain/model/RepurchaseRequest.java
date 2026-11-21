package com.company.floorplan.domain.model;

import java.time.LocalDateTime;

/**
 * 厂家回购请求（业务闭环 P1：日本新车渠道核心信用增强 メーカー買取約束）。
 *
 * <p><b>业务定位</b>：经销商违约或车辆长期滞销时，厂家按事先约定买回车辆，
 * 回款直接冲抵该台垫款本金——这是日本库存融资"不依赖强物理监管"的关键防线。
 * 区别于违约处置：回购是<b>正常合同履约</b>（厂家承诺），处置是<b>违约后追偿</b>。</p>
 *
 * <p><b>status</b>：{@code REQUESTED}(已申请) / {@code FULFILLED}(已兑现，冲抵垫款) / {@code REJECTED}(驳回)。</p>
 */
public class RepurchaseRequest {

    /** 主键。 */
    private Long id;

    /** 回购单号（业务唯一）。 */
    private String requestNo;

    /** 车架号。 */
    private String vin;

    /** 经销商 ID。 */
    private Long dealerId;

    /** 关联额度 ID。 */
    private Long creditLineId;

    /** 申请原因。 */
    private String reason;

    /** 状态。 */
    private String status;

    /** 厂家回购金额（整数日元，兑现时填写）。 */
    private long repurchaseAmount;

    /** 兑现时间。 */
    private LocalDateTime fulfilledAt;

    public RepurchaseRequest() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRequestNo() {
        return requestNo;
    }

    public void setRequestNo(String requestNo) {
        this.requestNo = requestNo;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getRepurchaseAmount() {
        return repurchaseAmount;
    }

    public void setRepurchaseAmount(long repurchaseAmount) {
        this.repurchaseAmount = repurchaseAmount;
    }

    public LocalDateTime getFulfilledAt() {
        return fulfilledAt;
    }

    public void setFulfilledAt(LocalDateTime fulfilledAt) {
        this.fulfilledAt = fulfilledAt;
    }
}
