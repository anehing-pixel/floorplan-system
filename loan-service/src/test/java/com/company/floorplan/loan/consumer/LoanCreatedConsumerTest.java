package com.company.floorplan.loan.consumer;

import com.company.floorplan.common.event.DomainEvent;
import com.company.floorplan.loan.model.Loan;
import com.company.floorplan.loan.service.LoanApplicationService;
import com.company.floorplan.loan.service.LoanApplicationService.LoanCreatedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LoanCreatedConsumer} 的载荷解析单测（不依赖数据库）。
 *
 * <p>钉两件事：① 必填字段缺失必须抛异常——dispatcher 只有看见异常才会重试/置 DEAD，
 * 静默 return 会把坏载荷记成 DONE；② 可选字段缺失<b>不</b>兜默认值，
 * 尤其 {@code dueDate}：core 侧无权威源（Q-EVT3 未定源），这里造一个"看起来合理"的到期日
 * 等于凭空生成本金清偿义务。</p>
 *
 * <p>另外钉住 {@code name()} 的取值：它是 {@code consumer_offsets} / {@code event_consumptions}
 * 的键，改了等于换了一个消费者，历史事件会被从头重放。</p>
 */
class LoanCreatedConsumerTest {

    private final LoanApplicationService service = mock(LoanApplicationService.class);
    private final LoanCreatedConsumer consumer = new LoanCreatedConsumer(service);

    /** 拼一条 outbox 事件：eventId 固定，payload 用字面 JSON（形状以契约 §2.1 为准）。 */
    private static DomainEvent event(String payload) {
        return new DomainEvent(101L, "LOAN_CREATED", "900001", payload, Instant.now());
    }

    /** 取出真正传给建据服务的那个事件对象；顺带断言"只调了一次"。 */
    private LoanCreatedEvent capture() {
        ArgumentCaptor<LoanCreatedEvent> cap = ArgumentCaptor.forClass(LoanCreatedEvent.class);
        verify(service).createFromLoanCreated(cap.capture());
        return cap.getValue();
    }

    @Test
    void 完整载荷逐字段传给建据() {
        when(service.createFromLoanCreated(any())).thenReturn(new Loan());
        consumer.consume(event("{\"vin\":\"JT12345678901234\",\"traceId\":\"T-1\",\"advanceId\":900001,"
                + "\"dealerId\":7,\"creditLineId\":8,\"principal\":1665000,"
                + "\"dueDate\":\"2026-12-31\",\"gracePeriodDays\":3}"));

        LoanCreatedEvent e = capture();
        assertEquals("JT12345678901234", e.vin());
        assertEquals(900001L, e.advanceId());
        assertEquals(7L, e.dealerId());
        assertEquals(8L, e.creditLineId());
        assertEquals(1665000L, e.principal());
        assertEquals(LocalDate.of(2026, 12, 31), e.dueDate());
        assertEquals(3, e.gracePeriodDays());
        assertEquals("T-1", e.traceId());
    }

    @Test
    void 缺dueDate时不造默认值但建据继续() {
        when(service.createFromLoanCreated(any())).thenReturn(new Loan());
        consumer.consume(event("{\"vin\":\"JT1\",\"traceId\":\"T-2\",\"advanceId\":900002,"
                + "\"dealerId\":7,\"creditLineId\":8,\"principal\":100000}"));
        assertNull(capture().dueDate());
    }

    @Test
    void 宽限期缺失按零() {
        when(service.createFromLoanCreated(any())).thenReturn(new Loan());
        consumer.consume(event("{\"vin\":\"JT1\",\"traceId\":\"T-3\",\"advanceId\":1,"
                + "\"dealerId\":7,\"creditLineId\":8,\"principal\":100}"));
        assertEquals(0, capture().gracePeriodDays());
    }

    @Test
    void 宽限期为负原样交给应用层收敛() {
        when(service.createFromLoanCreated(any())).thenReturn(new Loan());
        consumer.consume(event("{\"vin\":\"JT1\",\"traceId\":\"T-4\",\"advanceId\":2,"
                + "\"dealerId\":7,\"creditLineId\":8,\"principal\":100,\"gracePeriodDays\":-5}"));
        assertEquals(-5, capture().gracePeriodDays());
    }

