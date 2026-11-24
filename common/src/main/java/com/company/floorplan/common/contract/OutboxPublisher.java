package com.company.floorplan.common.contract;

/**
 * 本地消息表（Transactional Outbox）发布契约（Wave 0 冻结）。
 *
 * <p>用于"放款后的状态同步"：借据生成、额度回补、DPD 更新、解押指令这类允许秒级延迟的
 * 事后动作。写入与业务在<b>同一个本地事务</b>内完成，再由定时任务投递，消费端靠唯一键幂等。</p>
 *
 * <p>为什么不用 Seata：AT 模式的全局锁 + 回滚在金融账务上成本和风险都太高。
 * 门禁类即时拦截走同步 Feign（fail-closed），事后状态同步走 outbox，两者分工。</p>
 */
public interface OutboxPublisher {

    /** 事件名常量，避免各服务自由命名导致消费端对不上 */
    String EVENT_LOAN_CREATED = "LOAN_CREATED";
    String EVENT_PAYMENT_MATCHED = "PAYMENT_MATCHED";
    String EVENT_COLLATERAL_CHANGED = "COLLATERAL_CHANGED";

    /**
     * 在调用方事务内写一个 outbox 事件。
     *
     * @param eventType 事件名，用上面的常量
     * @param aggregateKey 聚合键 =「这一次发生」的主键（LOAN_CREATED 用 advanceId、
     *                     PAYMENT_MATCHED 用 paymentRef），<b>禁止用 vin</b>。
     *                     {@code JdbcOutboxPublisher} 的抑制条件是同键已有 PENDING/SENT，
     *                     而 SENT 行永久保留——用 vin 会把同一台车的第二次合法事件永久吞掉。
     *                     （V21 迁移注释里"vin 或借据号"的旧措辞已作废，迁移冻结不可改。）
     * @param payload JSON 字符串，必须包含 vin + traceId 两个字段
     */
    void publish(String eventType, String aggregateKey, String payload);
}
