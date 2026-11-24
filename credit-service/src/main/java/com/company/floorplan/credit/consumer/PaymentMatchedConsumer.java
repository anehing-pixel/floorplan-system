package com.company.floorplan.credit.consumer;

import com.company.floorplan.common.event.DomainEvent;
import com.company.floorplan.common.event.EventConsumer;
import com.company.floorplan.domain.model.CreditLine;
import com.company.floorplan.domain.repository.CreditLineRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * {@code PAYMENT_MATCHED} 的 credit-service 消费者：把「回补额」真实写回额度线。
 *
 * <p><b>它解决什么</b>：bank 侧 {@code ReleaseOrchestrator} 只登记回补请求（PENDING），
 * 不写 {@code credit_lines}——跨服务直写会在物理拆库当天失效。额度是本系统并发风险最高的资源，
 * 必须在自己的服务里用 {@code SELECT ... FOR UPDATE} 行锁完成。</p>
 *
 * <p><b>三层幂等，各管一件事</b>：
 * <ol>
 *   <li>框架层 {@code event_consumptions(event_id, consumer)}：同一事件行重投只跑一次；</li>
 *   <li>业务层 {@code credit_line_restorations.payment_ref} 唯一约束：同一笔回款被重新发布成
 *       新事件（新 event_id）时，由 {@link JdbcCreditRestorationRepository#claim} 挡住；</li>
 *   <li>额度行锁：把同一额度线上的并发回补串行化，避免读-改-写丢更新。</li>
 * </ol>
 *
 * <p><b>不按比例推算</b>：事件没带 {@code restoreAmount} 时一律不回补（ADR Q24：部分还款不回补额度）。
 * {@code settled=true} 但没带金额，只记 WARN 并跳过——这是生产方的口径缺口，不能由消费方替它猜。</p>
 *
 * <p><b>失败语义</b>：数据不合法（缺 paymentRef / creditLineId、金额非正、额度不存在）一律抛异常，
 * 由 EventDispatcher 记 FAILED 并在下一轮重试，3 次后 DEAD。静默 return 会把坏事件记成 DONE，
 * 额度却永远没回补——这正是本仓库反复踩过的假绿。</p>
 *
 * <p><b>命名</b>：{@link #name()} 返回 {@code "credit-service"}（服务名，不是类名）。
 * 该值同时是 {@code consumer_offsets} 与 {@code event_consumptions} 的键，上线后不能改。</p>
 */
@Component
public class PaymentMatchedConsumer implements EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentMatchedConsumer.class);

    /** 与 {@code OutboxPublisher.EVENT_PAYMENT_MATCHED} 同一取值；写成字面量避免反向依赖契约常量类。 */
    private static final String EVENT_TYPE = "PAYMENT_MATCHED";

    private final CreditLineRepository creditLineRepository;
    private final JdbcCreditRestorationRepository restorationRepository;
    private final SupportRepository supportRepository;

    public PaymentMatchedConsumer(CreditLineRepository creditLineRepository,
                                  JdbcCreditRestorationRepository restorationRepository,
                                  SupportRepository supportRepository) {
        this.creditLineRepository = creditLineRepository;
        this.restorationRepository = restorationRepository;
        this.supportRepository = supportRepository;
    }

    @Override
    public String name() {
        return "credit-service";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(EVENT_TYPE);
    }

    @Override
    @Transactional
    public void consume(DomainEvent event) {
        String vin = event.textField("vin");
        String traceId = event.textField("traceId");
        String paymentRef = event.textField("paymentRef");

        if (vin == null || vin.isBlank()) {
            throw new IllegalArgumentException("PAYMENT_MATCHED 缺 vin：event_id=" + event.eventId());
        }
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("PAYMENT_MATCHED 缺 traceId：event_id=" + event.eventId()
                    + " vin=" + vin);
        }
        if (paymentRef == null || paymentRef.isBlank()) {
            // paymentRef 是回补的业务幂等键；缺了它，任何重复投递都会重复回补
            throw new IllegalArgumentException("PAYMENT_MATCHED 缺 paymentRef，无法做业务幂等：event_id="
                    + event.eventId() + " vin=" + vin);
        }

        Boolean settled = event.booleanField("settled");
        Long restoreAmount = event.longField("restoreAmount");

        if (!Boolean.TRUE.equals(settled) && restoreAmount == null) {
            // 未结清且没有显式回补额：这不是错误，是"本次不回补"（ADR Q24）
            log.info("PAYMENT_MATCHED 不触发额度回补（未结清且无 restoreAmount）：event_id={} paymentRef={} "
                    + "vin={} settled={} traceId={}", event.eventId(), paymentRef, vin, settled, traceId);
            return;
        }
        if (restoreAmount == null) {
            // 生产方说结清了，却没说回补多少；不替它按比例推算，但不把事件判死
            log.warn("PAYMENT_MATCHED settled=true 但缺 restoreAmount，消费方不推算回补额：event_id={} "
                    + "paymentRef={} vin={} traceId={}", event.eventId(), paymentRef, vin, traceId);
            return;
        }
        if (restoreAmount <= 0) {
            throw new IllegalArgumentException("PAYMENT_MATCHED 的 restoreAmount 必须为正整数日元："
                    + "event_id=" + event.eventId() + " paymentRef=" + paymentRef
                    + " restoreAmount=" + restoreAmount);
        }

        Long creditLineId = event.longField("creditLineId");
        if (creditLineId == null) {
            // 只有 dealerId 无法在多条额度线之间定位；回补到错的额度线比不回补更危险
            throw new IllegalArgumentException("PAYMENT_MATCHED 缺 creditLineId，无法定位回补目标：event_id="
                    + event.eventId() + " paymentRef=" + paymentRef + " vin=" + vin);
        }
        Long dealerId = event.longField("dealerId");

        // L2：先认领 paymentRef。冲突说明同一笔回款已经回补过（可能是另一条 outbox 行）。
        if (!restorationRepository.claim(paymentRef, event.eventId(), traceId,
                creditLineId, dealerId, restoreAmount)) {
            log.info("PAYMENT_MATCHED 业务幂等命中，跳过重复回补：event_id={} paymentRef={} vin={} traceId={}",
                    event.eventId(), paymentRef, vin, traceId);
            return;
        }

        CreditLine line = creditLineRepository.findByIdForUpdate(creditLineId)
                .orElseThrow(() -> new IllegalStateException(
                        "PAYMENT_MATCHED 回补目标额度线不存在：creditLineId=" + creditLineId
                                + " paymentRef=" + paymentRef));
        if (dealerId != null && line.getDealerId() != null && !dealerId.equals(line.getDealerId())) {
            // 额度线与经销商不匹配 = 事件写错了；宁可失败重试/进死信，也不能扣错归属
            throw new IllegalStateException("PAYMENT_MATCHED 的 dealerId 与额度线归属不一致：paymentRef="
                    + paymentRef + " dealerId=" + dealerId + " creditLine.dealerId=" + line.getDealerId());
        }

        long usedBefore = line.getUsedAmount();
        long availableBefore = line.available();
        line.release(restoreAmount);
        CreditLine saved = creditLineRepository.save(line);
        long applied = Math.max(0L, usedBefore - saved.getUsedAmount());
        long availableAfter = saved.available();
        if (applied < restoreAmount) {
            log.warn("PAYMENT_MATCHED 实际释放额小于事件回补额，差额应查是否已被其他路径释放：paymentRef={} "
                            + "restoreAmount={} applied={} creditLineId={}",
                    paymentRef, restoreAmount, applied, creditLineId);
        }
        restorationRepository.complete(paymentRef, availableBefore, availableAfter, applied,
                applied < restoreAmount
                        ? "used_amount 已被其他路径释放，差额=" + (restoreAmount - applied)
                        : null);

        // 关键动作写审计；操作人是系统（消费方），traceId 从事件透传，跨服务能串回银行流水
        supportRepository.writeAudit("system", "CREDIT_LINE_RESTORED", "CREDIT_LINE",
                String.valueOf(creditLineId),
                "used=" + usedBefore + ",available=" + availableBefore,
                "used=" + saved.getUsedAmount() + ",available=" + availableAfter
                        + ",applied=" + applied + ",paymentRef=" + paymentRef,
                traceId);

        log.info("PAYMENT_MATCHED 额度回补完成 event_id={} paymentRef={} creditLineId={} dealerId={} "
                        + "restoreAmount={} applied={} availableBefore={} availableAfter={} traceId={}",
                event.eventId(), paymentRef, creditLineId, saved.getDealerId(), restoreAmount, applied,
                availableBefore, availableAfter, traceId);
    }
}
