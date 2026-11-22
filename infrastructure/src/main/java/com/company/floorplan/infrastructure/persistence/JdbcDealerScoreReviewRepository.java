package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.DealerScoreReview;
import com.company.floorplan.domain.model.ScoreDimension;
import com.company.floorplan.domain.model.ScoreReviewStatus;
import com.company.floorplan.domain.repository.DealerScoreReviewRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 双维评级评审流水仓储实现（R1.3；表 {@code dealer_score_reviews}，V46）。
 *
 * <p><b>本类是本号段唯一做版本 CAS 的地方</b>：V46 只给这张表带了 {@code version} 列，
 * {@code dealers} 没有（V1 建表未带、V46 只 ADD COLUMN）。因此
 * {@link #confirm} / {@link #cancel} 的 WHERE 同时带 {@code status='PENDING'} 与
 * {@code version=?}：前者防"已签认的行再签一次"，后者防"读候选与签认之间分数被人改掉"。</p>
 *
 * <p><b>候选值绝不外溢到主数据</b>：{@link #writeConfirmedScoresToDealer} 只是执行器，
 * "只有 CONFIRMED 才准写"这条规则的唯一归属地是应用层
 * （{@code DealerScoreReview.isConfirmedFinal()}），这里再判一次就会让规则活在两层、改一处漏一处。</p>
 */
@Repository
public class JdbcDealerScoreReviewRepository implements DealerScoreReviewRepository {

    private final JdbcTemplate jdbc;

    public JdbcDealerScoreReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 评审流水映射（列名与 V46 的 dealer_score_reviews 逐列对应）。 */
    private static final RowMapper<DealerScoreReview> MAPPER = (rs, i) -> {
        DealerScoreReview r = new DealerScoreReview();
        r.setId(rs.getLong("id"));
        r.setDealerId(rs.getLong("dealer_id"));
        r.setReviewYear(intOrNull(rs, "review_year"));
        r.setOperatingScore(intOrNull(rs, "operating_score"));
        r.setStockQualityScore(intOrNull(rs, "stock_quality_score"));
        String status = rs.getString("status");
        r.setStatus(status == null ? null : ScoreReviewStatus.valueOf(status));
        r.setScoredBy(rs.getString("scored_by"));
        r.setConfirmedBy(rs.getString("confirmed_by"));
        r.setEvidencePath(rs.getString("evidence_path"));
        r.setNote(rs.getString("note"));
        r.setIdempotencyKey(rs.getString("idempotency_key"));
        r.setRequestFingerprint(rs.getString("request_fingerprint"));
        r.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        r.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        r.setVersion(intOrNull(rs, "version"));
        return r;
    };

    @Override
    public Optional<DealerScoreReview> findById(Long id) {
        return jdbc.query("SELECT * FROM dealer_score_reviews WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    @Override
    public Optional<DealerScoreReview> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("SELECT * FROM dealer_score_reviews WHERE idempotency_key = ?",
                MAPPER, idempotencyKey.trim()).stream().findFirst();
    }

    @Override
    public Optional<DealerScoreReview> findPending(Long dealerId, Integer reviewYear) {
        // 最多一条由 uk_dsr_pending（dealer_id, review_year WHERE status='PENDING'）保证。
        return jdbc.query("SELECT * FROM dealer_score_reviews "
                        + "WHERE dealer_id = ? AND review_year = ? AND status = 'PENDING'",
                MAPPER, dealerId, reviewYear).stream().findFirst();
    }

    @Override
    public List<DealerScoreReview> findHistory(Long dealerId, Integer reviewYear) {
        if (reviewYear == null) {
            return jdbc.query("SELECT * FROM dealer_score_reviews WHERE dealer_id = ? "
                    + "ORDER BY review_year DESC, id DESC", MAPPER, dealerId);
        }
        return jdbc.query("SELECT * FROM dealer_score_reviews WHERE dealer_id = ? AND review_year = ? "
                + "ORDER BY id DESC", MAPPER, dealerId, reviewYear);
    }

    @Override
    public DealerScoreReview insert(DealerScoreReview review) {
        try {
            Long id = jdbc.queryForObject(
                    "INSERT INTO dealer_score_reviews (dealer_id, review_year, operating_score, "
                            + "stock_quality_score, status, scored_by, confirmed_by, evidence_path, note, "
                            + "idempotency_key, request_fingerprint, version) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,0) RETURNING id",
                    Long.class,
                    review.getDealerId(), review.getReviewYear(), review.getOperatingScore(),
                    review.getStockQualityScore(),
                    review.getStatus() == null ? ScoreReviewStatus.PENDING.name() : review.getStatus().name(),
                    review.getScoredBy(), review.getConfirmedBy(), review.getEvidencePath(),
                    review.getNote(), review.getIdempotencyKey(), review.getRequestFingerprint());
            review.setId(id);
            review.setVersion(0);
            if (review.getStatus() == null) {
                review.setStatus(ScoreReviewStatus.PENDING);
            }
            return review;
        } catch (DuplicateKeyException ex) {
            // 两个唯一锚点都可能撞：uk_dsr_idem（同请求重放）与 uk_dsr_pending（并发开两条候选）。
            // 不在这里回查已存在行——PG 撞约束后当前事务已中止，同事务内 SELECT 只会再报
            // "current transaction is aborted"，把 409 变成 500。调用方应先查后写。
            throw new BusinessException(ErrorCode.CONFLICT_001,
                    "候选评分已存在（幂等键 " + review.getIdempotencyKey()
                            + " 或该车商该年度已有待签认候选），请重读后重试");
        }
    }

    @Override
    public boolean updateCandidateDimensionScore(Long id, ScoreDimension dimension, Integer score,
                                                 String evidencePath, String note, int expectedVersion) {
        if (dimension == null) {
            throw new BusinessException(ErrorCode.VALID_001, "逐维录入缺少评级维度");
        }
        if (score == null) {
            // 见接口注释：清空一维请作废候选后新开一行，本方法不提供把分数写回 NULL 的路径。
            throw new BusinessException(ErrorCode.VALID_001,
                    "维度 " + dimension.name() + " 的分数不能为空（清空请作废候选行后重新录入）");
        }
        // 列名来自枚举常量，取值集合封闭（只有 operating_score / stock_quality_score 两个字面量），
        // 不是用户输入；再加一道白名单，是为了将来给 ScoreDimension 加第三维时在这里立刻失败，
        // 而不是拼出一条没有该列的 SQL 静默少写。
        String column = dimension.getColumnName();
        if (!"operating_score".equals(column) && !"stock_quality_score".equals(column)) {
            throw new BusinessException(ErrorCode.SYS_001,
                    "评级维度映射到未知列：" + column + "（仓储未同步，拒绝拼接列名）");
        }
        // 另一维用 COALESCE 保住：逐维录入只动自己那一列，否则第二维录入会把第一维清空。
        // 状态守卫 status='PENDING'：终态行是 G-A4 的证据，改分必须新开候选。
        int rows = jdbc.update("UPDATE dealer_score_reviews SET " + column + " = ?, "
                + "evidence_path = COALESCE(?, evidence_path), note = COALESCE(?, note), "
                + "version = version + 1, updated_at = now() "
                + "WHERE id = ? AND status = 'PENDING' AND version = ?",
                score, blankToNull(evidencePath), blankToNull(note), id, expectedVersion);
        return rows > 0;
    }

    @Override
    public boolean confirm(Long id, String confirmedBy, int expectedVersion) {
        // status 与 version 两个条件都在 WHERE 里：任一不满足就是 0 行，调用方重读重判。
        int rows = jdbc.update("UPDATE dealer_score_reviews SET status='CONFIRMED', confirmed_by=?, "
                + "version=version+1, updated_at=now() "
                + "WHERE id=? AND status='PENDING' AND version=?", confirmedBy, id, expectedVersion);
        return rows > 0;
    }

    @Override
    public boolean cancel(Long id, String note, int expectedVersion) {
        int rows = jdbc.update("UPDATE dealer_score_reviews SET status='CANCELLED', "
                + "note=COALESCE(?, note), version=version+1, updated_at=now() "
                + "WHERE id=? AND status='PENDING' AND version=?", note, id, expectedVersion);
        return rows > 0;
    }

    @Override
    public boolean writeConfirmedScoresToDealer(Long dealerId, Integer operatingScore,
                                                Integer stockQualityScore) {
        // dealers 没有 version 列，因此这里没有 CAS 可用（取舍见接口注释）；
        // 并发窗口由"与 confirm() 同事务"压缩：同一评审候选的两次签认只可能有一个成功。
        int rows = jdbc.update("UPDATE dealers SET operating_score=?, stock_quality_score=?, updated_at=now() "
                + "WHERE id=?", operatingScore, stockQualityScore, dealerId);
        return rows > 0;
    }

    // ---- 小工具 ----

    /** 空白串按"不改动"处理（与 {@code updateCandidateDimensionScore} 的 COALESCE 语义配对）。 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** INT 列取值：NULL 与 0 是两件事（未评 vs 评了 0），所以不能直接 getInt。 */
    private static Integer intOrNull(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
