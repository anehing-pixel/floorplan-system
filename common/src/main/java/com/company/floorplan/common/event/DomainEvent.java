package com.company.floorplan.common.event;

import java.time.Instant;

/**
 * 领域事件的不可变载体（W0-1，契约已冻结，W1 五个 agent 照此编码）。
 *
 * <p><b>它代表什么</b>：{@code outbox_events} 表里<b>已经发生</b>的一件事后的一条不可变记录。
 * 事件由生产方在业务事务内写入（见 {@link com.company.floorplan.common.contract.OutboxPublisher}），
 * 由消费方按游标拉取。所以本对象只有字段、没有任何行为，也没有"重试一次"这类方法。</p>
 *
 * <p><b>为什么用 record</b>：事件一旦提交就不可修改；若做成可变 bean，消费者之间互相改 payload
 * 会造成"同一个事件在不同服务里含义不同"的隐性 bug。record 把这一点交给编译器保证。</p>
 *
 * <p><b>payload 为什么是 String 而不是 Map</b>：库里存的就是 JSON 文本，各消费方关心的字段不同
 * （loan 只要 vin/advanceId，release 还要 paymentRef）。由消费方自己按需要的形状反序列化，
 * 共享层就不必为任何一个消费方定义"全量 DTO"——那会变成第二个契约源，改一处要动 N 个服务。</p>
 *
 * @param eventId      {@code outbox_events.id}，全局单调递增，是消费游标与幂等键的主键
 * @param eventType    事件名，取值只用 {@link com.company.floorplan.common.contract.OutboxPublisher}
 *                     里的三个常量，不许自由命名（否则消费端的订阅集合对不上）
 * @param aggregateKey 聚合键 =「这一次发生」的主键（advanceId / paymentRef），发布去重的维度；
 *                     禁止用 vin（V21 注释里的旧措辞已作废），理由见 {@code OutboxPublisher#publish}
 * @param payload      JSON 文本，必含 {@code vin} 与 {@code traceId}（缺任一，发布端直接拒绝写入）
 * @param occurredAt   事件写入时间，取自库里的 {@code created_at}（TIMESTAMPTZ），业务判断以它为准
 */
public record DomainEvent(long eventId, String eventType, String aggregateKey,
                          String payload, Instant occurredAt) {

    /**
     * 从 payload 里取一个字符串字段，供消费者做前置校验。
     *
     * <p>刻意做成 static 之外的实例方法：消费者解析 payload 的动作是重复的，放到载体上避免每个
     * 消费者各写一套 JSON 读取。取不到返回 null，由调用方决定是跳过还是失败——
     * dispatcher 把"抛异常"定义为消费失败，所以调用方要的是"数据不合法"而不是"我不确定"时，
     * 应该抛异常，别静默 return。</p>
     */
    public String textField(String key) {
        return JsonPayloadReader.readString(payload, key);
    }

    /** 从 payload 里取一个整数字段（金额、ID），非整数返回 null。 */
    public Long longField(String key) {
        return JsonPayloadReader.readLong(payload, key);
    }

    /**
     * 从 payload 里取一个布尔字段。
     *
     * <p>取不到或形状不对返回 {@code null}，由调用方决定"缺字段"是失败还是跳过——
     * {@code PAYMENT_MATCHED} 的 {@code settled} 缺失时必须走"失败/不回补"，不能默认成 true。</p>
     */
    public Boolean booleanField(String key) {
        return JsonPayloadReader.readBoolean(payload, key);
    }
}
