package com.company.floorplan.bank.store;

import com.company.floorplan.bank.model.CreditRestore;
import com.company.floorplan.bank.model.LienRelease;
import com.company.floorplan.bank.model.MatchStatus;
import com.company.floorplan.bank.model.PaymentMatch;
import com.company.floorplan.bank.model.ReleaseStatus;
import com.company.floorplan.bank.model.RestoreStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * V17 三张表的 JDBC 实现（替换 {@link InMemoryPaymentStore} 的生产路径）。
 *
 * <p><b>为什么本轮必须换掉内存版</b>：
 * <ul>
 *   <li>{@code PAYMENT_MATCHED} 要求「匹配结论落库」与「事件写 outbox」在同一个本地事务里。
 *       内存 Map 不参与事务，进程重启还会把已匹配结论全部丢掉——重新扫流水会得到新的 matchId，
 *       事件却仍按 paymentRef 去重，两边对不上；</li>
 *   <li>V17 的 {@code uk_payment_match_txn / uk_lien_release_match / uk_credit_restore_match}
 *       就是为并发幂等准备的，不用它们等于把设计好的防线丢掉。</li>
 * </ul>
 *
 * <p><b>写方法保持"插入或忽略"的语义</b>：{@code ON CONFLICT DO NOTHING} 让数据库判重，
 * 不做先查后插。并发下先查后插必然穿（两个事务都没看到对方未提交的行）。</p>
 *
 * <p><b>内存实现仍保留</b>：{@link InMemoryPaymentStore} 是纯单测夹具，
 * 不让 {@code mvn test} 依赖 5434 的数据库。生产装配在 {@code BankServiceApplication} 里切换。</p>
 */
public class JdbcPaymentStore implements PaymentStore {

    private final JdbcTemplate jdbc;

    public JdbcPaymentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PaymentMatch> MATCH_MAPPER = (rs, i) -> new PaymentMatch(
            rs.getLong("id"),
            rs.getString("account_ref"),
            rs.getString("transaction_id"),
            rs.getString("vin"),
            nullableLong(rs.getObject("loan_id")),
            nullableLong(rs.getObject("advance_id")),
            nullableLong(rs.getObject("dealer_id")),
            nullableLong(rs.getObject("credit_line_id")),
            MatchStatus.valueOf(rs.getString("match_status")),
            rs.getString("match_rule"),
            rs.getBigDecimal("match_score"),
            rs.getLong("matched_amount"),
            nullableInstant(rs.getTimestamp("matched_at")),
            rs.getString("matched_by"),
            rs.getString("decision_reason"),
            rs.getString("candidate_summary"));

    private static final RowMapper<LienRelease> RELEASE_MAPPER = (rs, i) -> new LienRelease(
            rs.getLong("id"),
            rs.getLong("match_id"),
            rs.getString("transaction_id"),
            rs.getString("vin"),
            nullableLong(rs.getObject("loan_id")),
            rs.getLong("release_amount"),
            ReleaseStatus.valueOf(rs.getString("status")),
            rs.getString("cancellation_status"),
            nullableInstant(rs.getTimestamp("released_at")),
            rs.getString("failed_reason"),
            rs.getString("operator"));

    private static final RowMapper<CreditRestore> RESTORE_MAPPER = (rs, i) -> new CreditRestore(
            rs.getLong("id"),
            rs.getLong("match_id"),
            rs.getString("transaction_id"),
            rs.getLong("dealer_id"),
            nullableLong(rs.getObject("credit_line_id")),
            rs.getLong("restore_amount"),
            nullableLong(rs.getObject("available_before")),
            nullableLong(rs.getObject("available_after")),
            RestoreStatus.valueOf(rs.getString("status")),
            rs.getString("idempotency_key"),
            nullableInstant(rs.getTimestamp("restored_at")),
            rs.getString("failed_reason"),
            rs.getString("operator"));

    // ------------------------------------------------------------------
    // payment_matches
    // ------------------------------------------------------------------

