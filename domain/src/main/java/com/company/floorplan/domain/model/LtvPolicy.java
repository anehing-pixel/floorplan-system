package com.company.floorplan.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * LTV 政策（USED R3.2/R3.4，{@code ltv_policies}，V57 / D04 §3.14）。
 *
 * <p><b>这里存的是「判断」不是「事实」</b>：V55/V56 存市场报了什么价，本表存「什么样的车按几成垫」。
 * 判断必须版本化，且生效动作要与维护动作分人（L5 + V57 {@code ck_ltv_policy_sod}），
 * 否则政策可以自己改、自己生效。</p>
 *
 * <p>{@code version} 是<b>政策代际</b>（业务版本号，与 {@code policy_code} 一起构成
 * {@code uk_ltv_policy}），不要和行乐观锁混为一谈——V57 的列注释专门说了这两者无关。
 * 放款快照写的是 {@code ltv_policy_code + ltv_policy_version}（V58），
 * 缺了版本号就只能指回「最新版」，可复算性当场断掉。</p>
 */
public class LtvPolicy {

    private Long id;

    /** 政策码（同一码同时最多一个 ACTIVE，V57 {@code uk_policy_active}）。 */
    private String policyCode;

    /** 政策代际，从 1 起（V57 CHECK {@code version >= 1}）。 */
    private int version = 1;

    private String policyName;

    /** 状态；只能经 {@link LtvPolicyStatus#canMoveTo} 改。 */
    private LtvPolicyStatus status = LtvPolicyStatus.DRAFT;

    /** 生效起日。 */
    private LocalDate effectiveFrom;

    /** 生效止日；{@code null} = 长期有效。 */
    private LocalDate effectiveTo;

    /**
     * 兜底成数 [待定源 D-05/D-06]：<b>只在零命中时使用</b>，命中多带不进兜底（D08 §4.2 兜底带段）。
     *
     * <p>V57 seed 为 NULL（未签字不落数）。L3 要求它严格小于所有带 {@code max_ratio} 的最小值，
     * 这条在应用层校验（{@code LtvPolicyValidator}），库里表达不了（要跨行聚合）。</p>
     */
    private BigDecimal defaultRatio;

    private String createdBy;
    private String approvedBy;
    private OffsetDateTime approvedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    /** 带集合：随政策整批读写，不落本表。 */
    private final List<LtvBand> bands = new ArrayList<>();

    /** 本行是否处于「带集合冻结」状态（L5：ACTIVE 不得改带，违反即 {@code LTV_004}）。 */
    public boolean bandsFrozen() {
        return status != null && !status.canEditBands();
    }

    /** 是否已批准生效（闸门 G3–G6 只认 ACTIVE 版本）。 */
    public boolean isActive() {
        return status == LtvPolicyStatus.ACTIVE;
    }

    /** 给定日期是否落在本版本的生效区间内。 */
    public boolean effectiveOn(LocalDate date) {
        if (!isActive() || date == null || effectiveFrom == null) {
            return false;
        }
        if (date.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo == null || !date.isAfter(effectiveTo);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPolicyCode() {
        return policyCode;
    }

    public void setPolicyCode(String policyCode) {
        this.policyCode = policyCode;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getPolicyName() {
        return policyName;
    }

    public void setPolicyName(String policyName) {
        this.policyName = policyName;
    }

    public LtvPolicyStatus getStatus() {
        return status;
    }

    public void setStatus(LtvPolicyStatus status) {
        this.status = status;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(LocalDate effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public BigDecimal getDefaultRatio() {
        return defaultRatio;
    }

    public void setDefaultRatio(BigDecimal defaultRatio) {
        this.defaultRatio = defaultRatio;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public OffsetDateTime getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(OffsetDateTime approvedAt) {
        this.approvedAt = approvedAt;
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

    public List<LtvBand> getBands() {
        return bands;
    }

    /**
     * 追加一条分档带（按请求装配政策时、以及从库里重建时用）。
     *
     * <p>只追加不校验：带集合的合法性（priority 唯一、比率、L1〜L4）是
     * {@code LtvPolicyValidator} 的职责，混进模型就会变成「装配到一半的策略不能看一眼」。
     * 试算与 validate 端点都要能把一份<b>尚未通过校验</b>的带集合原样回给调用方。</p>
     */
    public void addBand(LtvBand band) {
        if (band != null) {
            bands.add(band);
        }
    }
}
