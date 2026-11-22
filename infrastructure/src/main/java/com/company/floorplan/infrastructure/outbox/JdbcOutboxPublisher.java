package com.company.floorplan.infrastructure.outbox;

import com.company.floorplan.common.contract.OutboxPublisher;
import com.company.floorplan.common.event.JsonPayloadReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link OutboxPublisher} 的 JDBC 实现（W0-2）：把事件写进本地消息表 {@code outbox_events}。
 *
 * <p><b>为什么必须有实现类</b>：V21 把表和契约建好了，但全仓 0 个实现、0 个 {@code @Scheduled}，
 * 所以"放款→借据→回款→解押→回补"整条链此前只能靠人工搬数据。本类与
 * {@link EventDispatcher} 一起把这段机制补上，之后各服务只写消费者，不再各造一套假触发。</p>
 *
 * <p><b>事务边界（关键，别改）</b>：本类<b>不加</b> {@code @Transactional}。
 * "业务写 + 事件写"必须是同一个本地事务，否则会出现两种都不对的结局——
 * 事件先提交、业务回滚 → 凭空多一条永久消费不掉的幽灵事件；
 * 业务先提交、事件回滚 → 状态永久不推进（借据不生成、额度不回补）。
 * 因此事件写入跟随调用方事务，由 {@code application} 层的一个用例一个事务边界统一控制。
 * 在没有外层事务的调用点（如定时作业）单独调用本类，事件会即时提交，属于可接受的下界。</p>
 *
 * <p><b>去重为什么不用唯一索引</b>：V21 只有普通索引 {@code idx_outbox_agg}，而已落库迁移不可修改
 * （Flyway checksum）。这里用 {@code INSERT ... SELECT ... WHERE NOT EXISTS} 在应用侧实现同一语义。</p>
 *
 * <p><b>已知并发窗口（如实记录，不掩盖）</b>：{@code NOT EXISTS} 是"读—判—写"，两个并发事务
 * 都没看到对方未提交的插入时，可能各写一条，出现同 {@code (event_type, aggregate_key)} 的重复行。
 * 挡住它的不是这里，而是消费端的 {@code event_consumptions(event_id, consumer)} 幂等主键 +
 * 消费者自身的业务唯一键。这也是本项目"幂等必须有两层"的既有口径
 * （参见 {@code release} 的 CAS 更新、{@code disposal} 的配对解冻）。</p>
 */
public class JdbcOutboxPublisher implements OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(JdbcOutboxPublisher.class);

    /**
     * 带去重的插入：同一 (event_type, aggregate_key) 已有 PENDING/SENT 记录时，一条都不插。
     *
     * <p>SELECT 的字段顺序与 outbox_events 的列顺序无关，显式写列名，避免 V21 之后加列时错位。</p>
     */
    private static final String INSERT_IF_ABSENT =
            "INSERT INTO outbox_events (event_type, aggregate_key, payload, status) "
                    + "SELECT :eventType, :aggregateKey, :payload, 'PENDING' "
                    + "WHERE NOT EXISTS (SELECT 1 FROM outbox_events "
                    + "                  WHERE event_type = :eventType AND aggregate_key = :aggregateKey "
                    + "                    AND status IN ('PENDING', 'SENT'))";

    /** 命名参数版本；构造时包一层，不额外要求 Boot 提供 NamedParameterJdbcTemplate bean（自动配置只给 JdbcTemplate）。 */
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOutboxPublisher(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /**
     * 在调用方事务内写一个 outbox 事件。
     *
     * <p>校验顺序刻意放在 SQL 之前：非法事件<b>绝不落库</b>。理由很实际——一条 payload 不合法的
     * 事件写进去之后，消费端每次拉到都失败，重试 3 次变 DEAD，之后只能人工重放；
     * 而在发布点抛异常，栈就在真正的责任代码上。</p>
     *
     * @throws IllegalArgumentException eventType / aggregateKey 空白，或 payload 缺 vin / traceId
     */
    @Override
    public void publish(String eventType, String aggregateKey, String payload) {
        // 事件名空 → 消费端订阅集合永远对不上，直接判生产方 bug
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("outbox 事件的 eventType 不能为空");
        }
        // 聚合键空 → 去重与幂等锚点都失效，等于放弃这条链路的可追溯性
        if (aggregateKey == null || aggregateKey.isBlank()) {
            throw new IllegalArgumentException("outbox 事件的 aggregateKey 不能为空（消费端幂等锚点）");
        }
        // V21 + 契约硬要求：payload 必含 vin 与 traceId，缺失不静默写空
        JsonPayloadReader.requireVinAndTraceId(payload);

        Map<String, Object> params = new HashMap<>();
        params.put("eventType", eventType);
        params.put("aggregateKey", aggregateKey);
        params.put("payload", payload);

        int inserted = jdbc.update(INSERT_IF_ABSENT, params);
        if (inserted == 0) {
            // 同聚合已有未投递/已投递事件：这是去重生效，不是失败。打 info 便于冒烟核对"确实没多插一条"。
            log.info("outbox 去重命中，跳过重复写入 event_type={} aggregate_key={}", eventType, aggregateKey);
        } else {
            log.debug("outbox 事件已写入 event_type={} aggregate_key={}", eventType, aggregateKey);
        }
    }
}
