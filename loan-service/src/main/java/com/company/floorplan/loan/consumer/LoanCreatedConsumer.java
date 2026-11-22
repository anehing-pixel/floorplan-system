package com.company.floorplan.loan.consumer;

import com.company.floorplan.common.event.DomainEvent;
import com.company.floorplan.common.event.EventConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * {@code LOAN_CREATED} 的消费者<b>骨架</b>（W0-7）。
 *
 * <p><b>本类存在的两个目的</b>，都不是业务：</p>
 * <ol>
 *   <li>证明 W0 的事件机制真的能跑通——发布/游标/幂等/重试这条链在 loan-service 里有落点；</li>
 *   <li>把 W1-LOAN 的接线位置固定下来，避免五个 agent 各自另起一个消费者、
 *       用两个不同 consumer 名读同一个队列（那样游标与幂等记录都会分裂成两套口径）。</li>
 * </ol>
 *
 * <p><b>它刻意不做什么</b>：不调用 {@code LoanApplicationService.createFromLoanCreated()}。
 * 地基波不带业务动作，否则"机制没验证成，先把借据建错了"——排查时无法区分是机制坏还是业务坏。</p>
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

    @Override
    public String name() {
        return "loan-service";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(EVENT_TYPE);
    }

    /**
     * 骨架实现：只做 payload 合法性校验 + 结构化日志。
     *
     * <p>校验不过<b>必须抛异常</b>而不是 return：dispatcher 把异常定义为消费失败，
     * 会记 {@code attempts} 并在 3 次后置 DEAD。若在这里 return，一条 payload 残缺的事件
     * 会被标成 DONE，等于把生产方的错误记成"已成功消费"——这正是本项目反复踩过的"假绿"。</p>
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

        log.info("LOAN_CREATED 已收到（W0 骨架，未建据）event_id={} vin={} advanceId={} traceId={}",
                event.eventId(), vin, advanceId, event.textField("traceId"));

        // TODO(W1-LOAN)：在此接线建据——
        //   LoanApplicationService.LoanCreatedEvent e = new LoanApplicationService.LoanCreatedEvent(
        //       vin, advanceId, event.longField("dealerId"), event.longField("creditLineId"),
        //       principal, LocalDate.parse(event.textField("dueDate")), gracePeriodDays, traceId);
        //   loanApplicationService.createFromLoanCreated(e);
        // 接线时的三件事必须一起做完，缺一项就是"写了代码没接通"：
        //   1) 把 LoanApplicationService 注入进来（该服务只扫自身包，application 层 bean 要在启动类 @Import）；
        //   2) 保持幂等：loan_no = "LN-" + advanceId + 唯一约束，事件重投不产生第二笔债；
        //   3) 在 scripts/smoke_w1_loan.py 里断言"插一条 LOAN_CREATED → loans 多一行，重复消费不重复建"。
    }
}
