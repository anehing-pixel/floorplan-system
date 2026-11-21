package com.company.floorplan.domain.model;

import java.time.LocalDate;

/**
 * 收款账户（上游账户 / 经销商结算账户）。
 *
 * <p><b>两类归属共用一个模型</b>：{@code supplierId} 与 {@code dealerId} 二选一，
 * 区别只在归属侧。这样账户的加密、脱敏、冷静期、双人复核逻辑只需实现一遍。</p>
 *
 * <p><b>敏感数据纪律</b>：账号明文<b>只存在于内存与密文字段</b>——
 * {@link #getAccountNoCipher()} 是 AES-GCM 密文，对外一律走 {@link #getAccountNoMask()}。
 * 任何日志与审计输出都不得触碰明文。</p>
 */
public class BankAccount {

    /** 归属类型。 */
    public enum OwnerType {
        /** 上游账户（DIRECT_PAY 的收款方）。 */
        SUPPLIER,
        /** 经销商结算账户（SELF_PAY 场景 C 的收款方）。 */
        DEALER
    }

    private Long id;
    private OwnerType ownerType;
    private Long supplierId;
    private Long dealerId;
    private String bankName;
    private String accountHolder;
    /** 账号密文（AES-GCM），落库存这个。 */
    private String accountNoCipher;
    /** 账号掩码，对外只返这个。 */
    private String accountNoMask;
    /** 业务引用码：Excel 导入只填这个，系统按引用查主数据。 */
    private String accountRef;
    private BankAccountStatus status;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    /** 复核人（双人复核：录入人与复核人必须是两个人）。 */
    private String reviewer;
    private LocalDate createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public OwnerType getOwnerType() { return ownerType; }
    public void setOwnerType(OwnerType ownerType) { this.ownerType = ownerType; }

    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }

    public Long getDealerId() { return dealerId; }
    public void setDealerId(Long dealerId) { this.dealerId = dealerId; }

    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }

    public String getAccountHolder() { return accountHolder; }
    public void setAccountHolder(String accountHolder) { this.accountHolder = accountHolder; }

    public String getAccountNoCipher() { return accountNoCipher; }
    public void setAccountNoCipher(String accountNoCipher) { this.accountNoCipher = accountNoCipher; }

    public String getAccountNoMask() { return accountNoMask; }
    public void setAccountNoMask(String accountNoMask) { this.accountNoMask = accountNoMask; }

    public String getAccountRef() { return accountRef; }
    public void setAccountRef(String accountRef) { this.accountRef = accountRef; }

    public BankAccountStatus getStatus() { return status; }
    public void setStatus(BankAccountStatus status) { this.status = status; }

    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }

    public LocalDate getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(LocalDate effectiveTo) { this.effectiveTo = effectiveTo; }

    public String getReviewer() { return reviewer; }
    public void setReviewer(String reviewer) { this.reviewer = reviewer; }

    public LocalDate getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDate createdAt) { this.createdAt = createdAt; }

    /**
     * 是否已过生效期。
     *
     * <p>变更冷静期的判定基础：账户在 {@code effectiveFrom} 之前不可用，
     * 变更后需等待冷静期才生效。</p>
     *
     * @param on 判定基准日
     * @return true 表示在生效期内
     */
    public boolean isEffectiveOn(LocalDate on) {
        if (effectiveFrom != null && on.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo == null || !on.isAfter(effectiveTo);
    }

    /**
     * 账户事实快照（银行 + 户名 + 掩码）。
     *
     * <p>用于冻结到采购订单与垫款上——留痕不可变。
     * <b>只含掩码，不含明文</b>，因此快照本身不是敏感数据泄露源。</p>
     *
     * @return 快照文本
     */
    public String snapshot() {
        return bankName + " / " + accountHolder + " / " + accountNoMask;
    }
}
