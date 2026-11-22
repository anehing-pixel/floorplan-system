package com.company.floorplan.web.integration;

import com.company.floorplan.application.event.LoanCreatedCommand;
import com.company.floorplan.application.event.LoanCreatedPublisher;
import com.company.floorplan.common.contract.OutboxPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link LoanCreatedPublisher} 的 outbox 适配器（W2-CORE）：把放款确认这件事写进本地消息表。
 *
 * <p><b>为什么适配器必须在 web 层</b>：{@code application} 不依赖 {@code common}
 * （依赖方向是反的：{@code common → application}），所以
 * {@code AdvanceApplicationService} 里 {@code import ...common.contract.OutboxPublisher}
 * 根本编译不过。{@code infrastructure} 只依赖 domain + common，"端口写 application、
 * 适配器放 infrastructure"同样走不通。只有 web 同时看得见 application、common、infrastructure，
 * 于是端口留在应用层、这个实现类落在 web。</p>
 *
 * <p><b>装配方式：只 {@code @Import}，刻意不打 {@code @Component}</b>。
 * web 的 {@code @ComponentScan} 覆盖整个 {@code com.company.floorplan}，
 * 同一个类若"扫描 + {@code @Import}"两条路都注册，会产出两份同类型 bean，
 * 按类型注入直接 {@code NoUniqueBeanDefinitionException}。
 * 这与 {@code FloorplanApplication} 上 {@code JdbcOutboxPublisher}/{@code EventDispatcher}
 * 的处理保持同一套口径。</p>
 *
 * <p><b>三条硬规矩（写死在这里，别让下一个 agent 改回去）</b>：</p>
 * <ol>
 *   <li><b>{@code aggregate_key} = {@code advanceId}，禁止用 {@code vin}</b>。
 *       {@link OutboxPublisher} 实现端的去重条件是"同 (event_type, aggregate_key) 已有
 *       {@code PENDING} 或 {@code SENT} 就不插"，而 {@code SENT} 行永久保留 ⇒
 *       同一聚合键实际只能发布一条。用 vin 会把同车的第二笔合法垫款<b>永久吞掉</b>
 *       （{@code AGENTS §6.3} / 契约 §3.3 明令）。</li>
 *   <li><b>不手拼 JSON</b>。用 {@link ObjectMapper} 序列化：本方法运行在放款确认的事务里，
 *       一个坏 JSON（转义漏了）会让这笔确认整个回滚，而且现场只剩一条 500。</li>
 *   <li><b>不 catch 发布异常</b>。载荷不合法 = 生产方 bug，让它把事务打断（契约 §5 的 fail-closed），
 *       比"回调返回 200 但账上没有这笔债"正确得多。</li>
 * </ol>
 *
 * @see LoanCreatedCommand 字段口径与"为什么本期不发 dueDate/gracePeriodDays"
 */
public class OutboxLoanCreatedPublisher implements LoanCreatedPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxLoanCreatedPublisher.class);

    /** 发布端（{@code JdbcOutboxPublisher}）：在调用方事务内写入 + NOT EXISTS 去重，本类不重复实现去重。 */
    private final OutboxPublisher outbox;

    /** Spring Boot 自动装配的 ObjectMapper（web 侧有 Jackson），负责把命令序列化成契约形状的 JSON。 */
    private final ObjectMapper mapper;

    public OutboxLoanCreatedPublisher(OutboxPublisher outbox, ObjectMapper mapper) {
        this.outbox = outbox;
        this.mapper = mapper;
    }

    /**
     * 组装契约载荷并交发布端落库。
     *
     * <p>字段名逐一对齐 {@code docs/接口契约-事件与Feign.md §2.1}；
     * {@code dealerId}/{@code creditLineId} 允许为 null（契约里是"建议"字段），
     * Jackson 会写成 JSON {@code null}，消费端 {@code DomainEvent.longField} 取到 null 后按缺省处理。</p>
     */
    @Override
    public void publish(LoanCreatedCommand cmd) {
        // traceId 必须在进 SQL 之前先自己查一遍：发布端的 requireVinAndTraceId 只判"非空白"，
        // 而 String.valueOf(null) 得到的是四个字符的 "null"，非空白 → 能通过校验，
        // 于是跨服务永远串不上链的坏值会被伪装成成功（AGENTS §12「假绿」）。
        String traceId = cmd.traceId();
        if (traceId == null || traceId.isBlank() || "null".equals(traceId)) {
            throw new IllegalArgumentException(
                    "LOAN_CREATED 缺 traceId，无法跨服务排障（取值=" + traceId + "）：advanceId=" + cmd.advanceId());
        }
        if (cmd.vin() == null || cmd.vin().isBlank()) {
            throw new IllegalArgumentException("LOAN_CREATED 缺 vin，消费端无法定位车辆：advanceId=" + cmd.advanceId());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("vin", cmd.vin());
        payload.put("traceId", traceId);
        payload.put("advanceId", cmd.advanceId());
        payload.put("dealerId", cmd.dealerId());
        payload.put("creditLineId", cmd.creditLineId());
        // principal 走 long，序列化出来是 JSON 整数 —— 金额一律整数日元，禁止小数（AGENTS §6.3）
        payload.put("principal", cmd.principal());

        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // 理论到不了这里（全是标量），但绝不 return 吞掉：宁可这笔确认失败
            throw new IllegalStateException("LOAN_CREATED 载荷序列化失败：advanceId=" + cmd.advanceId(), e);
        }

        // 同事务写入；重复发布由实现端去重，不抛异常也不插第二条
        outbox.publish(OutboxPublisher.EVENT_LOAN_CREATED, String.valueOf(cmd.advanceId()), json);
        log.info("LOAN_CREATED 已发布 advanceId={} vin={} principal={} traceId={}",
                cmd.advanceId(), cmd.vin(), cmd.principal(), traceId);
    }
}
