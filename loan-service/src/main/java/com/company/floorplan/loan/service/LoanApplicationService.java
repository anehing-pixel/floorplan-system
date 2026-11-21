package com.company.floorplan.loan.service;

import com.company.floorplan.loan.model.DealerRiskProfile;
import com.company.floorplan.loan.model.DpdStage;
import com.company.floorplan.loan.model.Loan;
import com.company.floorplan.loan.model.LoanStatus;
import com.company.floorplan.loan.model.OverdueRecord;
import com.company.floorplan.loan.model.OverdueRecordStatus;
import com.company.floorplan.loan.model.RepaymentPlan;
import com.company.floorplan.loan.model.RepaymentPlanStatus;
import com.company.floorplan.loan.repository.JdbcLoanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 借据应用服务 —— "算风险"的一层。
 *
 * <p><b>职责边界</b>：建据（消费 LOAN_CREATED）、生成还款计划、每晚 DPD 批量刷新、
 * 经销商画像全量重算。不读画像做流程判定（设计稿铁律①），不发布事件
 * （消费触发由 outbox 投递器接线，见 HANDOFF TODO）。</p>
 */
@Service
public class LoanApplicationService {

    private static final Logger log = LoggerFactory.getLogger(LoanApplicationService.class);

    private final JdbcLoanRepository repo;

    public LoanApplicationService(JdbcLoanRepository repo) {
        this.repo = repo;
    }

    /** LOAN_CREATED 事件载荷：payload 必含 vin + traceId（outbox 契约）。 */
    public record LoanCreatedEvent(String vin, Long advanceId, Long dealerId, Long creditLineId,
                                   long principal, LocalDate dueDate, int gracePeriodDays, String traceId) {
    }

    /**
     * 消费 outbox 事件 LOAN_CREATED 建立借据并生成一期还款计划。
     *
     * <p><b>幂等</b>：loan_no = "LN-" + advanceId。一笔垫款对应一笔借据，事件重投时
     * ON CONFLICT 挡住，按 VIN 复用已有借据，不产生第二笔债。</p>
     *
     * <p><b>钱没到，债不成立</b>：advanceId 为空说明银行回调尚未 PAID，直接拒绝建据。</p>
     */
    public Loan createFromLoanCreated(LoanCreatedEvent e) {
        if (e.vin() == null || e.vin().isBlank()) {
            throw new IllegalArgumentException("LOAN_CREATED 载荷缺 vin");
        }
        if (e.advanceId() == null) {
            throw new IllegalArgumentException("advanceId 缺失：银行回调 PAID 后才建据（钱没到，债不成立）");
        }

        Loan loan = new Loan();
        loan.setLoanNo("LN-" + e.advanceId());
        loan.setAdvanceId(e.advanceId());
        loan.setVin(e.vin());
        loan.setDealerId(e.dealerId());
        loan.setCreditLineId(e.creditLineId());
        loan.setPrincipal(e.principal());
        loan.setDueDate(e.dueDate());
        loan.setMaturityDate(e.dueDate());
        loan.setGracePeriodDays(Math.max(0, e.gracePeriodDays()));
        loan.setStatus(LoanStatus.DRAFT.name());
        loan.setTermSource("CONTRACT");

        long id = repo.insert(loan);
        if (id == 0L) {
            // 事件重投：按 VIN 复用未结清借据（advance_id 是确定性幂等键）
            Loan existing = repo.findActiveByVin(e.vin());
            if (existing != null) {
                log.info("LOAN_CREATED 重复投递，复用已有借据 vin={} loanId={} traceId={}",
                        e.vin(), existing.getId(), e.traceId());
                return existing;
            }
            throw new IllegalStateException("借据幂等冲突但按 VIN 未找到已有借据: loanNo=" + loan.getLoanNo());
        }

        loan.setId(id);
        generateRepaymentPlan(id, e.principal(), e.dueDate());
        repo.insertStatusHistory(id, null, LoanStatus.DRAFT.name(),
                "LOAN_CREATED traceId=" + e.traceId(), "system");
        return loan;
    }

    /** 生成一期还款计划（一次性到期还本；合同为分期时走下面的按月版本）。 */
    public int generateRepaymentPlan(long loanId, long principal, LocalDate dueDate) {
        if (dueDate == null) {
            log.warn("借据 {} 期限未录入，跳过还款计划生成（到期概念不存在）", loanId);
            return 0;
        }
        RepaymentPlan p = new RepaymentPlan();
        p.setLoanId(loanId);
        p.setPeriodNo(1);
        p.setDueDate(dueDate);
        p.setPrincipalDue(principal);
        p.setInterestDue(0);
        p.setTotalDue(principal);
        p.setStatus(RepaymentPlanStatus.PENDING.name());
        return repo.insertRepaymentPlan(p);
    }