    @Override
    public PaymentMatch saveMatchIfAbsent(PaymentMatch match) {
        Long id = jdbc.query(
                "INSERT INTO payment_matches (transaction_id, account_ref, vin, loan_id, advance_id, "
                        + "dealer_id, credit_line_id, match_status, match_rule, match_score, matched_amount, "
                        + "matched_at, matched_by, decision_reason, candidate_summary) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT (account_ref, transaction_id) DO NOTHING RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                match.transactionId(), match.accountRef(), match.vin(), match.loanId(), match.advanceId(),
                match.dealerId(), match.creditLineId(), match.matchStatus().name(), match.matchRule(),
                match.matchScore(), match.matchedAmount(), timestamp(match.matchedAt()),
                match.matchedBy(), match.decisionReason(), match.candidateSummary());
        if (id != null) {
            return withMatchId(match, id);
        }
        // 唯一键冲突：返回数据库中已有的那一行（不能返回本次候选，否则调用方会拿到未落库的 id）
        return findMatch(match.accountRef(), match.transactionId())
                .orElseThrow(() -> new IllegalStateException(
                        "payment_matches 插入冲突但读不回已有行：" + match.key()));
    }

    @Override
    public Optional<PaymentMatch> findMatch(String accountRef, String transactionId) {
        return jdbc.query("SELECT * FROM payment_matches WHERE account_ref = ? AND transaction_id = ?",
                MATCH_MAPPER, accountRef, transactionId).stream().findFirst();
    }

    @Override
    public PaymentMatch saveMatch(PaymentMatch match) {
        int updated = jdbc.update(
                "UPDATE payment_matches SET vin=?, loan_id=?, advance_id=?, dealer_id=?, credit_line_id=?, "
                        + "match_status=?, match_rule=?, match_score=?, matched_amount=?, matched_at=?, "
                        + "matched_by=?, decision_reason=?, candidate_summary=?, updated_at=now() "
                        + "WHERE account_ref=? AND transaction_id=?",
                match.vin(), match.loanId(), match.advanceId(), match.dealerId(), match.creditLineId(),
                match.matchStatus().name(), match.matchRule(), match.matchScore(), match.matchedAmount(),
                timestamp(match.matchedAt()), match.matchedBy(), match.decisionReason(),
                match.candidateSummary(), match.accountRef(), match.transactionId());
        if (updated == 0) {
            return saveMatchIfAbsent(match);
        }
        return findMatch(match.accountRef(), match.transactionId())
                .orElseThrow(() -> new IllegalStateException(
                        "payment_matches 更新成功但读不回：matchId=" + match.id()));
    }

    @Override
    public List<PaymentMatch> findAllMatches() {
        return jdbc.query("SELECT * FROM payment_matches ORDER BY id DESC", MATCH_MAPPER);
    }

    @Override
    public List<PaymentMatch> findMatchesByStatus(MatchStatus status) {
        return jdbc.query("SELECT * FROM payment_matches WHERE match_status = ? ORDER BY id DESC",
                MATCH_MAPPER, status.name());
    }

    // ------------------------------------------------------------------
    // lien_releases
    // ------------------------------------------------------------------

    @Override
    public LienRelease saveReleaseIfAbsent(LienRelease release) {
        Long id = jdbc.query(
                "INSERT INTO lien_releases (match_id, transaction_id, vin, loan_id, release_amount, "
                        + "status, cancellation_status, released_at, failed_reason, operator) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT (match_id) DO NOTHING RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                release.matchId(), release.transactionId(), release.vin(), release.loanId(),
                release.releaseAmount(), release.status().name(), release.cancellationStatus(),
                timestamp(release.releasedAt()), release.failedReason(), release.operator());
        if (id != null) {
            return withReleaseId(release, id);
        }
        return findReleaseByMatchId(release.matchId())
                .orElseThrow(() -> new IllegalStateException(
                        "lien_releases 插入冲突但读不回已有行：matchId=" + release.matchId()));
    }

    @Override
    public LienRelease saveRelease(LienRelease release) {
        int updated = jdbc.update(
                "UPDATE lien_releases SET status=?, cancellation_status=?, released_at=?, failed_reason=? "
                        + "WHERE match_id=?",
                release.status().name(), release.cancellationStatus(), timestamp(release.releasedAt()),
                release.failedReason(), release.matchId());
        if (updated == 0) {
            return saveReleaseIfAbsent(release);
        }
        return findReleaseByMatchId(release.matchId())
                .orElseThrow(() -> new IllegalStateException(
                        "lien_releases 更新成功但读不回：matchId=" + release.matchId()));
    }

