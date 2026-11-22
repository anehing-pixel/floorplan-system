package com.company.floorplan.loan.consumer;

import com.company.floorplan.common.event.DomainEvent;
import com.company.floorplan.common.event.EventConsumer;
import com.company.floorplan.loan.model.Loan;
import com.company.floorplan.loan.service.LoanApplicationService;
import com.company.floorplan.loan.service.LoanApplicationService.LoanCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Set;

/**
 * {@code LOAN_CREATED} 的<b>真消费者</b>（W0-7 留的骨架，W1-LOAN 接线建据）。
 *
 * <p><b>它做什么</b>：银行回调把垫款翻成 PAID 之后，core 在同一事务里写一条 outbox 事件；
 * 本类把它拉下来，交 {@link LoanApplicationService#createFromLoanCreated} 建立借据。
 * 这一根线接通之前，{@code loans} 表恒空，DPD / 逾期 / 画像 / 对账四条线全部空转
 * （{@code AGENTS §9} P0 第 1 条）。</p>
 *
 * <p><b>两层幂等，各管一件事，别混</b>：</p>
 * <ul>
 *   <li><b>框架层</b>：{@code event_consumptions(event_id, consumer)} 主键挡住同一个
 *       {@code event_id} 被消费两次，由 {@code EventDispatcher} 负责，本类无需处理；</li>
 *   <li><b>业务层</b>：同一次放款事实<b>被重新发布成新行</b>（新 {@code event_id}）时框架挡不住，
 *       靠 {@code loan_no = "LN-" + advanceId} 的唯一约束——这条已写在
 *       {@code docs/接口契约-事件与Feign.md §3.2}，实现体在仓储的 {@code ON CONFLICT (loan_no) DO NOTHING}。</li>
 * </ul>
 *
 * <p><b>失败一律抛异常，不许 return</b>。dispatcher 把异常定义为消费失败：记 {@code attempts}、
 * 3 次后置 DEAD。若在数据不合法时静默 return，一条坏载荷会被标成 DONE，
 * 等于把生产方的错误记成"已成功建据"——这正是本项目反复踩过的假绿。</p>
 *
 * <p><b>命名约定</b>：{@link #name()} 返回 {@code "loan-service"}（服务名，不是类名）。
 * 该值同时是 {@code consumer_offsets} 与 {@code event_consumptions} 的键，
 * 一旦上线就不能改——改了等于换了一个消费者，历史事件会被当作全新事件重头读。</p>
 */
@Component
public class LoanCreatedConsumer implements EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(LoanCreatedConsumer.class);

    /** 与 {@code OutboxPublisher.EVENT_LOAN_CREATED} 同一取值，写成字面量以免 loan-service 反向依赖契约常量类。 */
    private static final String EVENT_TYPE = "LOAN_CREATED";

    private final LoanApplicationService loanService;

    public LoanCreatedConsumer(LoanApplicationService loanService) {
        this.loanService = loanService;
    }

    @Override
    public String name() {
        return "loan-service";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(EVENT_TYPE);
    }

    /**
     * 消费一条 {@code LOAN_CREATED}：校验载荷 → 建据 → 打全链路日志。
     *
     * <p>校验口径来自契约 §2.1 的"必填"列 + {@code loans} 表的 NOT NULL 约束：
     * {@code vin}/{@code advanceId}/{@code principal}/{@code dealerId}/{@code creditLineId}
     * 缺任何一个都<b>建不出</b>一条合法借据，所以全部在这里挡下并抛，
     * 而不是让数据库抛一个裸的 not-null violation 混进重试计数。</p>
     *
     * <p>{@code dueDate} 本期<i>可能</i>缺失（core 侧无权威源，Q-EVT3 未定源就不发），
     * 那不是错误：{@link LoanApplicationService#generateRepaymentPlan} 对 {@code null}
     * 是 WARN + 跳过还款计划，借据照建。<b>不许</b>在这里给它造默认值。</p>
     */
    @Override
    public void consume(DomainEvent event) {
        String vin = event.textField("vin");
        Long advanceId = event.longField("advanceId");

        if (vin == null || vin.isBlank()) {
            throw new IllegalArgumentException("LOAN_CREATED 缺 vin，无法定位车辆：event_id=" + event.eventId());
        }
        if (advanceId == null) {
            // 口径来自 createFromLoanCreated 的既有校验："钱没到，债不成立"，这里只把它前移到消费入口。
            throw new IllegalArgumentException("LOAN_CREATED 缺 advanceId，银行回调未 PAID 不得建据：vin=" + vin);
        }

        String traceId = event.textField("traceId");
        if (traceId == null || traceId.isBlank()) {
            // traceId 要落进 loan_status_history.remark；没有它，日后无法从借据反查是哪次放款建的据
            throw new IllegalArgumentException("LOAN_CREATED 缺 traceId：event_id=" + event.eventId() + " vin=" + vin);
        }
        Long dealerId = event.longField("dealerId");
        Long creditLineId = event.longField("creditLineId");
        Long principal = event.longField("principal");
        if (dealerId == null || creditLineId == null) {
            // loans.dealer_id / credit_line_id 是 NOT NULL（V16），缺了建不出行；
            // 契约里这两个字段是"建议"，所以在这里明确成"建据的必要条件"，而不是留给数据库报错。
            throw new IllegalArgumentException("LOAN_CREATED 缺归属字段，借据无法归户："
                    + "event_id=" + event.eventId() + " vin=" + vin
                    + " dealerId=" + dealerId + " creditLineId=" + creditLineId);
        }
        if (principal == null) {
            throw new IllegalArgumentException("LOAN_CREATED 缺 principal，本金无价可记：event_id="
                    + event.eventId() + " vin=" + vin);
        }
        if (principal < 0) {
            // 金额一律 BIGINT 整数日元；负数只可能是载荷写错，不能记成一笔负债
            throw new IllegalArgumentException("LOAN_CREATED 的 principal 为负数：" + principal
                    + "：event_id=" + event.eventId() + " vin=" + vin);
        }

        Loan loan = loanService.createFromLoanCreated(new LoanCreatedEvent(
                vin, advanceId, dealerId, creditLineId, principal,
                parseDueDate(event.textField("dueDate"), event.eventId(), vin),
                gracePeriodDaysOf(event), traceId));

        // 结构化日志带 event_id + traceId + loanNo：跨服务排查时能从借据号一路追回那次放款回调
        log.info("LOAN_CREATED 建据完成 event_id={} loanNo={} loanId={} vin={} advanceId={} principal={} traceId={}",
                event.eventId(), loan.getLoanNo(), loan.getId(), vin, advanceId, principal, traceId);
    }

    /**
     * 解析 ISO 到期日（{@code yyyy-MM-dd}）。缺失/空白 → {@code null}，由应用层跳过还款计划。
     *
     * <p>格式不合法<b>照抛</b>（{@code DateTimeParseException}）：那是生产方写坏了载荷，
     * 该进重试/死信通道，不是"当作没给"。</p>
     */
    private LocalDate parseDueDate(String raw, long eventId, String vin) {
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("LOAN_CREATED 的 dueDate 不是 ISO 日期：" + raw
                    + "：event_id=" + eventId + " vin=" + vin, e);
        }
    }

    /** 宽限期：缺省按 0；负数由 {@code createFromLoanCreated} 的 {@code Math.max(0, …)} 收敛。 */
    private int gracePeriodDaysOf(DomainEvent event) {
        Long grace = event.longField("gracePeriodDays");
        return grace == null ? 0 : grace.intValue();
    }
}