    /** 按月分期还款计划：余数并入最后一期，避免本金对不上。 */
    public int generateMonthlyPlans(long loanId, long principal, LocalDate firstDueDate, int periods) {
        if (periods < 1) {
            throw new IllegalArgumentException("分期期数必须 ≥1");
        }
        if (firstDueDate == null) {
            throw new IllegalArgumentException("首期到期日不能为空");
        }
        long base = principal / periods;
        long remainder = principal % periods;
        int inserted = 0;
        for (int i = 1; i <= periods; i++) {
            RepaymentPlan p = new RepaymentPlan();
            p.setLoanId(loanId);
            p.setPeriodNo(i);
            p.setDueDate(firstDueDate.plusMonths(i - 1L));
            p.setPrincipalDue(base + (i == periods ? remainder : 0));
            p.setInterestDue(0);
            p.setTotalDue(p.getPrincipalDue());
            p.setStatus(RepaymentPlanStatus.PENDING.name());
            inserted += repo.insertRepaymentPlan(p);
        }
        return inserted;
    }

    /** 每晚 T+1 批量刷新 DPD。返回处理笔数。 */
    public int refreshDpd(LocalDate asOf) {
        int processed = 0;
        for (Loan loan : repo.findAllOpen()) {
            processed += refreshOne(loan, asOf);
        }
        log.info("DPD 批量刷新完成 asOf={} processed={}", asOf, processed);
        return processed;
    }

    /**
     * 单笔刷新：逾期起算点取首个未足额期的 due_date + 宽限期。
     * 状态迁移与留痕在同一应用服务内完成（status 由仓储的 updateStatus 唯一写入）。
     */
    public int refreshOne(Loan loan, LocalDate asOf) {
        List<RepaymentPlan> plans = repo.findPlansByLoanId(loan.getId());
        RepaymentPlan firstUnpaid = plans.stream()
                .filter(p -> !RepaymentPlanStatus.PAID.name().equals(p.getStatus()))
                .min(Comparator.comparing(RepaymentPlan::getDueDate))
                .orElse(null);
        if (firstUnpaid == null || firstUnpaid.getDueDate() == null) {
            return 0; // 无未足额期 → 不逾期
        }

        int dpd = DpdCalculator.daysPastDue(asOf, firstUnpaid.getDueDate(), loan.getGracePeriodDays());
        DpdStage stage = DpdCalculator.stageOf(dpd);
        String stageCode = stage == DpdStage.NONE ? null : stage.getCode();
        LocalDate overdueSince = dpd > 0
                ? firstUnpaid.getDueDate().plusDays(Math.max(0, loan.getGracePeriodDays())) : null;

        // 唯一写入点：当前 DPD / 阶段 / 历史峰值（GREATEST 只增不减）
        repo.updateDpdSnapshot(loan.getId(), dpd, stageCode, overdueSince);

        // 逾期记录：一笔借据一条，跨档只升档不新增
        if (dpd > 0) {
            OverdueRecord o = new OverdueRecord();
            o.setOverdueNo("OD-" + loan.getId());
            o.setLoanId(loan.getId());
            o.setVin(loan.getVin());
            o.setDealerId(loan.getDealerId());
            o.setScheduleId(firstUnpaid.getId());
            o.setOverdueDate(overdueSince);
            o.setDpd(dpd);
            o.setCurrentStageCode(stageCode);
            o.setStageRuleVersion(DpdCalculator.RULE_VERSION);
            o.setDayBasisSnapshot(DpdCalculator.DAY_BASIS);
            o.setStageThresholdSnapshot(DpdCalculator.THRESHOLD_SNAPSHOT);
            o.setOutstanding(loan.getPrincipalOutstanding() + loan.getInterestOutstanding()
                    + loan.getPenaltyOutstanding() + loan.getFeeOutstanding());
            o.setStatus(OverdueRecordStatus.OPEN.name());
            repo.upsertOverdueRecord(o);
        }

        // 状态机：逾期只能由还款计划未足额触发（客观事实），不人工置入
        String target = dpd > 0 ? LoanStatus.OVERDUE.name() : LoanStatus.ACTIVE.name();
        if (!target.equals(loan.getStatus())) {
            int moved = repo.updateStatus(loan.getId(), loan.getStatus(), target);
            if (moved == 1) {
                repo.insertStatusHistory(loan.getId(), loan.getStatus(), target,
                        "DPD=" + dpd + " stage=" + stageCode, "system");
            }
        }
        return 1;
    }

