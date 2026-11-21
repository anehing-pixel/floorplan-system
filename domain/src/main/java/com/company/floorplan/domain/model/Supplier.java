package com.company.floorplan.domain.model;

import java.time.LocalDateTime;

/**
 * 上游主数据（制造商销售公司 / 区域总代理 / 批发公司 / 拍卖行 / 中古车批发商 / 经销商自身）。
 *
 * <p>上游是独立的风险主体：回购能力、账户可信度、结算纪律都需单独评估，
 * 不能与"厂家"混为一谈。</p>
 */
public class Supplier {

    /** 上游状态。 */
    public enum Status {
        /** 正常合作：可作为收款方。 */
        ACTIVE,
        /** 暂停合作：存量订单照常推进，禁止新增打款。 */
        SUSPENDED,
        /** 终止合作：不再承接新业务（终态）。 */
        TERMINATED
    }

    private Long id;
    private String supplierCode;
    private String name;
    private SupplierType supplierType;
    private Status status;
    private String riskGrade;
    private GuaranteeAbility guaranteeAbility;
    private String contactPerson;
    private String contactPhone;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSupplierCode() { return supplierCode; }
    public void setSupplierCode(String supplierCode) { this.supplierCode = supplierCode; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public SupplierType getSupplierType() { return supplierType; }
    public void setSupplierType(SupplierType supplierType) { this.supplierType = supplierType; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getRiskGrade() { return riskGrade; }
    public void setRiskGrade(String riskGrade) { this.riskGrade = riskGrade; }

    public GuaranteeAbility getGuaranteeAbility() { return guaranteeAbility; }
    public void setGuaranteeAbility(GuaranteeAbility guaranteeAbility) { this.guaranteeAbility = guaranteeAbility; }

    public String getContactPerson() { return contactPerson; }
    public void setContactPerson(String contactPerson) { this.contactPerson = contactPerson; }

    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    /**
     * 打款模式（派生值）。
     *
     * @return 由 {@link SupplierType} 推导，不可人工覆盖
     */
    public PayeeMode derivePayeeMode() {
        return supplierType == null ? PayeeMode.DIRECT_PAY : supplierType.derivePayeeMode();
    }
}