    @Override
    public Optional<LienRelease> findReleaseByMatchId(Long matchId) {
        return jdbc.query("SELECT * FROM lien_releases WHERE match_id = ?", RELEASE_MAPPER, matchId)
                .stream().findFirst();
    }

    @Override
    public List<LienRelease> findAllReleases() {
        return jdbc.query("SELECT * FROM lien_releases ORDER BY id DESC", RELEASE_MAPPER);
    }

    // ------------------------------------------------------------------
    // credit_restores（bank 侧「回补请求」台账；真实写入在 credit-service）
    // ------------------------------------------------------------------

    @Override
    public CreditRestore saveRestoreIfAbsent(CreditRestore restore) {
        Long id = jdbc.query(
                "INSERT INTO credit_restores (match_id, transaction_id, dealer_id, credit_line_id, "
                        + "restore_amount, available_before, available_after, status, idempotency_key, "
                        + "restored_at, failed_reason, operator) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT (match_id) DO NOTHING RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                restore.matchId(), restore.transactionId(), restore.dealerId(), restore.creditLineId(),
                restore.restoreAmount(), restore.availableBefore(), restore.availableAfter(),
                restore.status().name(), restore.idempotencyKey(), timestamp(restore.restoredAt()),
                restore.failedReason(), restore.operator());
        if (id != null) {
            return withRestoreId(restore, id);
        }
        return findRestoreByMatchId(restore.matchId())
                .orElseThrow(() -> new IllegalStateException(
                        "credit_restores 插入冲突但读不回已有行：matchId=" + restore.matchId()));
    }

    @Override
    public CreditRestore saveRestore(CreditRestore restore) {
        int updated = jdbc.update(
                "UPDATE credit_restores SET available_before=?, available_after=?, status=?, "
                        + "restored_at=?, failed_reason=? WHERE match_id=?",
                restore.availableBefore(), restore.availableAfter(), restore.status().name(),
                timestamp(restore.restoredAt()), restore.failedReason(), restore.matchId());
        if (updated == 0) {
            return saveRestoreIfAbsent(restore);
        }
        return findRestoreByMatchId(restore.matchId())
                .orElseThrow(() -> new IllegalStateException(
                        "credit_restores 更新成功但读不回：matchId=" + restore.matchId()));
    }

    @Override
    public Optional<CreditRestore> findRestoreByMatchId(Long matchId) {
        return jdbc.query("SELECT * FROM credit_restores WHERE match_id = ?", RESTORE_MAPPER, matchId)
                .stream().findFirst();
    }

    @Override
    public List<CreditRestore> findAllRestores() {
        return jdbc.query("SELECT * FROM credit_restores ORDER BY id DESC", RESTORE_MAPPER);
    }

    // ------------------------------------------------------------------

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static Instant nullableInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static PaymentMatch withMatchId(PaymentMatch m, Long id) {
        return new PaymentMatch(id, m.accountRef(), m.transactionId(), m.vin(), m.loanId(), m.advanceId(),
                m.dealerId(), m.creditLineId(), m.matchStatus(), m.matchRule(), m.matchScore(),
                m.matchedAmount(), m.matchedAt(), m.matchedBy(), m.decisionReason(), m.candidateSummary());
    }

    private static LienRelease withReleaseId(LienRelease r, Long id) {
        return new LienRelease(id, r.matchId(), r.transactionId(), r.vin(), r.loanId(), r.releaseAmount(),
                r.status(), r.cancellationStatus(), r.releasedAt(), r.failedReason(), r.operator());
    }

    private static CreditRestore withRestoreId(CreditRestore r, Long id) {
        return new CreditRestore(id, r.matchId(), r.transactionId(), r.dealerId(), r.creditLineId(),
                r.restoreAmount(), r.availableBefore(), r.availableAfter(), r.status(),
                r.idempotencyKey(), r.restoredAt(), r.failedReason(), r.operator());
    }
}
