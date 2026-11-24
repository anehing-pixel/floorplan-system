package com.company.floorplan.bank.service;

import com.company.floorplan.bank.client.LoanSnapshot;
import com.company.floorplan.bank.model.CreditRestore;
import com.company.floorplan.bank.model.LienRelease;
import com.company.floorplan.bank.model.PaymentMatch;
import com.company.floorplan.bank.model.ReleaseStatus;
import com.company.floorplan.bank.model.RestoreStatus;
import com.company.floorplan.bank.store.PaymentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 匹配成功后的释放编排：解押留痕 + 额度回补请求。
 *
 * <h3>职责边界（2026-11-24 调整）</h3>
 * <p>本类只写 <b>bank 自己的 V17 台账</b>：
 * <ul>
 *   <li>解押：记录「担保实体权利已消灭」并生成抹消待办（REQUESTED）。
 *       日本实务中抹消登记需权利人与登记义务人共同申请，系统不能自动 DONE；</li>
 *   <li>额度回补：只记录「回补请求」（{@code credit_restores.status = PENDING}）。
 *       <b>不查询、不写入、不冒充已回补</b>——额度写入必须在 credit-service 自己的行锁事务里完成
 *       （{@code CreditLineClient} 契约只读，且 AGENTS §3.1 禁止跨服务直写）。</li>
 * </ul>
 *
 * <p>真正的回补由 {@code PAYMENT_MATCHED} 的消费方 credit-service 完成，
 * 它的 L2 幂等表是 V80 {@code credit_line_restorations}。本类不再维护
 * {@code availableBefore/availableAfter}（V17 列保留，写 NULL = 由真实写入方回填/不属于 bank 的职责）。</p>
 *
 * <p><b>为什么不能在本类里调 Feign 查可用额度然后写 RESTORED</b>：那是上一版的做法，
 * 查到的只是快照，写回 {@code availableAfter = before} 等于把"没回补"记成"已回补"。
 * 这种假绿比少一个功能更危险——它会让对账和红线判断都失去依据。</p>
 *
 * <h3>顺序与幂等（单测钉死）</h3>
 * <ol>
 *   <li>先解押：PENDING → RELEASED，并生成抹消待办（REQUESTED）；</li>
 *   <li>再登记回补请求：PENDING（由 credit-service 异步落实）。</li>
 * </ol>
 * 重复触发时按 matchId 判重，已 RELEASED/RESTORED 的直接跳过，绝不重复处理。
 */
public class ReleaseOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReleaseOrchestrator.class);

    private final PaymentStore store;

    public ReleaseOrchestrator(PaymentStore store) {
        this.store = store;
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
            } else if (priorRestore != null && priorRestore.status() == RestoreStatus.PENDING) {
                steps.add("CREDIT_RESTORE_ALREADY_REQUESTED");
            }
            return new ReleaseResult(match, priorRelease.get(), priorRestore, steps, true);
        }

        // ① 解押：担保实体权利消灭 + 抹消待办（V17 留痕；登记抹消本身是人工/外部动作）
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
        log.info("解押留痕（实体权利消灭，抹消待办 REQUESTED）：matchId={} vin={} amount={} operator={}",
                match.id(), match.vin(), match.matchedAmount(), operator);
        steps.add("LIEN_RELEASED");

        // ② 额度回补请求：只登记，真实写入由 credit-service 消费 PAYMENT_MATCHED 后完成
        steps.add("CREDIT_RESTORE_PENDING");
        CreditRestore restorePending = store.saveRestoreIfAbsent(
                CreditRestore.pending(match.id(), match.transactionId(),
                        loan.dealerId(), loan.creditLineId(), match.matchedAmount(), operator));
        if (restorePending.status() == RestoreStatus.RESTORED) {
            steps.add("SKIPPED_RESTORE_IDEMPOTENT");
        } else {
            steps.add("CREDIT_RESTORE_REQUESTED");
        }
        log.info("额度回补请求已登记（待 credit-service 消费 PAYMENT_MATCHED 落实）："
                        + "matchId={} dealerId={} creditLineId={} amount={} operator={}",
                match.id(), loan.dealerId(), loan.creditLineId(), match.matchedAmount(), operator);
        return new ReleaseResult(match, released, restorePending, steps, false);
    }
}