    /**
     * 经销商画像全量重算（异步任务调用；带 dataAsOf，可全量重算、可停算）。
     *
     * <p>一期只做展示与人工参考，riskTier 不参与定价/准入（设计稿 §6.2）。
     * 本方法不写 loans，也不被任何流程判定读取。</p>
     */
    public DealerRiskProfile recomputeProfile(long dealerId, LocalDate asOf) {
        List<Loan> loans = repo.findByDealerId(dealerId);
        DealerRiskProfile p = new DealerRiskProfile();
        p.setDealerId(dealerId);
        p.setDataAsOf(asOf);
        p.setComputedBy("loan-service-v1");

        p.setActiveLoanCount((int) loans.stream().filter(l -> LoanStatus.isOpen(l.getStatus())).count());
        p.setOutstandingTotal(loans.stream().mapToLong(this::totalOutstanding).sum());
        p.setOldestDueDate(loans.stream().map(Loan::getDueDate).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null));

        List<Loan> overdue = loans.stream().filter(l -> LoanStatus.OVERDUE.name().equals(l.getStatus())).toList();
        p.setCurrentOverdueCount(overdue.size());
        p.setCurrentOverdueAmount(overdue.stream().mapToLong(this::totalOutstanding).sum());
        p.setCurrentWorstStage(overdue.stream().map(Loan::getCurrentStageCode).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null));
        p.setMaxOverdueDays(loans.stream().mapToInt(Loan::getMaxDpdEver).max().orElse(0));

        // TODO(跨表)：近 12m 违约次数精确口径需 default_events 表（V16 五表之外），
        // 当前用 loans.default_flag + defaulted_at 近似；deduction_fail_rate_30d 需扣款流水表。
        List<Loan> defaulted = loans.stream().filter(Loan::isDefaultFlag).toList();
        p.setDefaultsTotal(defaulted.size());
        int recent = (int) defaulted.stream()
                .filter(l -> l.getDefaultedAt() != null
                        && !l.getDefaultedAt().toLocalDate().isBefore(asOf.minusDays(365)))
                .count();
        p.setDefaultsLast12m(recent);
        p.setDeductionFailCount30d(loans.stream().mapToInt(Loan::getDeductionFailTotal).sum());
        p.setDeductionFailStreakMax(loans.stream().mapToInt(Loan::getDeductionFailStreak).max().orElse(0));

        TierDecision decision = decideTier(p);
        p.setRiskTier(decision.tier());
        p.setTierReason(decision.reason());
        p.setScoredAt(LocalDateTime.now());

        repo.upsertProfile(p);
        return p;
    }

    /** 画像分层规则（一期仅展示，不参与流程）。 */
    private TierDecision decideTier(DealerRiskProfile p) {
        if (p.getDefaultsLast12m() > 0) {
            return new TierDecision("D", "近 12 个月违约 " + p.getDefaultsLast12m() + " 次");
        }
        if (p.getMaxOverdueDays() >= 91) {
            return new TierDecision("D", "历史最大逾期 " + p.getMaxOverdueDays() + " 天");
        }
        if (p.getMaxOverdueDays() >= 61 || p.getCurrentOverdueCount() >= 3) {
            return new TierDecision("C", "最大逾期 " + p.getMaxOverdueDays() + " 天 / 当前逾期 "
                    + p.getCurrentOverdueCount() + " 笔");
        }
        if (p.getMaxOverdueDays() >= 31 || p.getCurrentOverdueCount() >= 2) {
            return new TierDecision("B", "最大逾期 " + p.getMaxOverdueDays() + " 天 / 当前逾期 "
                    + p.getCurrentOverdueCount() + " 笔");
        }
        return new TierDecision("A", "无逾期与违约记录");
    }

    private record TierDecision(String tier, String reason) {
    }

    private long totalOutstanding(Loan l) {
        return l.getPrincipalOutstanding() + l.getInterestOutstanding()
                + l.getPenaltyOutstanding() + l.getFeeOutstanding();
    }

    /** 查询用：借据详情（不存在返回 null，由 Controller 决定 404）。 */
    public Loan getLoan(long id) {
        return repo.findById(id);
    }

    /** 查询用：契约端点 by-vin。 */
    public Loan getActiveLoanByVin(String vin) {
        return repo.findActiveByVin(vin);
    }

    /** 查询用：经销商画像（不存在返回 null）。 */
    public DealerRiskProfile getProfile(long dealerId) {
        return repo.findProfileByDealerId(dealerId);
    }

    /** 查询用：某笔借据截至 asOf 的 DPD（不写库）。 */
    public DpdCalculator.DpdResult dpdOf(long loanId, LocalDate asOf) {
        Loan loan = repo.findById(loanId);
        if (loan == null) {
            return null;
        }
        LocalDate firstDue = repo.findPlansByLoanId(loanId).stream()
                .filter(p -> !RepaymentPlanStatus.PAID.name().equals(p.getStatus()))
                .map(RepaymentPlan::getDueDate).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(loan.getDueDate());
        return DpdCalculator.evaluate(asOf, firstDue, loan.getGracePeriodDays());
    }
}