    @Test
    void dueDate格式不合法要抛而不是当成缺失() {
        assertThrows(IllegalArgumentException.class, () -> consumer.consume(
                event("{\"vin\":\"JT1\",\"traceId\":\"T-5\",\"advanceId\":3,\"dealerId\":7,"
                        + "\"creditLineId\":8,\"principal\":100000,\"dueDate\":\"31/12/2026\"}")));
        verify(service, never()).createFromLoanCreated(any());
    }

    @Test
    void 缺vin或advanceId直接拒() {
        IllegalArgumentException noVin = assertThrows(IllegalArgumentException.class, () -> consumer.consume(
                event("{\"traceId\":\"T\",\"advanceId\":1,\"dealerId\":7,\"creditLineId\":8,\"principal\":1}")));
        assertTrue(noVin.getMessage().contains("vin"), noVin.getMessage());

        IllegalArgumentException noAdvance = assertThrows(IllegalArgumentException.class, () -> consumer.consume(
                event("{\"vin\":\"JT1\",\"traceId\":\"T\",\"dealerId\":7,\"creditLineId\":8,\"principal\":1}")));
        // "钱没到，债不成立"：这句理由必须留在异常里，否则排障时看不出是回调顺序问题
        assertTrue(noAdvance.getMessage().contains("PAID"), noAdvance.getMessage());
        verify(service, never()).createFromLoanCreated(any());
    }

    @Test
    void 缺归属或金额异常一律拒_不留半条借据() {
        // loans.dealer_id / credit_line_id / principal 都是 NOT NULL，缺了数据库也会拒；
        // 在这里挡下是为了让异常说清"哪个字段缺"，而不是抛一条裸的 not-null violation。
        assertThrows(IllegalArgumentException.class, () -> consumer.consume(
                event("{\"vin\":\"JT1\",\"traceId\":\"T\",\"advanceId\":1,\"creditLineId\":8,\"principal\":1}")));
        assertThrows(IllegalArgumentException.class, () -> consumer.consume(
                event("{\"vin\":\"JT1\",\"traceId\":\"T\",\"advanceId\":1,\"dealerId\":7,\"principal\":1}")));
        assertThrows(IllegalArgumentException.class, () -> consumer.consume(
                event("{\"vin\":\"JT1\",\"traceId\":\"T\",\"advanceId\":1,\"dealerId\":7,\"creditLineId\":8}")));
        assertThrows(IllegalArgumentException.class, () -> consumer.consume(
                event("{\"vin\":\"JT1\",\"advanceId\":1,\"dealerId\":7,\"creditLineId\":8,\"principal\":100}")));
        assertThrows(IllegalArgumentException.class, () -> consumer.consume(
                event("{\"vin\":\"JT1\",\"traceId\":\"T\",\"advanceId\":1,\"dealerId\":7,"
                        + "\"creditLineId\":8,\"principal\":-1}")));
        verify(service, never()).createFromLoanCreated(any());
    }

    @Test
    void 消费名与订阅集合不许漂移() {
        assertEquals("loan-service", consumer.name());
        assertEquals(1, consumer.eventTypes().size());
        assertTrue(consumer.eventTypes().contains("LOAN_CREATED"));
    }

    @Test
    void 同一advanceId的两次投递各自转建据_幂等不在本层() {
        // 框架层（event_consumptions 主键）只挡同一个 event_id 重投；
        // 这里是"新 event_id、同 advanceId"，必须照常转给应用服务，由 loan_no 唯一键挡第二笔债
        // （见 LoanCreateIdempotencyTest）。消费者自己再去重就会把合法的第二次发布吞掉。
        when(service.createFromLoanCreated(any())).thenReturn(new Loan());
        String payload = "{\"vin\":\"JT1\",\"traceId\":\"T-6\",\"advanceId\":900009,"
                + "\"dealerId\":7,\"creditLineId\":8,\"principal\":100000}";
        consumer.consume(event(payload));
        consumer.consume(event(payload));
        verify(service, times(2)).createFromLoanCreated(any());
    }
}
