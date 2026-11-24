package com.company.floorplan.bank.event;

import com.company.floorplan.bank.model.PaymentMatch;
import com.company.floorplan.common.contract.OutboxPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code PAYMENT_MATCHED} 的 outbox 发布器（bank-service，契约 §2.2）。
 *
 * <p><b>触发时机</b>：匹配结论（{@code AUTO_MATCHED} / {@code MANUAL_MATCHED}）落库的同一个本地事务。
 * 未匹配 / 候选待确认 / 人工绑定前的挂起 / 人工忽略：<b>一律不发</b>（契约 §2.2 末行）。</p>
 *
 * <p><b>aggregate_key = paymentRef = transaction_id</b>。发布端去重条件是
 * 「同 (event_type, aggregate_key) 已有 PENDING/SENT 就不插」，而 SENT 行永久保留；
 * 用 vin 会把同一台车的第二笔合法回款永久吞掉。契约 §3.3 把这条写成硬规则。</p>
 *
 * <p><b>settled / restoreAmount 的口径</b>：
 * <ul>
 *   <li>{@code settled = 本次匹配金额 >= 借据剩余应回本金}（借据查询契约的 expectedAmount）；</li>
 *   <li>{@code restoreAmount} 只在 settled=true 且能拿到借据本金时出现，值 = 放款时占用的本金
 *       （D09 §8.3：回补额 = advance_amount，不含利息）；</li>
 *   <li>部分回款（settled=false）<b>不带 restoreAmount</b>：消费方看到没有 restoreAmount 就不回补，
 *       绝不按比例推算（ADR Q24）。</li>
 * </ul>
 * 借据本金缺失时不冒充一个数字：settled 可以是真的，但没有 restoreAmount，回补方会跳过并留下可查记录。</p>
 *
 * <p><b>不 catch 发布异常</b>：载荷不合法 = 生产方 bug，让事务回滚比"回款匹配表说成功了、事件却没有"正确。
 * 这跟 web 的 {@code OutboxLoanCreatedPublisher} 是同一套口径。</p>
 */
public class PaymentMatchedPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentMatchedPublisher.class);

    private final OutboxPublisher outbox;
    private final ObjectMapper mapper;

    public PaymentMatchedPublisher(OutboxPublisher outbox, ObjectMapper mapper) {
        this.outbox = outbox;
        this.mapper = mapper;
    }

    /**
     * 发布一次匹配成功事件。
     *
     * @param match         已落库的匹配结论（必须有 vin / transactionId / matchedAmount）
     * @param settled       是否结清（决定消费方是否回补）
     * @param restoreAmount 回补额；仅在 settled 且本金可得时非 null
     * @param traceId       全链路追踪号（必填）
     */
    public void publish(PaymentMatch match, boolean settled, Long restoreAmount, String traceId) {
        if (match == null) {
            throw new IllegalArgumentException("PAYMENT_MATCHED 缺少匹配结论");
        }
        if (match.vin() == null || match.vin().isBlank()) {
            throw new IllegalArgumentException("PAYMENT_MATCHED 缺 vin，消费方无法定位车辆：paymentRef="
                    + match.transactionId());
        }
        if (match.transactionId() == null || match.transactionId().isBlank()) {
            throw new IllegalArgumentException("PAYMENT_MATCHED 缺 paymentRef（transaction_id），无法做业务幂等：vin="
                    + match.vin());
        }
        if (traceId == null || traceId.isBlank() || "null".equals(traceId)) {
            // String.valueOf(null) 会得到非空白的 "null"，不先挡下来会伪装成成功（AGENTS §12 假绿）
            throw new IllegalArgumentException("PAYMENT_MATCHED 缺 traceId，跨服务排障断链：paymentRef="
                    + match.transactionId());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("vin", match.vin());
        payload.put("traceId", traceId);
        payload.put("paymentRef", match.transactionId());
        payload.put("amount", match.matchedAmount());
        payload.put("settled", settled);
        payload.put("loanId", match.loanId());
        payload.put("advanceId", match.advanceId());
        payload.put("dealerId", match.dealerId());
        // 契约 §2.2 之外的两个 additive 字段：
        //   creditLineId —— 消费方必须按额度线行锁回补，仅凭 dealerId 在多条额度线时无法定位；
        //   restoreAmount —— 契约 §2.2 明确列为可选，给了就以它为准。
        payload.put("creditLineId", match.creditLineId());
        payload.put("matchedAt", match.matchedAt() == null ? null : match.matchedAt().toString());
        if (restoreAmount != null) {
            payload.put("restoreAmount", restoreAmount);
        }

        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("PAYMENT_MATCHED 载荷序列化失败：paymentRef="
                    + match.transactionId(), e);
        }

        outbox.publish(OutboxPublisher.EVENT_PAYMENT_MATCHED, match.transactionId(), json);
        log.info("PAYMENT_MATCHED 已发布 paymentRef={} vin={} settled={} restoreAmount={} traceId={}",
                match.transactionId(), match.vin(), settled, restoreAmount, traceId);
    }
}
