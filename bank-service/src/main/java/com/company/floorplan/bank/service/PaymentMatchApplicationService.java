package com.company.floorplan.bank.service;

import com.company.floorplan.bank.client.LoanLookupClient;
import com.company.floorplan.bank.client.LoanSnapshot;
import com.company.floorplan.bank.matcher.MatchDecision;
import com.company.floorplan.bank.matcher.VinMatcher;
import com.company.floorplan.bank.model.CreditRestore;
import com.company.floorplan.bank.model.LienRelease;
import com.company.floorplan.bank.model.MatchStatus;
import com.company.floorplan.bank.model.PaymentMatch;
import com.company.floorplan.bank.model.ReleaseStatus;
import com.company.floorplan.bank.store.PaymentStore;
import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.PaymentTransaction;
import com.company.floorplan.domain.repository.PaymentTransactionRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 回款匹配应用服务：把"流水 → VIN → 借据 → 解押 → 回补"串成一条可调用链。
 *
 * <p><b>采集侧完全不动</b>：流水从 {@link PaymentTransactionRepository#findUnmatched()} 只读取得，
 * 匹配结论写 V17 三表；流水表的 matchStatus 仍由采集侧维护，本服务不提供 update 通道，
 * 以免破坏 V15 "事实不可改"的纪律。TODO(Wave2)：由 loan/payment 联合补流水状态回写。</p>
 *
 * <p><b>操作人</b>由 Controller 从令牌解析后传入，本类不信任任何来自请求体的自称操作人。</p>
 */
public class PaymentMatchApplicationService {

    private final PaymentTransactionRepository transactionRepository;
    private final LoanLookupClient loanLookupClient;
    private final PaymentStore store;
    private final ReleaseOrchestrator orchestrator;

    public PaymentMatchApplicationService(PaymentTransactionRepository transactionRepository,
                                          LoanLookupClient loanLookupClient,
                                          PaymentStore store,
                                          ReleaseOrchestrator orchestrator) {
        this.transactionRepository = transactionRepository;
        this.loanLookupClient = loanLookupClient;
        this.store = store;
        this.orchestrator = orchestrator;
    }

    /** 批量触发：扫描全部未匹配流水，逐笔匹配并释放。 */
    public MatchRunSummary matchAll(String operator) {
        List<PaymentTransaction> pending = transactionRepository.findUnmatched();
        List<MatchRunResult> details = new ArrayList<>();
        int matched = 0;
        int released = 0;
        for (PaymentTransaction tx : pending) {
            if (!tx.isMatchCandidate()) {
                continue;
            }
            MatchRunResult result = matchOne(tx.getAccountRef(), tx.getTransactionId(), operator);
            details.add(result);
            if (result.matched()) {
                matched++;
            }
            if (result.released()) {
                released++;
            }
        }
        return new MatchRunSummary(details.size(), matched, details.size() - matched, released, details);
    }

    /** 单笔触发：已处理过的流水直接返回既有结论（幂等）。 */
    public MatchRunResult matchOne(String accountRef, String transactionId, String operator) {
        Optional<PaymentMatch> existing = store.findMatch(accountRef, transactionId);
        if (existing.isPresent()) {
            return toResult(existing.get(), false);
        }

        PaymentTransaction tx = findTransaction(accountRef, transactionId);
        if (tx == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_001,
                    Map.of("transactionId", transactionId, "accountRef", accountRef));
        }

        List<String> vins = VinMatcher.extractVins(tx.getReference());
        List<LoanSnapshot> loans = loanLookupClient.findActiveByVins(vins);
        MatchDecision decision = VinMatcher.match(tx, loans);

        PaymentMatch fresh = PaymentMatch.unmatched(accountRef, transactionId, tx.getAmount(),
                decision.reason(), String.valueOf(decision.candidateVins()));
        if (decision.isMatched()) {
            LoanSnapshot loan = decision.loan();
            fresh = fresh.withDecision(MatchStatus.AUTO_MATCHED, decision.vin(), loan.id(),
                    loan.advanceId(), loan.dealerId(), null, decision.rule(), decision.score(),
                    Instant.now(), "system", decision.reason());
        }
        PaymentMatch saved = store.saveMatchIfAbsent(fresh);

        boolean released = false;
        if (saved.isReleasable() && decision.loan() != null) {
            ReleaseResult result = orchestrator.orchestrate(saved, decision.loan(), operator);
            released = result.release() != null && result.release().status() == ReleaseStatus.RELEASED;
        }
        return toResult(saved, released);
    }

    /** 人工绑定 VIN：借据必须存在且未结清；操作人是令牌身份，不是请求体自称。 */
    public PaymentMatch manualMatch(String accountRef, String transactionId, String vin, String operator) {
        if (vin == null || vin.isBlank()) {
            throw new BusinessException(ErrorCode.VALID_001, "VIN 不能为空");
        }
        LoanSnapshot loan = loanLookupClient.findActiveByVin(vin)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_001,
                        "VIN " + vin + " 无未结清借据，不可人工绑定"));
        PaymentTransaction tx = findTransaction(accountRef, transactionId);
        long amount = tx == null ? 0L : tx.getAmount();

        PaymentMatch base = store.findMatch(accountRef, transactionId)
                .orElseGet(() -> store.saveMatchIfAbsent(
                        PaymentMatch.unmatched(accountRef, transactionId, amount, "人工绑定前挂起", null)));
        PaymentMatch changed = base.withDecision(MatchStatus.MANUAL_MATCHED, vin, loan.id(),
                loan.advanceId(), loan.dealerId(), null, "MANUAL", null,
                Instant.now(), operator, "人工绑定 VIN=" + vin);
        PaymentMatch saved = store.saveMatch(changed);
        orchestrator.orchestrate(saved, loan, operator);
        return saved;
    }

    /** 人工判定为非本业务：理由本身是审计证据（说明账户混用了其他生意）。 */
    public PaymentMatch ignore(String accountRef, String transactionId, String reason, String operator) {
        PaymentTransaction tx = findTransaction(accountRef, transactionId);
        long amount = tx == null ? 0L : tx.getAmount();
        PaymentMatch base = store.findMatch(accountRef, transactionId)
                .orElseGet(() -> store.saveMatchIfAbsent(
                        PaymentMatch.unmatched(accountRef, transactionId, amount, "人工忽略前挂起", null)));
        PaymentMatch saved = store.saveMatch(base.withDecision(MatchStatus.IGNORED,
                null, null, null, null, null, "MANUAL_IGNORE", null,
                Instant.now(), operator, reason));
        return saved;
    }

    public List<PaymentMatch> query(MatchStatus status) {
        return status == null ? store.findAllMatches() : store.findMatchesByStatus(status);
    }

    public List<PaymentMatch> unmatched() {
        return store.findMatchesByStatus(MatchStatus.UNMATCHED);
    }

    public Optional<LienRelease> releaseOf(long matchId) {
        return store.findReleaseByMatchId(matchId);
    }

    public Optional<CreditRestore> restoreOf(long matchId) {
        return store.findRestoreByMatchId(matchId);
    }

    private PaymentTransaction findTransaction(String accountRef, String transactionId) {
        return transactionRepository.findUnmatched().stream()
                .filter(tx -> accountRef.equals(tx.getAccountRef())
                        && transactionId.equals(tx.getTransactionId()))
                .findFirst()
                .orElse(null);
    }

    private MatchRunResult toResult(PaymentMatch match, boolean released) {
        return new MatchRunResult(match.accountRef(), match.transactionId(), match,
                match.matchRule(), match.matchScore() == null ? null : match.matchScore().toPlainString(),
                match.isReleasable(), released);
    }
}
