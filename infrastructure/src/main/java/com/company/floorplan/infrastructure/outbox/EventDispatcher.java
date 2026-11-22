package com.company.floorplan.infrastructure.outbox;

import com.company.floorplan.common.event.DomainEvent;
import com.company.floorplan.common.event.EventConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 事件消费端框架（W0-3）：定时按游标拉 {@code outbox_events}，分发给本服务的 {@link EventConsumer}。
 *
 * <p><b>为什么用 DB 轮询而不是 MQ</b>：现在十个服务共用一个库，本地又不启中间件；
 * outbox 表已在库里（V21），轮询即可拿到"至少一次 + 断点续读"，零新增依赖。
 * 拆库后换成 HTTP 回调或 MQ 只改本类，消费者一行不动——理由详见
 * {@code docs/事件机制实现说明.md}。</p>
 *
 * <p><b>投递语义是 at-least-once，不是 exactly-once</b>：{@code consume()} 成功与游标推进之间
 * 存在宕机窗口，同一条事件会被重投。幂等由两层保证——
 * 框架层 {@code event_consumptions(event_id, consumer)} 主键挡住"同一事件重复处理"，
 * 业务层由消费者自己按业务唯一键去重（建借据按 advanceId、回补按 paymentRef）。</p>
 *
 * <p><b>失败语义（本类的核心约定，改动前请先读）</b>：</p>
 * <ol>
 *   <li>消费抛异常 → {@code attempts+1}、{@code last_error} 落库、状态 FAILED，
 *       <b>游标不前进</b>，下一轮重试同一条；</li>
 *   <li>{@code attempts} 达到 3 → 状态置 DEAD 并<b>推进游标</b>。
 *       理由：一条坏事件若永远阻塞，该消费者的所有后续事件都不会被处理，故障面从"一条数据"
 *       放大成"整条链路停摆"；</li>
 *   <li>已经是 DONE / DEAD 的事件直接跳过并推进，不重复调用 {@code consume}；</li>
 *   <li>DEAD 的人工重放不在本期范围，留接缝给 W3 收口。</li>
 * </ol>
 *
 * <p><b>多实例限制（如实说明）</b>：游标按 {@code consumer} 一行，两个进程若用同一个 consumer 名
 * 会互相覆盖游标，导致漏读或重复读。本地冒烟与当前部署都是单实例，故不在本期加分布式锁；
 * 多实例部署前必须改成 {@code FOR UPDATE SKIP LOCKED} 或按实例名分片，已记入实现说明的遗留项。</p>
 */
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    /** 单轮每个消费者最多取多少条：太小追不上积压，太大一次占住连接与内存。100 条足够覆盖本地场景。 */
    private static final int BATCH_LIMIT = 100;

    /** 重试上限。达到即置 DEAD 并跳过，避免一条坏事件卡死整个消费者。 */
    private static final int MAX_ATTEMPTS = 3;

    /** last_error 落库长度上限：异常栈可能很长，表里是 TEXT 但日志和排障都不需要全文，截断避免撑爆行。 */
    private static final int MAX_ERROR_LEN = 500;

    private final NamedParameterJdbcTemplate jdbc;

    /** 本服务装配到的消费者。为空说明这个服务不消费任何事件，调度任务直接空转返回。 */
    private final List<EventConsumer> consumers;

    /**
     * 用 {@link ObjectProvider} 而不是直接注入 {@code List<EventConsumer>}：
     * 九个服务里有消费者的只是少数，没有匹配 bean 时集合注入会走"缺依赖"路径，
     * ObjectProvider 的流式取值才是"可有可无"的正确表达。
     */
    public EventDispatcher(JdbcTemplate jdbc, ObjectProvider<EventConsumer> consumerProvider) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.consumers = consumerProvider.orderedStream().collect(Collectors.toList());
    }

    /**
     * 每秒一轮。用 {@code fixedDelay}（上一轮结束后再计时）而不是 fixedRate：
     * 一轮耗时超过间隔时，fixedRate 会让任务堆积并发执行，两个线程同时读写同一个游标就是数据错乱。
     */
    @Scheduled(fixedDelay = 1000)
    public void poll() {
        // 本服务没有消费者 → 一条 SQL 都不发。九个服务同时空转轮询会白白吃掉连接池，必须在入口挡掉。
        if (consumers.isEmpty()) {
            return;
        }
        for (EventConsumer consumer : consumers) {
            try {
                drain(consumer);
            } catch (Exception e) {
                // DB 抖动 / 迁移未执行（表还不存在）在这里兜住。
                // 调度线程抛异常会让 Spring 取消该任务的后续执行，等于"重启前永久停摆"，比空转更糟。
                log.warn("事件消费轮询异常 consumer={} err={}", consumer.name(), e.toString());
            }
        }
    }

    /**
     * 把一个消费者本轮能读到的事件全部按 id 升序处理，直到某条失败或批次结束。
     *
     * <p>不按 status 过滤：一条事件可能同时被多个服务的消费者订阅，先处理完的那个把它标成 SENT，
     * 不能因此让慢一点的消费者读不到。订阅范围只由 {@code event_type} 与游标决定。</p>
     */
    private void drain(EventConsumer consumer) {
        Set<String> types = consumer.eventTypes();
        if (types == null || types.isEmpty()) {
            return;
        }
        String name = consumer.name();
        long offset = readOrCreateOffset(name);

        Map<String, Object> params = new HashMap<>();
        params.put("types", new ArrayList<>(types));
        params.put("offset", offset);
        params.put("limit", BATCH_LIMIT);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, event_type, aggregate_key, payload, created_at FROM outbox_events "
                        + "WHERE id > :offset AND event_type IN (:types) ORDER BY id LIMIT :limit", params);

        for (Map<String, Object> row : rows) {
            DomainEvent event = toEvent(row);
            boolean resolved = handle(consumer, event);
            if (resolved) {
                // 游标推进与"已投递"标记成对出现：只有这条事件对本消费者已有定论（成功或死信）才动游标。
                writeOffset(name, event.eventId());
                markSent(event.eventId());
            } else {
                // 本轮到此为止：游标停在上一条，下轮从同一条重试，保证顺序不跳号。
                return;
            }
        }
    }

    /**
     * 处理单条事件。
     *
     * @return {@code true} 表示这条事件对本消费者已有定论（成功 / 死信 / 早已终结），可以推进游标；
     *         {@code false} 表示处理失败且还有重试机会，必须原地停住。
     */
    private boolean handle(EventConsumer consumer, DomainEvent event) {
        String name = consumer.name();
        Map<String, Object> key = new HashMap<>();
        key.put("id", event.eventId());
        key.put("consumer", name);

        // 1) 幂等登记：首次遇到该 (事件, 消费者) 时插一行，已存在则一行都不动。
        jdbc.update("INSERT INTO event_consumptions (event_id, consumer, status, attempts) "
                + "VALUES (:id, :consumer, 'PENDING', 0) ON CONFLICT (event_id, consumer) DO NOTHING", key);

        // 2) 读回当前定论。刚插入的一定是 PENDING/0，重投的可能是 DONE / FAILED / DEAD。
        List<Map<String, Object>> cur = jdbc.queryForList(
                "SELECT status, attempts FROM event_consumptions WHERE event_id = :id AND consumer = :consumer", key);
        String status = cur.isEmpty() ? "PENDING" : (String) cur.get(0).get("status");
        int attempts = cur.isEmpty() ? 0 : ((Number) cur.get(0).get("attempts")).intValue();

        if ("DONE".equals(status) || "DEAD".equals(status)) {
            // 重放窗口里撞到的已终结事件：跳过，不再调用 consume（这就是框架层幂等）。
            return true;
        }
        if (attempts >= MAX_ATTEMPTS) {
            // 上一轮已把 attempts 累加到上限但没标 DEAD（例如标完 attempts 进程被杀）：这里补标并放行游标。
            markDead(name, event, "重试次数已达上限，未再执行");
            return true;
        }

        try {
            // 3) 真正交给业务。异常一律视为失败，不在这里替消费者做判断。
            consumer.consume(event);
            jdbc.update("UPDATE event_consumptions SET status = 'DONE', attempts = :next, "
                    + "last_error = NULL, updated_at = now() WHERE event_id = :id AND consumer = :consumer",
                    withNext(key, attempts + 1, null));
            log.info("事件消费成功 consumer={} event_id={} event_type={} aggregate_key={}",
                    name, event.eventId(), event.eventType(), event.aggregateKey());
            return true;
        } catch (Exception e) {
            int next = attempts + 1;
            boolean dead = next >= MAX_ATTEMPTS;
            Map<String, Object> p = withNext(key, next, truncate(describe(e)));
            jdbc.update("UPDATE event_consumptions SET status = " + (dead ? "'DEAD'" : "'FAILED'")
                    + ", attempts = :next, last_error = :err, updated_at = now() "
                    + "WHERE event_id = :id AND consumer = :consumer", p);
            if (dead) {
                log.error("事件消费失败且已达重试上限，置 DEAD 并跳过 consumer={} event_id={} event_type={} "
                        + "aggregate_key={} err={}", name, event.eventId(), event.eventType(),
                        event.aggregateKey(), describe(e), e);
            } else {
                log.warn("事件消费失败，游标不前进，下轮重试 consumer={} event_id={} attempts={}/{} err={}",
                        name, event.eventId(), next, MAX_ATTEMPTS, describe(e));
            }
            // 变 DEAD 才推进游标；FAILED 留在原地重试同一条。
            return dead;
        }
    }

    /** 复制一份参数并加上 attempts / last_error，避免复用同一个 Map 造成跨语句污染。 */
    private Map<String, Object> withNext(Map<String, Object> key, int next, String err) {
        Map<String, Object> p = new HashMap<>(key);
        p.put("next", next);
        p.put("err", err);
        return p;
    }

    /** 死信标记：attempts 保持原值语义（记为已达上限的那一次），last_error 写明跳过原因。 */
    private void markDead(String consumer, DomainEvent event, String reason) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", event.eventId());
        p.put("consumer", consumer);
        p.put("err", reason);
        jdbc.update("UPDATE event_consumptions SET status = 'DEAD', last_error = :err, updated_at = now() "
                + "WHERE event_id = :id AND consumer = :consumer", p);
        log.error("事件已达重试上限，置 DEAD 并推进游标 consumer={} event_id={} event_type={} aggregate_key={} {}",
                consumer, event.eventId(), event.eventType(), event.aggregateKey(), reason);
    }

    /** 取游标；行不存在则以 0 建档（首次启动从最早的事件读起，不丢历史）。 */
    private long readOrCreateOffset(String consumer) {
        Map<String, Object> p = new HashMap<>();
        p.put("consumer", consumer);
        jdbc.update("INSERT INTO consumer_offsets (consumer, last_event_id) VALUES (:consumer, 0) "
                + "ON CONFLICT (consumer) DO NOTHING", p);
        Long v = jdbc.queryForObject(
                "SELECT last_event_id FROM consumer_offsets WHERE consumer = :consumer", p, Long.class);
        return v == null ? 0L : v;
    }

    /**
     * 推进游标。带 {@code last_event_id < :id} 条件，保证游标只会单调前进——
     * 即使将来出现并发轮询或多线程分发，也不会把游标倒回去造成重复处理历史事件。
     */
    private void writeOffset(String consumer, long eventId) {
        Map<String, Object> p = new HashMap<>();
        p.put("consumer", consumer);
        p.put("id", eventId);
        jdbc.update("UPDATE consumer_offsets SET last_event_id = :id, updated_at = now() "
                + "WHERE consumer = :consumer AND last_event_id < :id", p);
    }

    /**
     * 把事件标为已投递。
     *
     * <p>带 {@code status = 'PENDING'} 条件：一个事件可能被多个服务的消费者订阅，先处理完的那个改状态，
     * 后面的不能把它改回去。这里的状态只表示"已被投递过"，不影响其他消费者的可读性（见
     * {@link #drain} 不按 status 过滤）。</p>
     *
     * <p><b>必须知道的副作用</b>：发布端去重的判据是"同 (event_type, aggregate_key) 存在
     * PENDING <b>或 SENT</b>"（V21 原约定），而 SENT 行永久保留，所以同一聚合键实际上只能发布一条事件。
     * 因此 aggregateKey 必须按"一次发生"取值（LOAN_CREATED 用 advanceId、PAYMENT_MATCHED 用 paymentRef），
     * <b>不能用 vin</b>——用 vin 会让同一台车的第二次合法事件被永久抑制。契约文档已把这条写成硬规则。</p>
     */
    private void markSent(long eventId) {
        jdbc.update("UPDATE outbox_events SET status = 'SENT', sent_at = now() "
                + "WHERE id = :id AND status = 'PENDING'", Map.of("id", eventId));
    }

    /** 结果集一行 → 事件载体。字段名与 V21 表结构一致，改名会让这里静默拿到 null。 */
    private DomainEvent toEvent(Map<String, Object> row) {
        long id = ((Number) row.get("id")).longValue();
        String type = (String) row.get("event_type");
        String key = (String) row.get("aggregate_key");
        String payload = (String) row.get("payload");
        Object createdAt = row.get("created_at");
        Instant occurredAt = createdAt instanceof Timestamp ts ? ts.toInstant() : Instant.now();
        return new DomainEvent(id, type, key, payload, occurredAt);
    }

    /** 异常的可读描述：message 常为 null（如 NPE），退化到类名，保证 last_error 一定有内容。 */
    private String describe(Exception e) {
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? e.getClass().getName() : e.getClass().getSimpleName() + ": " + msg;
    }

    private String truncate(String s) {
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN) + "…";
    }
}
