package com.company.floorplan.domain.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 车辆估值实体（USED R3.1，{@code vehicle_valuations}，V55 / D04 §3.12）。
 *
 * <p><b>一行 = 一次估值结论</b>：来源事实（四列）+ 审慎值 + 有效期 + 状态。
 * 不可变的是<b>结论</b>，不是这行对象本身——状态列会流转（{@link ValuationStatus}），
 * 但四个来源价与 prudent_value 一旦生效就不再改写（要改就写新行、旧行置 SUPERSEDED，
 * 这是 P20「重估不回溯」的存储保证，V55 表注释）。</p>
 *
 * <p><b>{@code valuationKey} 的式子</b>：{@code VAL-{vin}-{yyyyMMdd}-{seq}}（D00 §13.1）。
 * 它挂在 UNIQUE 列上，因此同一 VIN 同一天的第 N 次估值必须由调用方给出 seq——
 * 用例不自己数历史行数：数出来的 seq 在并发下会撞 UNIQUE，
 * 而幂等重放（同一 seq 重推）恰恰需要它撞。这条键同时是 UC-R3-1 的幂等锚点。</p>
 *
 * <p>{@link #inputs} 不落本表，是 {@code valuation_inputs} 的同事务明细，
 * 读的时候一起带出来（审慎值必须能由它复算，D11 A6）。</p>
 */
public class VehicleValuation {

    /** 主键；{@code advances.valuation_id} 逻辑引用它（V58，跨上下文不建 FK）。 */
    private Long id;

    /** 业务键 {@code VAL-{vin}-{yyyyMMdd}-{seq}}，库内 UNIQUE，对外暴露的就是它。 */
    private String valuationKey;

    /** 車台番号，逻辑引用 {@code vehicle_units.vin}。 */
    private String vin;

    /** 用途，决定哪条硬校验生效（{@link ValuationPurpose}）。 */
    private ValuationPurpose purpose;

    /** 落札价（整数日元）。{@code null} = 这台车不是拍卖场来源，不参与 MIN 集合。 */
    private Long auctionWonPrice;

    /** 第三方查定价。 */
    private Long appraisedValue;

    /** 相場行情价。 */
    private Long marketPrice;

    /** 人工覆盖值；非空即要求 {@link #coSignedBy} 已签（V55 {@code ck_val_override_pair}）。 */
    private Long manualValue;

    /** 审慎值 = 放款基准的唯一来源（D04 §3.12）；NOT NULL，算不出来就不该有这一行。 */
    private long prudentValue;

    /** 本次使用的审慎规则快照：政策后续变更不影响历史行怎么被复算。 */
    private PrudentValueRule prudentRule;

    /** 结论主源（取胜者所属源）；{@code INTERNAL_DEGRADED} 不得作放款基准（闸门 G4）。 */
    private ValuationSource primarySource;

    /** 溯源落札批次行（V61 同包，跨迁移顺序 ⟹ 无 FK）。 */
    private Long auctionLotId;

    /** 溯源行情快照（V56）。 */
    private Long marketSnapshotId;

    /** 查定机构。 */
    private String appraisalProvider;

    /** 查定书编号。 */
    private String appraisalNo;

    /** 评估报告附件路径（本地存储，本仓不引入对象存储）。 */
    private String reportPath;

    /** 附件摘要：路径会被运维挪动，摘要才是不可变证据。 */
    private String reportDigest;

    /** 估值日（DATE，日粒度）：到期判定按日不随时钟。 */
    private LocalDate valuationDate;

    /** 有效截止日 = valuationDate + {@code valuation.valid-days}。 */
    private LocalDate validUntil;

    /** 状态；只能经 {@link ValuationStatus#canMoveTo} 改。 */
    private ValuationStatus status = ValuationStatus.DRAFT;

    /** 人工覆盖操作人（U-07 二人之一）。 */
    private String overriddenBy;

    /** 人工覆盖复核人；必须与 {@link #overriddenBy} 不同（V55 CHECK 兜底）。 */
    private String coSignedBy;

    /** 外部行情入站溯源（integration schema，跨上下文 = 逻辑引用）。 */
    private Long sourceMessageId;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private String createdBy;
    private String updatedBy;

    /** 行乐观锁；与政策代际 {@code ltv_policies.version} 无关（V55 列注释）。 */
    private int version;

    /** 同事务写入的输入明细（不落本表）。 */
    private final List<ValuationInput> inputs = new ArrayList<>();

    /** 是否已过期（按日粒度：有效截止日当天仍有效，次日起过期）。 */
    public boolean expiredOn(LocalDate today) {
        return today != null && validUntil != null && validUntil.isBefore(today);
    }

    /**
     * 本行能否作为放款基准（D08 §3.1/§3.2 的全部静态条件，不含"是否有 ACTIVE 行"这条库层判定）。
     *
     * <p>故意不做的事：不在这里判 purpose（同一行可以只作监控，闸门问的是「这台车有没有能作基准的估值」，
     * 由 {@code VehicleValuationRepository.findAdvanceBase} 的查询条件表达）；
     * 不在这里判人工覆盖是否<b>真</b>有二人（那要读库，domain 层不碰仓储）。
     * 库里 {@code ck_val_override_pair} 已经把「有 overridden_by 必有不同 co_signed_by」钉死，
     * 所以从库读回来的行不需要在这里重复防御。</p>
     */
    public boolean usableAsAdvanceBase() {
        return status == ValuationStatus.ACTIVE && primarySource != null && primarySource.advanceBaseEligible();
    }

    /** 有效输入源数量（按库列算，与 inputs 明细同源）：§3.2「除人工覆盖外单源不可作基准」的分母。 */
    public int eligibleSourceCount() {
        // 判"有没有这个源"只看是否为 NULL，不看数值大小：V55 头注把 NULL（没报）
        // 与 0（源报了零）定为两种事实，0 必须照样进 MIN 集合，
        // 再由 VALU_006（审慎值为 0 不得置 ACTIVE）把它拦在生效之前——
        // 在这里把 0 当"没有"滤掉，等于让"报了零"悄悄退化成"没报"。
        int n = 0;
        if (auctionWonPrice != null) {
            n++;
        }
        if (appraisedValue != null) {
            n++;
        }
        if (marketPrice != null) {
            n++;
        }
        if (manualValue != null) {
            n++;
        }
        return n;
    }

    /** 人工覆盖是否已二人签认（缺第二人 ⟹ {@code VALU_004}，D08 §3.4）。 */
    public boolean overrideFullySigned() {
        return overriddenBy == null
                || (coSignedBy != null && !coSignedBy.isBlank() && !coSignedBy.equals(overriddenBy));
    }

    /** 把一条输入同时写进「明细」和「对应来源列」，避免两处各写各的分叉（V55 头注的两套并存）。 */
    public void addInput(ValuationInput input) {
        inputs.add(input);
        switch (input.source()) {
            case AUCTION_WON_PRICE -> auctionWonPrice = input.value();
            case THIRD_PARTY_APPRAISAL -> appraisedValue = input.value();
            case MARKET_MATRIX -> marketPrice = input.value();
            case MANUAL_OVERRIDE -> manualValue = input.value();
            // INTERNAL_DEGRADED 没有来源列：它是"全部外部源不可得"的结论标记，
            // 塞进任何一列都会被 MIN_OF_SOURCES 当成一个真实来源参与取小。
            case INTERNAL_DEGRADED -> {
            }
        }
    }

    /**
     * 从库里重建明细：<b>只进列表，不动来源列</b>。
     *
     * <p>与 {@link #addInput} 的不对称是有意的：读取路径上来源列已经来自主表那一行，
     * 若再按明细回填，一旦库里主表列与明细行不一致（人工修数、迁移遗漏），
     * 读出来的对象就会以「后读到的那张表」为准，同一个聚合内出现两套事实，
     * 而调用方看不出来。重建必须让不一致留在原处、由自检或闸门暴露。</p>
     */
    public void attachInputFromStorage(ValuationInput input) {
        inputs.add(input);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getValuationKey() {
        return valuationKey;
    }

    public void setValuationKey(String valuationKey) {
        this.valuationKey = valuationKey;
    }

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public ValuationPurpose getPurpose() {
        return purpose;
    }

    public void setPurpose(ValuationPurpose purpose) {
        this.purpose = purpose;
    }

    public Long getAuctionWonPrice() {
        return auctionWonPrice;
    }

    public void setAuctionWonPrice(Long auctionWonPrice) {
        this.auctionWonPrice = auctionWonPrice;
    }

    public Long getAppraisedValue() {
        return appraisedValue;
    }

    public void setAppraisedValue(Long appraisedValue) {
        this.appraisedValue = appraisedValue;
    }

    public Long getMarketPrice() {
        return marketPrice;
    }

    public void setMarketPrice(Long marketPrice) {
        this.marketPrice = marketPrice;
    }

    public Long getManualValue() {
        return manualValue;
    }

    public void setManualValue(Long manualValue) {
        this.manualValue = manualValue;
    }

    public long getPrudentValue() {
        return prudentValue;
    }

    public void setPrudentValue(long prudentValue) {
        this.prudentValue = prudentValue;
    }

    public PrudentValueRule getPrudentRule() {
        return prudentRule;
    }

    public void setPrudentRule(PrudentValueRule prudentRule) {
        this.prudentRule = prudentRule;
    }

    public ValuationSource getPrimarySource() {
        return primarySource;
    }

    public void setPrimarySource(ValuationSource primarySource) {
        this.primarySource = primarySource;
    }

    public Long getAuctionLotId() {
        return auctionLotId;
    }

    public void setAuctionLotId(Long auctionLotId) {
        this.auctionLotId = auctionLotId;
    }

    public Long getMarketSnapshotId() {
        return marketSnapshotId;
    }

    public void setMarketSnapshotId(Long marketSnapshotId) {
        this.marketSnapshotId = marketSnapshotId;
    }

    public String getAppraisalProvider() {
        return appraisalProvider;
    }

    public void setAppraisalProvider(String appraisalProvider) {
        this.appraisalProvider = appraisalProvider;
    }

    public String getAppraisalNo() {
        return appraisalNo;
    }

    public void setAppraisalNo(String appraisalNo) {
        this.appraisalNo = appraisalNo;
    }

    public String getReportPath() {
        return reportPath;
    }

    public void setReportPath(String reportPath) {
        this.reportPath = reportPath;
    }

    public String getReportDigest() {
        return reportDigest;
    }

    public void setReportDigest(String reportDigest) {
        this.reportDigest = reportDigest;
    }

    public LocalDate getValuationDate() {
        return valuationDate;
    }

    public void setValuationDate(LocalDate valuationDate) {
        this.valuationDate = valuationDate;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public void setValidUntil(LocalDate validUntil) {
        this.validUntil = validUntil;
    }

    public ValuationStatus getStatus() {
        return status;
    }

    public void setStatus(ValuationStatus status) {
        this.status = status;
    }

    public String getOverriddenBy() {
        return overriddenBy;
    }

    public void setOverriddenBy(String overriddenBy) {
        this.overriddenBy = overriddenBy;
    }

    public String getCoSignedBy() {
        return coSignedBy;
    }

    public void setCoSignedBy(String coSignedBy) {
        this.coSignedBy = coSignedBy;
    }

    public Long getSourceMessageId() {
        return sourceMessageId;
    }

    public void setSourceMessageId(Long sourceMessageId) {
        this.sourceMessageId = sourceMessageId;
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

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public List<ValuationInput> getInputs() {
        return inputs;
    }
}
