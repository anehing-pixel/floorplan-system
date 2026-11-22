package com.company.floorplan.loan.service;

import com.company.floorplan.loan.model.Loan;
import com.company.floorplan.loan.model.LoanStatus;
import com.company.floorplan.loan.model.RepaymentPlan;
import com.company.floorplan.loan.repository.JdbcLoanRepository;
import com.company.floorplan.loan.service.LoanApplicationService.LoanCreatedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 建据的业务幂等单测（fake 仓储，不连库）。
 *
 * <p><b>为什么必须单独钉这一层</b>：框架的 {@code event_consumptions(event_id, consumer)}
 * 只挡"同一个 event_id 重投"。同一次放款事实一旦被重新发布成新的一行 outbox（新 id），
 * 框架会照样调一次 {@code consume()}——挡住第二笔债的只有 {@code loan_no = "LN-" + advanceId}
 * 这个业务唯一键（契约 §3.2）。这条线不测，就只能等真实重复投递时在生产上发现。</p>
 *
 * <p>仓储 {@code insert} 用 {@code ON CONFLICT (loan_no) DO NOTHING}，冲突时不报错、返回 0，
 * 所以"复用既有借据"这个分支的入口条件就是 {@code id == 0}，这里用打桩复现。</p>
 */
class LoanCreateIdempotencyTest {

    private final JdbcLoanRepository repo = mock(JdbcLoanRepository.class);
    private final LoanApplicationService service = new LoanApplicationService(repo);

    private static LoanCreatedEvent evt(LocalDate dueDate, int grace) {
        return new LoanCreatedEvent("JT12345678901234", 900001L, 7L, 8L, 1_665_000L,
                dueDate, grace, "TRACE-1");
    }

    @Test
    void 首次建据用advanceId做幂等键并落状态历史() {
        when(repo.insert(any(Loan.class))).thenReturn(555L);
        Loan saved = service.createFromLoanCreated(evt(LocalDate.of(2026, 12, 31), 3));

        ArgumentCaptor<Loan> cap = ArgumentCaptor.forClass(Loan.class);
        verify(repo).insert(cap.capture());
        Loan row = cap.getValue();
        assertEquals("LN-900001", row.getLoanNo());
        assertEquals(LoanStatus.DRAFT.name(), row.getStatus());
        assertEquals(900001L, row.getAdvanceId());
        assertEquals(1_665_000L, row.getPrincipal());
        // 到期日同时写 due_date 与 maturity_date（V16 两列同值，兼容设计稿引用名）
        assertEquals(LocalDate.of(2026, 12, 31), row.getDueDate());
        assertEquals(row.getDueDate(), row.getMaturityDate());
        assertEquals(555L, saved.getId());
        verify(repo).insertStatusHistory(eq(555L), isNull(), eq(LoanStatus.DRAFT.name()),
                anyString(), eq("system"));
    }

    @Test
    void 有到期日才生成还款计划() {
        when(repo.insert(any(Loan.class))).thenReturn(555L);
        service.createFromLoanCreated(evt(LocalDate.of(2026, 12, 31), 3));

        ArgumentCaptor<RepaymentPlan> cap = ArgumentCaptor.forClass(RepaymentPlan.class);
        verify(repo).insertRepaymentPlan(cap.capture());
        RepaymentPlan plan = cap.getValue();
        assertEquals(555L, plan.getLoanId());
        assertEquals(1, plan.getPeriodNo());
        assertEquals(1_665_000L, plan.getPrincipalDue());
        assertEquals(1_665_000L, plan.getTotalDue());
    }

    /**
     * Q-EVT3（到期日/宽限期权威源）未定源 → core 本期不发 dueDate，落点就是这条：
     * 借据照建、还款计划不生成、不抛异常。宁可"期限未录入"，也不要一个自造的到期日。
     */
    @Test
    void 无到期日时借据仍建成但不生成还款计划() {
        when(repo.insert(any(Loan.class))).thenReturn(555L);
        Loan loan = service.createFromLoanCreated(evt(null, 0));

        assertEquals("LN-900001", loan.getLoanNo());
        verify(repo, never()).insertRepaymentPlan(any());
        verify(repo).insertStatusHistory(anyLong(), isNull(), anyString(), anyString(), anyString());
    }

    @Test
    void 重复投递复用既有借据_不产生第二笔债() {
        when(repo.insert(any(Loan.class))).thenReturn(0L);   // ON CONFLICT (loan_no) DO NOTHING
        Loan existing = new Loan();
        existing.setId(555L);
        existing.setLoanNo("LN-900001");
        when(repo.findActiveByVin("JT12345678901234")).thenReturn(existing);

        Loan result = service.createFromLoanCreated(evt(LocalDate.of(2026, 12, 31), 3));

        assertSame(existing, result);
        verify(repo, never()).insertRepaymentPlan(any());
        verify(repo, never()).insertStatusHistory(anyLong(), any(), anyString(), anyString(), anyString());
    }

    /**
     * 唯一键冲突却按 VIN 找不到未结清借据：说明这笔债已结清/被作废，
     * 但又被重新发布了一次——这是数据异常，必须抛出去（置 DEAD 人工看），
     * 不能"当作成功"把一次可疑的重复放款抹平。
     */
    @Test
    void 冲突且找不到既有借据要抛而不是静默成功() {
        when(repo.insert(any(Loan.class))).thenReturn(0L);
        when(repo.findActiveByVin(anyString())).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> service.createFromLoanCreated(evt(LocalDate.of(2026, 12, 31), 3)));
    }

    @Test
    void 钱没到就不建据() {
        // advanceId 为空 = 银行回调未 PAID；这条校验必须在任何写库动作之前
        LoanCreatedEvent noAdvance = new LoanCreatedEvent("JT1", null, 7L, 8L, 100L,
                LocalDate.of(2026, 12, 31), 0, "TRACE-2");
        assertThrows(IllegalArgumentException.class, () -> service.createFromLoanCreated(noAdvance));
        verify(repo, never()).insert(any());

        LoanCreatedEvent noVin = new LoanCreatedEvent("  ", 900002L, 7L, 8L, 100L,
                LocalDate.of(2026, 12, 31), 0, "TRACE-3");
        assertThrows(IllegalArgumentException.class, () -> service.createFromLoanCreated(noVin));
        verify(repo, never()).insert(any());
    }

    @Test
    void 负宽限期按零收敛() {
        when(repo.insert(any(Loan.class))).thenReturn(555L);
        service.createFromLoanCreated(evt(LocalDate.of(2026, 12, 31), -5));

        ArgumentCaptor<Loan> cap = ArgumentCaptor.forClass(Loan.class);
        verify(repo).insert(cap.capture());
        assertEquals(0, cap.getValue().getGracePeriodDays());
    }
}
