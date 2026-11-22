package com.company.floorplan.common.event;

import java.util.Set;

/**
 * 事件消费方契约（W0-1，契约已冻结）。
 *
 * <p><b>实现方是谁</b>：每个服务在自己的包里实现本接口（例：
 * {@code loan-service} 的 {@code LoanCreatedConsumer}），由 {@code EventDispatcher} 定时驱动。
 * 服务没有实现类时 dispatcher 直接跳过，不发 SQL、不占连接。</p>
 *
 * <p><b>三条必须守住的约定</b>（违反即"写了代码没接通"，本项目踩过）：</p>
 * <ol>
 *   <li><b>必须幂等</b>。dispatcher 用 {@code event_consumptions(event_id, consumer)} 挡重复投递，
 *       但"消费成功、写游标前进程被杀"这一窗口仍会重放同一条事件。所以 {@link #consume} 自己
 *       必须能扛重复调用（按业务唯一键去重，别指望只被调一次）。</li>
 *   <li><b>不许吞异常</b>。抛异常＝消费失败，dispatcher 记 {@code attempts+1} 且游标不前进；
 *       若 catch 住返回正常，坏数据会被标成 DONE 永久丢失。</li>
 *   <li><b>不许做服务间调用</b>。消费跑在调度线程里，没有 HTTP 请求上下文，拿不到调用方 JWT；
 *       需要跨服务取数请在本服务表里落一份，或由后续波次显式设计带凭证的通路。</li>
 * </ol>
 */
public interface EventConsumer {

    /**
     * 消费方标识，写进 {@code consumer_offsets.consumer} 与 {@code event_consumptions.consumer}，
     * 也是幂等键的一半。
     *
     * <p>约定用服务名（如 {@code "loan-service"}），<b>不要用类名</b>：一个服务多个消费者共用服务名
     * 会让游标互相覆盖。多实例部署时该值必须实例唯一，否则两个进程抢同一游标——W0 只在单实例下验证，
     * 该限制已写进《事件机制实现说明》。</p>
     */
    String name();

    /**
     * 订阅的事件类型集合。dispatcher 据此拼 {@code WHERE event_type IN (...)},
     * 不在集合里的事件根本不会被拉给本消费者，也不会写消费记录。
     *
     * <p>返回空集合＝这个消费者不订阅任何东西，dispatcher 会跳过它。</p>
     */
    Set<String> eventTypes();

    /**
     * 处理一条事件。正常返回＝消费成功（dispatcher 置 DONE 并推进游标）；
     * 抛任何异常＝消费失败（dispatcher 记 attempts/last_error，游标原地不动，下轮重试）。
     *
     * <p>重试上限 3 次，超限置 DEAD 并推进游标——一条坏事件不能卡死整个消费者。
     * DEAD 事件的重放属 W3 收口范围，W0 只留接缝。</p>
     */
    void consume(DomainEvent event);
}
