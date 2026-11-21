package com.company.floorplan.bank.store;

import com.company.floorplan.bank.model.CreditRestore;
import com.company.floorplan.bank.model.LienRelease;
import com.company.floorplan.bank.model.MatchStatus;
import com.company.floorplan.bank.model.PaymentMatch;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存版 PaymentStore —— 无 PostgreSQL 时的可运行实现，也是单测夹具。
 *
 * <p>幂等语义：按唯一键 {@code putIfAbsent}，与 V17 的
 * {@code uk_payment_match_txn / uk_lien_release_match / uk_credit_restore_match} 对齐。
 * 真实 JDBC 实现必须保留同样的"数据库判重"语义，不能用先查后插。</p>
 *
 * <p>TODO(Wave2 集成)：替换为 JdbcPaymentStore，表结构见 V17__payment_match_and_release.sql。</p>
 */
public class InMemoryPaymentStore implements PaymentStore {

    private final ConcurrentMap<String, PaymentMatch> matches = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, LienRelease> releases = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, CreditRestore> restores = new ConcurrentHashMap<>();

    private final AtomicLong matchSeq = new AtomicLong();
    private final AtomicLong releaseSeq = new AtomicLong();
    private final AtomicLong restoreSeq = new AtomicLong();

    @Override
    public PaymentMatch saveMatchIfAbsent(PaymentMatch match) {
        PaymentMatch candidate = withMatchId(match);
        PaymentMatch existing = matches.putIfAbsent(candidate.key(), candidate);
        return existing != null ? existing : candidate;
    }

    @Override
    public Optional<PaymentMatch> findMatch(String accountRef, String transactionId) {
        return Optional.ofNullable(matches.get(accountRef + "|" + transactionId));
    }

    @Override
    public PaymentMatch saveMatch(PaymentMatch match) {
        PaymentMatch stored = withMatchId(match);
        matches.put(stored.key(), stored);
        return stored;
    }

    @Override
    public List<PaymentMatch> findAllMatches() {
        return List.copyOf(matches.values());
    }

    @Override
    public List<PaymentMatch> findMatchesByStatus(MatchStatus status) {
        return matches.values().stream().filter(m -> m.matchStatus() == status).toList();
    }

    @Override
    public LienRelease saveReleaseIfAbsent(LienRelease release) {
        LienRelease candidate = withReleaseId(release);
        LienRelease existing = releases.putIfAbsent(candidate.matchId(), candidate);
        return existing != null ? existing : candidate;
    }

    @Override
    public LienRelease saveRelease(LienRelease release) {
        LienRelease stored = withReleaseId(release);
        releases.put(stored.matchId(), stored);
        return stored;
    }

    @Override
    public Optional<LienRelease> findReleaseByMatchId(Long matchId) {
        return Optional.ofNullable(releases.get(matchId));
    }

    @Override
    public List<LienRelease> findAllReleases() {
        return List.copyOf(releases.values());
    }

    @Override
    public CreditRestore saveRestoreIfAbsent(CreditRestore restore) {
        CreditRestore candidate = withRestoreId(restore);
        CreditRestore existing = restores.putIfAbsent(candidate.matchId(), candidate);
        return existing != null ? existing : candidate;
    }

    @Override
    public CreditRestore saveRestore(CreditRestore restore) {
        CreditRestore stored = withRestoreId(restore);
        restores.put(stored.matchId(), stored);
        return stored;
    }

    @Override
    public Optional<CreditRestore> findRestoreByMatchId(Long matchId) {
        return Optional.ofNullable(restores.get(matchId));
    }

    @Override
    public List<CreditRestore> findAllRestores() {
        return List.copyOf(restores.values());
    }

    private PaymentMatch withMatchId(PaymentMatch m) {
        if (m.id() != null) {
            return m;
        }
        return new PaymentMatch(matchSeq.incrementAndGet(), m.accountRef(), m.transactionId(),
                m.vin(), m.loanId(), m.advanceId(), m.dealerId(), m.creditLineId(),
                m.matchStatus(), m.matchRule(), m.matchScore(), m.matchedAmount(),
                m.matchedAt(), m.matchedBy(), m.decisionReason(), m.candidateSummary());
    }

    private LienRelease withReleaseId(LienRelease r) {
        if (r.id() != null) {
            return r;
        }
        return new LienRelease(releaseSeq.incrementAndGet(), r.matchId(), r.transactionId(),
                r.vin(), r.loanId(), r.releaseAmount(), r.status(), r.cancellationStatus(),
                r.releasedAt(), r.failedReason(), r.operator());
    }

    private CreditRestore withRestoreId(CreditRestore r) {
        if (r.id() != null) {
            return r;
        }
        return new CreditRestore(restoreSeq.incrementAndGet(), r.matchId(), r.transactionId(),
                r.dealerId(), r.creditLineId(), r.restoreAmount(), r.availableBefore(),
                r.availableAfter(), r.status(), r.idempotencyKey(), r.restoredAt(),
                r.failedReason(), r.operator());
    }
}
