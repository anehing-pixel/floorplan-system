package com.company.floorplan.bank.service;

import com.company.floorplan.bank.client.LoanSnapshot;
import com.company.floorplan.bank.model.CreditRestore;
import com.company.floorplan.bank.model.LienRelease;
import com.company.floorplan.bank.model.PaymentMatch;
import com.company.floorplan.bank.model.ReleaseStatus;
import com.company.floorplan.bank.model.RestoreStatus;
import com.company.floorplan.bank.store.PaymentStore;
import com.company.floorplan.common.contract.CreditLineClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 解押 + 额度回补编排：匹配成功后的释放层。
 *
 * <h3>为什么是"编排"而不是一坨 service 方法</h3>
 * <p>设计稿 §4.2 的完整链路有 9 步，跨 VIN 台账、融资台账、担保台账、授信侧。
 * 本 agent 的可写边界只有 bank-service + V17，因此把跨服务动作做成两个出口：
 * <ul>
 *   <li>解押：自有端口 + 日志存根（common.CollateralClient 只提供只读登记查询，
 *       没有解押写入契约，不能越过冻结契约自己发明）；</li>
 *   <li>回补：{@link CreditLineClient} 只读查询快照 + 写入交给授信侧行锁事务（契约既定边界）。</li>
 * </ul>
 * 真实接线 TODO 见 HANDOFF.md；在接线前所有释放只留痕、不假装成功记账。</p>
 *
 * <h3>顺序与幂等（单测钉死）</h3>
 * <ol>
 *   <li>先解押：PENDING → RELEASED，并生成抹消待办（REQUESTED，需双方共同申请）；</li>
 *   <li>再查授信可用额度（只读）；</li>
 *   <li>最后回补：PENDING → RESTORED，记录回补前后快照。</li>
 * </ol>
 * 重复触发时按 matchId 判重，已 RELEASED/RESTORED 的直接跳过，绝不重复扣减额度。
 */
public class ReleaseOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReleaseOrchestrator.class);

    private final PaymentStore store;
    private final CreditLineClient creditLineClient;

    public ReleaseOrchestrator(PaymentStore store, CreditLineClient creditLineClient) {
        this.store = store;
        this.creditLineClient = creditLineClient;
    }

    /** 编排一次释放；返回执行步骤与最终记录，供查询端点与审计使用。 */
    public ReleaseResult orchestrate(PaymentMatch match, LoanSnapshot loan, String operator) {
        List<String> steps = new ArrayList<>();

        if (!match.isReleasable()) {
            steps.add("REJECTED_NOT_MATCHED");
            return new ReleaseResult(match, null, null, steps, false);
        }
        if (match.id() == null) {
            steps.add("REJECTED_NO_MATCH_ID");
            return new ReleaseResult(match, null, null, steps, false);
        }

        Optional<LienRelease> priorRelease = store.findReleaseByMatchId(match.id());
        if (priorRelease.isPresent() && priorRelease.get().status() == ReleaseStatus.RELEASED) {
            steps.add("SKIPPED_RELEASE_IDEMPOTENT");
            CreditRestore priorRestore = store.findRestoreByMatchId(match.id()).orElse(null);
            if (priorRestore != null && priorRestore.status() == RestoreStatus.RESTORED) {
                steps.add("SKIPPED_RESTORE_IDEMPOTENT");
            }
            return new ReleaseResult(match, priorRelease.get(), priorRestore, steps, true);
        }

        // ① 解押：担保实体权利消灭 + 抹消待办（存根只记日志，真实动作 TODO）
        steps.add("LIEN_RELEASE_PENDING");
        LienRelease pending = store.saveReleaseIfAbsent(
                LienRelease.pending(match.id(), match.transactionId(), match.vin(),
                        loan.id(), match.matchedAmount(), operator));
        if (pending.status() == ReleaseStatus.RELEASED) {
            steps.add("SKIPPED_RELEASE_CONCURRENT");
            return new ReleaseResult(match, pending,
                    store.findRestoreByMatchId(match.id()).orElse(null), steps, true);
        }
        Instant releasedAt = Instant.now();
        LienRelease released = store.saveRelease(pending.markedReleased(releasedAt));
        log.info("解押完成（实体权利消灭，抹消待办 REQUESTED）：matchId={} vin={} amount={} operator={}",
                match.id(), match.vin(), match.matchedAmount(), operator);
        steps.add("LIEN_RELEASED");

        // ② 额度回补：先做只读快照查询（CreditLineClient 契约边界），再记账
        steps.add("CREDIT_RESTORE_PENDING");
        CreditRestore restorePending = store.saveRestoreIfAbsent(
                CreditRestore.pending(match.id(), match.transactionId(),
                        loan.dealerId(), null, match.matchedAmount(), operator));
        if (restorePending.status() == RestoreStatus.RESTORED) {
            steps.add("SKIPPED_RESTORE_IDEMPOTENT");
            return new ReleaseResult(match, released, restorePending, steps, true);
        }

        try {
            steps.add("CREDIT_AVAILABLE_QUERY");
            BigDecimal available = creditLineClient.getAvailable(loan.dealerId());
            Long before = available == null ? null : available.longValue();
            CreditRestore withBefore = store.saveRestore(restorePending.withBefore(before));

            // TODO(Wave2)：真实写入由授信侧在自己的行锁事务内完成，本服务只留凭证。
            // 存根阶段 availableAfter = before，表示"尚未真实回补"，不能冒充已到账。
            CreditRestore restored = store.saveRestore(withBefore.markedRestored(Instant.now(), before));
            steps.add("CREDIT_RESTORED");
            log.info("额度回补凭证落库：matchId={} dealerId={} amount={} before={} operator={}",
                    match.id(), loan.dealerId(), match.matchedAmount(), before, operator);
            return new ReleaseResult(match, released, restored, steps, false);
        } catch (RuntimeException e) {
            // 设计稿 §4.4：不能静默失败。留一条 FAILED 凭证供强制工单重跑。
            CreditRestore failed = store.saveRestore(
                    store.findRestoreByMatchId(match.id()).orElse(restorePending).markedFailed(e.getMessage()));
            steps.add("CREDIT_RESTORE_FAILED");
            log.error("额度回补失败，已留 FAILED 凭证待重试：matchId={} dealerId={}",
                    match.id(), loan.dealerId(), e);
            return new ReleaseResult(match, released, failed, steps, false);
        }
    }
}
