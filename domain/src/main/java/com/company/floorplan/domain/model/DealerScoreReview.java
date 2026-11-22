package com.company.floorplan.domain.model;

import java.time.Instant;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;

/**
 * 双维评级评审流水实体（R1.3；表 {@code dealer_score_reviews}，V46）。
 *
 * <p><b>这张表为什么必须存在</b>：D04 §5.2 只给了 {@code dealers} 的两个分数列，
 * 但 D06 §1.2 的签名是 {@code confirmScores(dealerId, candidateId, operatorA, operatorB)}
 * ——candidateId 要求"候选评分先落库、可被第二人单独签认"。
 * 没有这张表就同时失去三样东西：幂等键 {@code DSCR:{dealerId}:{ts}} 的唯一兜底、
 * G-A4「两维均为第二人确认后的终值」的可核查证据、以及"谁改的/谁签的"的结构化留痕。</p>
 *
 * <p><b>候选值与终值的分离</b>：本行的分数在 {@code CONFIRMED} 之前<b>不写</b> {@code dealers}。
 * 这一条是 G-A4 的实现要点——如果 {@code PENDING} 就顺手回写主数据，
 * 那么"第一人自己填、自己看得到"的分数会直接进闸门，双人签认形同虚设。</p>
 *
 * <p><b>不做的事</b>：不给分数算加权（合成口径是未签字的 U-11，见实现说明），
 * 也不把分数换算成额度、利率或 LTV 档（D06 §1.4「三者不互相推导」）。</p>
 */
public class DealerScoreReview {

    /** 主键（D06 签名里的 candidateId）。 */
    private Long id;
    /** 所属车商。 */
    private Long dealerId;
    /** 评审年度（与年审周期对齐；周期起止未定源，本列只存年份）。 */
    private Integer reviewYear;
    /** 经营资质分（0–100 区间由 U-11 裁决，本轮不设 CHECK、不做上下界校验）。 */
    private Integer operatingScore;
    /** 在库车辆质量分（同上）。 */
    private Integer stockQualityScore;
    /** 评审状态。 */
    private ScoreReviewStatus status;
    /** 第一人（打分人）。 */
    private String scoredBy;
    /** 第二人（签认人）；NULL 表示尚未签认。 */
    private String confirmedBy;
    /** 证据文件路径。 */
    private String evidencePath;
    /** 备注。 */
    private String note;
    /** 幂等键 {@code DSCR:{dealerId}:{ts}}（与 {@code uk_dsr_idem} 对应）。 */
    private String idempotencyKey;
    /** 请求内容指纹：同键不同内容 → CONFLICT_001。 */
    private String requestFingerprint;
    /** 创建时间。 */
    private Instant createdAt;
    /** 更新时间。 */
    private Instant updatedAt;
    /** 乐观锁版本（V46:88 本表带 {@code version}；号段内另两张带版本的表是
     *  {@code used_config_params}(V41:38) 与 {@code inspection_certificates}(V52:49)，
     *  资质三表与 {@code dealers} 都没有）。 */
    private Integer version;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDealerId() { return dealerId; }
    public void setDealerId(Long dealerId) { this.dealerId = dealerId; }

    public Integer getReviewYear() { return reviewYear; }
    public void setReviewYear(Integer reviewYear) { this.reviewYear = reviewYear; }

    public Integer getOperatingScore() { return operatingScore; }
    public void setOperatingScore(Integer operatingScore) { this.operatingScore = operatingScore; }

    public Integer getStockQualityScore() { return stockQualityScore; }
    public void setStockQualityScore(Integer stockQualityScore) { this.stockQualityScore = stockQualityScore; }

    public ScoreReviewStatus getStatus() { return status; }
    public void setStatus(ScoreReviewStatus status) { this.status = status; }

    public String getScoredBy() { return scoredBy; }
    public void setScoredBy(String scoredBy) { this.scoredBy = scoredBy; }

    public String getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(String confirmedBy) { this.confirmedBy = confirmedBy; }

    public String getEvidencePath() { return evidencePath; }
    public void setEvidencePath(String evidencePath) { this.evidencePath = evidencePath; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    /**
     * 是否已满足 G-A4 的证据要求：已签认<b>且</b>两维分数齐备。
     *
     * <p>闸门侧读的是 {@code dealers} 两列，本谓词用于流水侧自证"这行配不配当终值"，
     * 两者缺一都会让"分数存在但没有第二人"的中间态混进主数据。</p>
     *
     * @return true 表示可以回写主数据
     */
    public boolean isConfirmedFinal() {
        return status == ScoreReviewStatus.CONFIRMED
                && operatingScore != null && stockQualityScore != null;
    }

    /**
     * 领域方法：第二人签认。
     *
     * <p><b>这里只校验状态流转，不校验"是不是同一个人"</b>：同人判定需要当前登录用户与
     * 打分人的身份比较，其唯一归属地是 {@code RoleSeparationPolicy}（AGENTS.md §8 的 SOD 红线，
     * 既有决策语义不动），由应用层在调用本方法之前调用它。
     * 本方法管的是"这行还配不配被签认"。</p>
     *
     * <p><b>错误码取舍（已登记）</b>：候选行不是 PENDING 时，D00 §9 的 USED 新增段里
     * 没有任何前缀覆盖"评级流水状态非法"（{@code QUAL_} 段含义是资质缺失/过期，语义不合），
     * 而 D06 §1.3 G-A4 把评级闸门违规统一指向 {@code AUTH_003}。
     * 因此这里复用 {@code AUTH_003}，用 message 区分两种成因；
     * 若收口方要求 409 与 403 分列，需要先在 D00 §9 补号段，本包不自派新码。</p>
     *
     * @param confirmedBy 第二人（签认人）标识
     * @throws BusinessException 非 PENDING 状态抛 {@code AUTH_003}
     */
    public void confirm(String confirmedBy) {
        if (status == null || !status.isPending()) {
            throw new BusinessException(ErrorCode.AUTH_003,
                    "候选评分行不是待签认状态，无法二次签认：status="
                            + (status == null ? "null" : status.name()));
        }
        this.status = ScoreReviewStatus.CONFIRMED;
        this.confirmedBy = confirmedBy;
    }

    /**
     * 领域方法：作废候选（打分错误或年度调整）。
     *
     * @param reason 作废原因（必填，进审计与流水备注）
     * @throws BusinessException 终态行再作废抛 {@code AUTH_003}（理由同 {@link #confirm}）
     */
    public void cancel(String reason) {
        if (status == null || !status.isPending()) {
            throw new BusinessException(ErrorCode.AUTH_003,
                    "只有待签认的候选评分可以作废，已终态的历史行请新开一行");
        }
        this.status = ScoreReviewStatus.CANCELLED;
        if (reason != null && !reason.isBlank()) {
            this.note = reason.trim();
        }
    }
}
