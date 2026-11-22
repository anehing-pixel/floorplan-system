package com.company.floorplan.application.event;

/**
 * {@code LOAN_CREATED} 的<b>发布端口</b>（application 层声明，web 层实现）。
 *
 * <p><b>这是"端口—适配器"的一条缝，不是可有可同的一层封装</b>：
 * 应用服务只说"钱到账了，把这件事发出去"，至于载荷用 Jackson 还是手拼、
 * 落到 {@code outbox_events} 还是别的媒介，都由 {@code web} 里的实现决定。
 * 换媒介时业务代码零改动，反过来业务代码也不会把序列化细节泄漏到别处。</p>
 *
 * <p><b>调用约定（三条，破一条就失去本地消息表的意义）</b>：</p>
 * <ol>
 *   <li><b>必须在调用方的事务内调用</b>。实现方（{@code JdbcOutboxPublisher}）刻意不加
 *       {@code @Transactional}，事件写入跟随调用方事务：业务提交则事件必达，业务回滚则事件消失。
 *       拆开就会出现"幽灵事件"（事件提交、业务回滚）或"状态永不推进"（业务提交、事件丢失）。</li>
 *   <li><b>失败必须让异常穿过去，不许 catch</b>。载荷不合法（缺 vin/traceId）说明生产方写错了，
 *       同事务回滚 = 这笔放款确认失败，调用方会收到 500 并可以重放回调。
 *       若在发布点吞掉异常，就变成"钱到账了、账上却永远没有这笔债"——正是本项目反复踩的假绿。</li>
 *   <li><b>一笔业务事实只调一次</b>。实现端按 {@code (event_type, aggregate_key)} 做
 *       {@code WHERE NOT EXISTS} 去重，重复调用不会插第二条，但也不该依赖它来兜业务幂等。</li>
 * </ol>
 *
 * @see LoanCreatedCommand 载荷字段口径（冻结源：{@code docs/接口契约-事件与Feign.md §2.1}）
 */
public interface LoanCreatedPublisher {

    /**
     * 在调用方事务内发布一条"借据应建立"事件。
     *
     * @param cmd 载荷，{@code vin} 与 {@code traceId} 不可为空白（由实现端校验并抛异常）
     * @throws IllegalArgumentException 载荷不合法（缺 vin / traceId 或 traceId 是字面量 {@code "null"}）
     */
    void publish(LoanCreatedCommand cmd);
}
