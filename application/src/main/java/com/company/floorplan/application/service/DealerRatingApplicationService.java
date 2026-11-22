package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.DealerScoreReview;
import com.company.floorplan.domain.model.ScoreDimension;
import com.company.floorplan.domain.model.ScoreReviewStatus;
import com.company.floorplan.domain.repository.DealerRepository;
import com.company.floorplan.domain.repository.DealerScoreReviewRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.domain.repository.UsedConfigRepository;
import com.company.floorplan.domain.service.RoleSeparationPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 双维评级用例（应用层；R1.3，UC-R1-3；D06 §1.2 的 {@code DealerRatingApplicationService}）。
 *
 * <p><b>本类只搬运分数，不生产分数</b>。两维（经营资质分／在库车辆质量分）的要素口径、
 * 权重、分域与合成方式都是 {@code [待定源 U-11]}（D06 §1.4），因此：
 * 入参就是<b>已经评出的分值</b>，本类不校验取值区间（0–100 是猜的）、不做加权、
 * 不把两维合成成一个等级、也不把分数换算成额度或 LTV 幅度（D06 §1.4「三者不互相推导」）。
 * 一旦这些推导写进代码，未签字的口径就被焊死，签字后只能靠改代码纠偏。</p>
 *
 * <p><b>候选值与终值的分界（G-A4 的实现要点）</b>：{@code PENDING} 候选行的分数
 * <b>不写</b> {@code dealers} 两列；只有第二人签认成功（{@code CONFIRMED}）后，
 * 才在同一个事务里把终值回写主数据。顺序反过来（先写主数据再签认）
 * 就等于让"第一人自己填自己看得到"的分进闸门，双人约束形同虚设。</p>
 *
 * <p><b>逐维录入</b>：D06 §1.2 的签名是 {@code scoreDimension(dealerId, dimension, input, idemKey)}，
 * 一次只评一维，而 {@code uk_dsr_pending}（V46）规定同车商同年度只有一条在途候选，
 * 所以第二维只能补写进那条候选（见
 * {@link DealerScoreReviewRepository#updateCandidateDimensionScore}）。
 * 这也是 V46 把两个分数列放宽为可空的原因。</p>
 *
 * <p><b>归属说明（如实登记）</b>：D06 §1.1 把 UC-R1-3 的归属写成 {@code risk-service}。
 * 本包按卡面把用例服务落在共享的 {@code application} 模块，
 * 部署单元怎么切（是否真起一个 risk-service）属收口方议题，已登记为实现说明的待裁决项。</p>
 */
@Service
public class DealerRatingApplicationService {

    private final DealerScoreReviewRepository scoreReviewRepository;
    private final DealerRepository dealerRepository;
    private final UsedConfigRepository usedConfigRepository;
    private final SupportRepository supportRepository;

    public DealerRatingApplicationService(DealerScoreReviewRepository scoreReviewRepository,
                                          DealerRepository dealerRepository,
                                          UsedConfigRepository usedConfigRepository,
                                          SupportRepository supportRepository) {
        this.scoreReviewRepository = scoreReviewRepository;
        this.dealerRepository = dealerRepository;
        this.usedConfigRepository = usedConfigRepository;
        this.supportRepository = supportRepository;
    }

    /**
     * 录入一个维度的候选评分（UC-R1-3 第一步）。
     *
     * <p><b>幂等</b>：{@code Idempotency-Key} 请求头有值时按 {@code DSCR:} 的落库点
     * （{@code uk_dsr_idem}）探测——同键同指纹返回首次结果且不写审计，同键不同指纹抛
     * {@code CONFLICT_001}。键缺失时<b>不合成一个"看起来幂等"的键</b>：
     * D00 §13.1 的公式 {@code DSCR:{dealerId}:{ts}} 带时间戳，服务端每次现拼必然各不相同，
     * 写出来的只是"一个没人能重放命中的键"，比没有键更容易让人误判已有保护。
     * 缺失时的重复保护只剩 {@code uk_dsr_pending} ＋ 同维覆盖：不会产生第二条候选，
     * 但重放会多刷一次 {@code version} 与一条审计，这一点如实登记。</p>
     *
     * @param dealerId       车商 ID
     * @param dimension      维度（OPERATING／STOCK_QUALITY）
     * @param score          该维分数（已评出的值；区间口径 U-11 未签字，故不做界内校验）
     * @param reviewYear     评审年度（候选的分组键；年审周期起止 [待定源]，只收年份）
     * @param evidencePath   证据路径（可空）
     * @param note           备注（可空）
     * @param idempotencyKey 幂等键（可空，来自 {@code Idempotency-Key}）
     * @param actor          操作人＝该候选的第一打分人（{@code scored_by}）
     * @param traceId        链路号
     * @return 落库后或首次已有的候选行
     */
    @Transactional
    public DealerScoreReview scoreDimension(Long dealerId, String dimension, Integer score, Integer reviewYear,
                                            String evidencePath, String note, String idempotencyKey,
                                            String actor, String traceId) {
        requireDealer(dealerId);
        ScoreDimension dim = parseEnum(ScoreDimension.class, dimension, "评级维度");
        if (score == null) {
            throw new BusinessException(ErrorCode.VALID_001, Map.of(
                    "reason", "SCORE_REQUIRED",
                    "detail", "候选评分必须给出该维度的分值；清空一维请作废候选行后重新录入"));
        }
        if (reviewYear == null) {
            throw new BusinessException(ErrorCode.VALID_001, Map.of(
                    "reason", "REVIEW_YEAR_REQUIRED",
                    "detail", "评审年度缺失。年审周期起止属 [待定源]，本包不按当前日期反推年度"));
        }

        String key = blankToNull(idempotencyKey);
        String fingerprint = fingerprint(dealerId, reviewYear, dim, score);
        if (key != null) {
            Optional<DealerScoreReview> replay = scoreReviewRepository.findByIdempotencyKey(key);
            if (replay.isPresent()) {
                DealerScoreReview first = replay.get();
                if (Objects.equals(first.getRequestFingerprint(), fingerprint)) {
                    return first;
                }
                throw new BusinessException(ErrorCode.CONFLICT_001, Map.of(
                        "idempotencyKey", key,
                        "reason", "SAME_KEY_DIFFERENT_REQUEST",
                        "existing", String.valueOf(first.getRequestFingerprint()),
                        "incoming", fingerprint));
            }
        }

        Optional<DealerScoreReview> pending = scoreReviewRepository.findPending(dealerId, reviewYear);
        if (pending.isPresent()) {
            DealerScoreReview current = pending.get();
            Integer beforeScore = scoreOf(current, dim);
            boolean updated = scoreReviewRepository.updateCandidateDimensionScore(
                    current.getId(), dim, score, evidencePath, note,
                    current.getVersion() == null ? 0 : current.getVersion());
            if (!updated) {
                // 版本不命中或行已不在 PENDING：并发里有人先签认/作废了。必须让调用方重读，
                // 不能把"我以为改成功了"的分数继续往下走。
                throw new BusinessException(ErrorCode.CONFLICT_001, Map.of(
                        "candidateId", current.getId(),
                        "reason", "CANDIDATE_CHANGED",
                        "expectedVersion", String.valueOf(current.getVersion())));
            }
            supportRepository.writeAudit(actor, "DEALER_SCORE_DIMENSION_RECORDED", "DEALER_SCORE_REVIEW",
                    String.valueOf(current.getId()),
                    dim.name() + "=" + beforeScore, dim.name() + "=" + score, traceId);
            return scoreReviewRepository.findById(current.getId()).orElse(current);
        }

        DealerScoreReview candidate = new DealerScoreReview();
        candidate.setDealerId(dealerId);
        candidate.setReviewYear(reviewYear);
        if (dim == ScoreDimension.OPERATING) {
            candidate.setOperatingScore(score);
        } else {
            candidate.setStockQualityScore(score);
        }
        candidate.setStatus(ScoreReviewStatus.PENDING);
        candidate.setScoredBy(actor);
        candidate.setEvidencePath(blankToNull(evidencePath));
        candidate.setNote(blankToNull(note));
        candidate.setIdempotencyKey(key);
        candidate.setRequestFingerprint(fingerprint);
        DealerScoreReview saved = scoreReviewRepository.insert(candidate);
        supportRepository.writeAudit(actor, "DEALER_SCORE_CANDIDATE_CREATED", "DEALER_SCORE_REVIEW",
                String.valueOf(saved.getId()), null,
                "year=" + reviewYear + "," + dim.name() + "=" + score, traceId);
        return saved;
    }

    /**
     * 第二人签认候选评分（UC-R1-3 第二步，G-A4 的产生点）。
     *
     * <p><b>四道检查，顺序有讲究</b>：</p>
     * <ol>
     *   <li>候选行归属校验：路径上的 {@code dealerId} 必须与候选行的车商一致，
     *       否则 {@code POST /api/dealers/1/scores/confirm} 带着车商 2 的 candidateId 就能改到 2 的档案；</li>
     *   <li>职责分离：{@code scored_by} 与签认人不得同一人
     *       （{@link RoleSeparationPolicy#assertNoSelfApproval}，AGENTS.md §8 的 SOD 红线，
     *       既有决策语义不动，这里只是调用方）；</li>
     *   <li>G-A4 两维齐备：任一维为 NULL 都不允许签认——只签一维就等于
     *       把"另一维还没评"的空档写成终值；</li>
     *   <li>状态与版本：由领域方法 {@code confirm(...)} 判状态、由仓储的
     *       {@code WHERE status='PENDING' AND version=?} 判并发。</li>
     * </ol>
     *
     * <p><b>二人开关</b>：{@code floorplan.used.dealer-rating-two-person-required} 是红线开关，
     * <b>参数表读不到时按 true 处理</b>（fail-closed，见 {@link UsedConfigRepository} 的缺省方向约定）。
     * 即便显式配成 false，签认人仍然必填——G-A4 要的是"第二人确认后的终值"这条证据，
     * 开关能松动的只是"签认人必须不同于打分人"这一点，不能把证据本身去掉。</p>
     *
     * @param dealerId    车商 ID
     * @param candidateId 候选行 ID（D06 签名里的 candidateId）
     * @param operatorA   第一人（打分人）标识；为空时取候选行的 {@code scored_by}
     * @param operatorB   第二人（签认人）标识，必填
     * @param traceId     链路号
     * @return 签认后的流水行（重新读取的库内值）
     */
    @Transactional
    public DealerScoreReview confirmScores(Long dealerId, Long candidateId, String operatorA,
                                           String operatorB, String traceId) {
        requireDealer(dealerId);
        if (candidateId == null) {
            throw new BusinessException(ErrorCode.VALID_001, "候选评分 ID 不能为空");
        }
        DealerScoreReview candidate = scoreReviewRepository.findById(candidateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001,
                        "候选评分不存在：" + candidateId));
        if (!Objects.equals(candidate.getDealerId(), dealerId)) {
            // 不是洁癖：这是"路径参数与业务主键必须一致"的通用防线，
            // 少了它，越车商签认在权限点相同的情况下看不出来。
            throw new BusinessException(ErrorCode.VALID_001, Map.of(
                    "reason", "CANDIDATE_DEALER_MISMATCH",
                    "dealerId", dealerId,
                    "candidateDealerId", String.valueOf(candidate.getDealerId())));
        }

        String scorer = blankToNull(operatorA) == null ? candidate.getScoredBy() : blankToNull(operatorA);
        if (scorer != null && !Objects.equals(scorer, candidate.getScoredBy())) {
            throw new BusinessException(ErrorCode.VALID_001, Map.of(
                    "reason", "SCORER_MISMATCH",
                    "detail", "请求声明的打分人与候选行 scored_by 不一致",
                    "candidateId", candidateId));
        }
        String confirmer = requireText(operatorB, "签认人");
        boolean twoPersonRequired = usedConfigRepository
                .findBoolean(UsedConfigRepository.DEALER_RATING_TWO_PERSON_REQUIRED, LocalDate.now())
                .orElse(Boolean.TRUE);
        if (twoPersonRequired) {
            RoleSeparationPolicy.assertNoSelfApproval(candidate.getScoredBy(), confirmer, "确认车商双维评级");
        }

        if (candidate.getOperatingScore() == null || candidate.getStockQualityScore() == null) {
            // G-A4 违反 → AUTH_003（D06 §1.3 表内定源，不自派新码）。
            throw new BusinessException(ErrorCode.AUTH_003, Map.of(
                    "reason", "SCORE_DIMENSION_MISSING",
                    "candidateId", candidateId,
                    "missing", candidate.getOperatingScore() == null
                            ? ScoreDimension.OPERATING.getColumnName() : ScoreDimension.STOCK_QUALITY.getColumnName(),
                    "detail", "G-A4 要求两维均为第二人确认后的终值，缺任一维都不许签认"));
        }

        // 领域方法管"这行还配不配被签认"（非 PENDING → AUTH_003），
        // 仓储 CAS 管"这一签有没有和别人撞上"。两件事分两层，规则不重复实现。
        candidate.confirm(confirmer);
        boolean confirmed = scoreReviewRepository.confirm(candidateId, confirmer,
                candidate.getVersion() == null ? 0 : candidate.getVersion());
        if (!confirmed) {
            throw new BusinessException(ErrorCode.CONFLICT_001, Map.of(
                    "candidateId", candidateId,
                    "reason", "CANDIDATE_STATE_CHANGED",
                    "expectedVersion", String.valueOf(candidate.getVersion())));
        }

        // 终值回写主数据（G-A4 的落地动作）与签认同事务：
        // 若回写失败必须整体回滚，否则会出现"流水已 CONFIRMED 但 dealers 仍是旧分"的脱钩状态。
        boolean projected = scoreReviewRepository.writeConfirmedScoresToDealer(
                dealerId, candidate.getOperatingScore(), candidate.getStockQualityScore());
        if (!projected) {
            throw new BusinessException(ErrorCode.SYS_001,
                    "评分终值回写 dealers 失败（车商行不存在或被并发删除），本次签认已回滚：" + dealerId);
        }

        supportRepository.writeAudit(confirmer, "DEALER_SCORES_CONFIRMED", "DEALER",
                String.valueOf(dealerId),
                "candidateId=" + candidateId + ",status=PENDING,version=" + candidate.getVersion(),
                "candidateId=" + candidateId + ",status=CONFIRMED"
                        + ",operatingScore=" + candidate.getOperatingScore()
                        + ",stockQualityScore=" + candidate.getStockQualityScore()
                        + ",scoredBy=" + candidate.getScoredBy(), traceId);
        // 重新读一遍：confirmed_by / version / updated_at 都由 SQL 写，本地对象里的值是旧的。
        return scoreReviewRepository.findById(candidateId).orElse(candidate);
    }

    /**
     * 年审快照（UC-R1-6 的数据视图，只读）。
     *
     * <p><b>只给事实，不给结论</b>：本方法返回"该车商该年度的在途候选、最近一次已签认终值、
     * 全部流水"，不算综合分、不给"该不该续期"的判断——年审指标本身（在库车龄结构／估值衰减／
     * 变现能力／WMS 账实相符率，D06 §1.1 UC-R1-6）由 recon＋risk 侧计算，
     * 且这些指标口径同样未签字。</p>
     *
     * <p><b>年度怎么取</b>：{@code asOfDate} 所在<b>自然年</b>。年审周期的起止是 [待定源]，
     * 所以这里不做"跨过 X 月算下一年度"这类区间推算，也不给默认年度——
     * 调用方（含作业）必须明确自己看的是哪一天。</p>
     *
     * <p><b>没有读 {@code dealers} 的两个分数列</b>：{@code Dealer} 是 LEGACY 聚合，
     * 本包按 D03 §2.1 的边界没有给它加字段（加了就要动 {@code JdbcDealerRepository} 的
     * 整表 SQL，与并行包撞车）。因此本视图以 {@code dealer_score_reviews} 为权威源——
     * 它同时带着 {@code confirmed_by} 这条 G-A4 证据，而主数据那两列只是投影。
     * 需要读投影的下游（放款闸门）由收口方决定归口，已登记为实现说明的缺口。</p>
     *
     * @param dealerId  车商 ID
     * @param asOfDate  快照日；为空取今天
     * @return 快照视图
     */
    @Transactional(readOnly = true)
    public Map<String, Object> annualReviewSnapshot(Long dealerId, LocalDate asOfDate) {
        requireDealer(dealerId);
        LocalDate asOf = asOfDate == null ? LocalDate.now() : asOfDate;
        int reviewYear = asOf.getYear();

        Optional<DealerScoreReview> pending = scoreReviewRepository.findPending(dealerId, reviewYear);
        List<DealerScoreReview> history = scoreReviewRepository.findHistory(dealerId, null);
        DealerScoreReview latestConfirmed = null;
        for (DealerScoreReview row : history) {
            // findHistory 按年度倒序、同年倒序，第一条满足"已签认且两维齐备"的就是最近终值。
            if (row.isConfirmedFinal()) {
                latestConfirmed = row;
                break;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dealerId", dealerId);
        result.put("asOfDate", asOf);
        result.put("reviewYear", reviewYear);
        result.put("pendingCandidate", pending.orElse(null));
        result.put("latestConfirmed", latestConfirmed);
        result.put("reviewHistory", history);
        result.put("reviewCount", history.size());
        // G-A4 的流水侧自证：有没有一条"两维齐备且已签认"的行。
        result.put("gA4EvidencePresent", latestConfirmed != null);
        // 未签字项显式暴露出去，省得前端以为"没给就是 0"。
        result.put("scoreRangeCalibrated", false);
        result.put("compositeScore", null);
        result.put("note", "两维权重与合成口径 [待定源 U-11]，本视图不产出综合分；"
                + "scored_by/confirmed_by 才是 G-A4 的证据列");
        return result;
    }

    // =====================================================================
    // 内部小工具
    // =====================================================================

    /** 车商存在性：候选行的 {@code dealer_id} 是 FK，主体不存在时提前给 404 而不是撞 FK。 */
    private void requireDealer(Long dealerId) {
        if (dealerId == null) {
            throw new BusinessException(ErrorCode.VALID_001, "车商 ID 不能为空");
        }
        dealerRepository.findById(dealerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "车商不存在：" + dealerId));
    }

    private static String requireText(String value, String label) {
        String v = blankToNull(value);
        if (v == null) {
            throw new BusinessException(ErrorCode.VALID_001, label + "不能为空");
        }
        return v;
    }

    /** 枚举解析：非法取值回 {@code VALID_001}，不让 {@code valueOf} 抛成 500。 */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String label) {
        String v = blankToNull(raw);
        if (v == null) {
            throw new BusinessException(ErrorCode.VALID_001, label + "不能为空");
        }
        try {
            return Enum.valueOf(type, v.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALID_001,
                    label + "取值非法：" + raw + "（合法值：OPERATING / STOCK_QUALITY）");
        }
    }

    /** 取某维度当前分数（审计的 before 值用）。 */
    private static Integer scoreOf(DealerScoreReview review, ScoreDimension dim) {
        return dim == ScoreDimension.OPERATING ? review.getOperatingScore() : review.getStockQualityScore();
    }

    /** 请求指纹：幂等只比"这次请求是不是同一件事"，不比操作人（换人录同一分数仍是重放）。 */
    private static String fingerprint(Long dealerId, Integer reviewYear, ScoreDimension dim, Integer score) {
        return dealerId + "|" + reviewYear + "|" + dim.name() + "|" + score;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
