package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.AnnualReview;
import com.company.floorplan.domain.repository.AnnualReviewRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 额度年审任务仓储实现。
 */
@Repository
public class JdbcAnnualReviewRepository implements AnnualReviewRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<AnnualReview> MAPPER = (rs, i) -> {
        AnnualReview r = new AnnualReview();
        r.setId(rs.getLong("id"));
        r.setReviewNo(rs.getString("review_no"));
        Object dealerId = rs.getObject("dealer_id");
        r.setDealerId(dealerId == null ? null : ((Number) dealerId).longValue());
        Object creditLineId = rs.getObject("credit_line_id");
        r.setCreditLineId(creditLineId == null ? null : ((Number) creditLineId).longValue());
        r.setReviewCycle(rs.getString("review_cycle"));
        r.setStatus(rs.getString("status"));
        r.setFrozenAmount(rs.getLong("frozen_amount"));
        r.setResultNote(rs.getString("result_note"));
        r.setInitiatedAt(toLdt(rs.getTimestamp("initiated_at")));
        r.setCompletedAt(toLdt(rs.getTimestamp("completed_at")));
        return r;
    };

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    public JdbcAnnualReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AnnualReview save(AnnualReview r) {
        jdbc.update("INSERT INTO annual_reviews (review_no, dealer_id, credit_line_id, review_cycle, status, "
                        + "frozen_amount, result_note, initiated_at, completed_at) VALUES (?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT (review_no) DO UPDATE SET review_cycle=EXCLUDED.review_cycle, "
                        + "status=EXCLUDED.status, frozen_amount=EXCLUDED.frozen_amount, result_note=EXCLUDED.result_note, "
                        + "completed_at=EXCLUDED.completed_at",
                r.getReviewNo(), r.getDealerId(), r.getCreditLineId(), r.getReviewCycle(),
                r.getStatus() == null ? "PENDING" : r.getStatus(), r.getFrozenAmount(),
                r.getResultNote(), r.getInitiatedAt(), r.getCompletedAt());
        return r;
    }

    @Override
    public Optional<AnnualReview> findByReviewNo(String reviewNo) {
        return jdbc.query("SELECT * FROM annual_reviews WHERE review_no = ?", MAPPER, reviewNo)
                .stream().findFirst();
    }

    @Override
    public List<AnnualReview> findByDealerId(Long dealerId) {
        return jdbc.query("SELECT * FROM annual_reviews WHERE dealer_id = ? ORDER BY initiated_at DESC",
                MAPPER, dealerId);
    }

    @Override
    public Optional<AnnualReview> findPendingByCreditLine(Long creditLineId) {
        return jdbc.query("SELECT * FROM annual_reviews WHERE credit_line_id = ? "
                        + "AND status IN ('PENDING','IN_PROGRESS') ORDER BY id DESC LIMIT 1",
                MAPPER, creditLineId).stream().findFirst();
    }

    @Override
    public Optional<AnnualReview> findLatestByCreditLine(Long creditLineId) {
        return jdbc.query("SELECT * FROM annual_reviews WHERE credit_line_id = ? ORDER BY id DESC LIMIT 1",
                MAPPER, creditLineId).stream().findFirst();
    }
}
