package com.company.floorplan.application.service;

import com.company.floorplan.domain.model.AgingPlan;
import com.company.floorplan.domain.model.InterestEntry;
import com.company.floorplan.domain.model.VehicleStatus;
import com.company.floorplan.domain.model.CreditLine;
import com.company.floorplan.domain.model.VehicleUnit;
import com.company.floorplan.domain.repository.AgingPlanRepository;
import com.company.floorplan.domain.repository.AnnualReviewRepository;
import com.company.floorplan.domain.repository.CreditLineRepository;
import com.company.floorplan.domain.repository.DisposalCaseRepository;
import com.company.floorplan.domain.repository.InterestLedgerRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.domain.repository.VehicleUnitRepository;
import com.company.floorplan.domain.service.InterestCalculator;
import com.company.floorplan.application.service.AnnualReviewApplicationService;
import com.company.floorplan.application.service.DisposalApplicationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批处理作业（日终）。
 * <p>
 * 幂等基石：interest_ledger(vin, biz_date) 唯一约束 + batch_runs(job_name, biz_date) 唯一约束。
 * 各作业方法独立事务，由 Controller 分别调用（避免同类自调用绕过事务代理）。
 */
@Service
public class BatchApplicationService {

    private final VehicleUnitRepository vehicleUnitRepository;
    private final InterestLedgerRepository interestLedgerRepository;
    private final AgingPlanRepository agingPlanRepository;
    private final SupportRepository supportRepository;
    private final DisposalCaseRepository disposalCaseRepository;
    private final CreditLineRepository creditLineRepository;
    private final AnnualReviewRepository annualReviewRepository;
    private final DisposalApplicationService disposalApplicationService;
    private final AnnualReviewApplicationService annualReviewApplicationService;

    /** 库龄递减：首次触发天数（预设 90 天，需客户确认）。 */
    @Value("${floorplan.aging.first-trigger-days:90}")
    private int agingFirstTriggerDays;

    /** 库龄递减：每期间隔天数（预设 30 天）。 */
    @Value("${floorplan.aging.period-days:30}")
    private int agingPeriodDays;

    /** 库龄递减：每期偿还比例（预设 10% 原始垫款）。 */
    @Value("${floorplan.aging.reduce-ratio:0.10}")
    private BigDecimal agingReduceRatio;

    /** T2-4（M6/G4）：库龄递减豁免 VIN 名单（逗号分隔），如主机厂调拨中/整备运输中的车。 */
    @Value("${floorplan.aging.exempt-vins:}")
    private String agingExemptVins;

    /**
     * 结算窗口宽限期（营业日）—— 贴合日本实务：
     * 售出后超结算窗口（预设 2 营业日）未还，先"告警但允许"；
     * 仅在<b>宽限期内</b>（预设 5 营业日）仍未还，才升级为脱信销售(OOT)嫌疑（最高优先级）。
     */
    @Value("${floorplan.settle-window-grace-days:5}")
    private int settleWindowGraceDays;

    /** 额度年审间隔（天）：超过该间隔且无进行中年审，年审扫描会触发复核。 */
    @Value("${floorplan.annual-review.interval-days:365}")
    private int annualReviewIntervalDays;

    public BatchApplicationService(VehicleUnitRepository vehicleUnitRepository,
                                   InterestLedgerRepository interestLedgerRepository,
                                   AgingPlanRepository agingPlanRepository,
                                   SupportRepository supportRepository,
                                   DisposalCaseRepository disposalCaseRepository,
                                   CreditLineRepository creditLineRepository,
                                   AnnualReviewRepository annualReviewRepository,
                                   DisposalApplicationService disposalApplicationService,
                                   AnnualReviewApplicationService annualReviewApplicationService) {
        this.vehicleUnitRepository = vehicleUnitRepository;
        this.interestLedgerRepository = interestLedgerRepository;
        this.agingPlanRepository = agingPlanRepository;
        this.supportRepository = supportRepository;
        this.disposalCaseRepository = disposalCaseRepository;
        this.creditLineRepository = creditLineRepository;
        this.annualReviewRepository = annualReviewRepository;
        this.disposalApplicationService = disposalApplicationService;
        this.annualReviewApplicationService = annualReviewApplicationService;
    }

    /**
     * 日终计息作业（业务闭环第 ⑤ 环节）。
     *
     * <p><b>补算能力</b>：从每台车的 {@code lastInterestDate} 次日起，
     * 逐日补算到目标业务日。这样即使某天作业未执行（故障/维护），
     * 下次执行也会把漏掉的天数补齐，<b>不会漏计</b>。</p>
     *
     * <p><b>幂等保证（两层）</b>：
     * <ul>
     *   <li>业务层：{@code batch_runs(job_name, biz_date)} 记录该业务日已成功，
     *       重复触发直接跳过；</li>
     *   <li>数据层：{@code interest_ledger(vin, biz_date)} 唯一约束 +
     *       {@code ON CONFLICT DO NOTHING}，即使绕过业务层检查也不会重复计息。</li>
     * </ul>
     *
     * <p><b>利率快照</b>：每条流水写入当时的利率（rate_snapshot），
     * 支持利率变更后的历史追溯与重算。</p>
     *
     * @param bizDate 计息业务日
     * @return 作业结果：处理台数、合计利息；若该业务日已成功则返回 skipped=true
     */
    @Transactional
    public Map<String, Object> runDailyInterest(LocalDate bizDate) {
        if (supportRepository.batchSucceeded("DAILY_INTEREST", bizDate)) {
            return Map.of("jobName", "DAILY_INTEREST", "bizDate", bizDate.toString(),
                    "skipped", true, "reason", "该业务日已成功执行，幂等跳过");
        }
        supportRepository.markBatchRunning("DAILY_INTEREST", bizDate);
        try {
            List<VehicleUnit> units = vehicleUnitRepository.findInterestBearing(bizDate);
            int processed = 0;
            long total = 0L;
            for (VehicleUnit unit : units) {
                LocalDate cursor = unit.getLastInterestDate() != null
                        ? unit.getLastInterestDate()
                        : (unit.getStockInDate() != null ? unit.getStockInDate().minusDays(1) : bizDate.minusDays(1));
                if (cursor.isAfter(bizDate)) {
                    continue;
                }
                int guard = 0;
                while (cursor.isBefore(bizDate) && guard++ < 3650) {
                    cursor = cursor.plusDays(1);
                    long daily = InterestCalculator.dailyInterest(unit.getPrincipalBalance(), unit.getAnnualRate());
                    InterestEntry entry = new InterestEntry();
                    entry.setVin(unit.getVin());
                    entry.setBizDate(cursor);
                    entry.setPrincipalBalance(unit.getPrincipalBalance());
                    entry.setDailyInterest(daily);
                    entry.setRateSnapshot(unit.getAnnualRate());
                    interestLedgerRepository.saveIfAbsent(entry);
                    unit.accrueInterest(daily, cursor);
                    total += daily;
                }
                vehicleUnitRepository.save(unit);
                processed++;
            }
            supportRepository.markBatchFinished("DAILY_INTEREST", bizDate, true, null);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("jobName", "DAILY_INTEREST");
            data.put("bizDate", bizDate.toString());
            data.put("status", "SUCCESS");
            data.put("processedCount", processed);
            data.put("totalInterest", total);
            return data;
        } catch (RuntimeException ex) {
            supportRepository.markBatchFinished("DAILY_INTEREST", bizDate, false, ex.getMessage());
            throw ex;
        }
    }

    /** 库龄推进。 */
    @Transactional
    public Map<String, Object> runAgingAdvance(LocalDate bizDate) {
        supportRepository.markBatchRunning("AGING_ADVANCE", bizDate);
        try {
            List<VehicleUnit> units = vehicleUnitRepository.findByStatus(VehicleStatus.IN_STOCK);
            int processed = 0;
            for (VehicleUnit unit : units) {
                if (unit.getStockInDate() != null) {
                    unit.setAgingDays((int) ChronoUnit.DAYS.between(unit.getStockInDate(), bizDate));
                    vehicleUnitRepository.save(unit);
                    processed++;
                }
            }
            supportRepository.markBatchFinished("AGING_ADVANCE", bizDate, true, null);
            return Map.of("jobName", "AGING_ADVANCE", "bizDate", bizDate.toString(),
                    "status", "SUCCESS", "processedCount", processed);
        } catch (RuntimeException ex) {
            supportRepository.markBatchFinished("AGING_ADVANCE", bizDate, false, ex.getMessage());
            throw ex;
        }
    }

    /** 库龄递减计划生成（规则版本化，不影响存量计划）。 */
    @Transactional
    public Map<String, Object> runAgingReduction(LocalDate bizDate) {
        supportRepository.markBatchRunning("AGING_REDUCTION_DUE", bizDate);
        try {
            List<VehicleUnit> units = vehicleUnitRepository.findByStatus(VehicleStatus.IN_STOCK);
            int created = 0;
            java.util.Set<String> exempt = parseExemptVins();
            for (VehicleUnit unit : units) {
                // T2-4（M6/G4）：豁免车不生成递减计划（调拨中/已签约未交车/整备运输中）
                if (AgingPlan.isExempt(unit.getVin(), exempt)) {
                    continue;
                }
                if (unit.getAgingDays() == null || unit.getAgingDays() < agingFirstTriggerDays) {
                    continue;
                }
                if (agingPlanRepository.findByVin(unit.getVin()).isPresent()) {
                    continue;
                }
                long reduceAmount = BigDecimal.valueOf(unit.getAdvanceAmount())
                        .multiply(agingReduceRatio).setScale(0, RoundingMode.HALF_UP).longValueExact();
                AgingPlan plan = new AgingPlan();
                plan.setVin(unit.getVin());
                plan.setRuleVersion("V1");
                plan.setFirstTriggerDate(bizDate);
                plan.setPeriodDays(agingPeriodDays);
                plan.setReduceRatio(agingReduceRatio);
                plan.setReduceAmount(reduceAmount);
                plan.setPaidPeriods(0);
                plan.setNextDueDate(bizDate.plusDays(agingPeriodDays));
                plan.setStatus("ACTIVE");
                agingPlanRepository.save(plan);
                supportRepository.createAlert(unit.getDealerId(), unit.getVin(), "AGING", "MEDIUM",
                        "库龄 " + unit.getAgingDays() + " 天达阈值，生成递减还款计划，每期 " + reduceAmount);
                created++;
            }
            supportRepository.markBatchFinished("AGING_REDUCTION_DUE", bizDate, true, null);
            return Map.of("jobName", "AGING_REDUCTION_DUE", "bizDate", bizDate.toString(),
                    "status", "SUCCESS", "createdPlans", created);
        } catch (RuntimeException ex) {
            supportRepository.markBatchFinished("AGING_REDUCTION_DUE", bizDate, false, ex.getMessage());
            throw ex;
        }
    }

    /** 解析库龄递减豁免 VIN 名单（逗号分隔，配置为空时不豁免任何车）。 */
    private java.util.Set<String> parseExemptVins() {
        if (agingExemptVins == null || agingExemptVins.isBlank()) {
            return java.util.Set.of();
        }
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        for (String vin : agingExemptVins.split(",")) {
            if (!vin.isBlank()) {
                set.add(vin.trim());
            }
        }
        return set;
    }

    /**
     * 结算窗口扫描（业务闭环第 ⑥→⑦ 衔接）—— 贴合日本实务的"告警但允许"。
     *
     * <p><b>两档处理</b>（关键不变量，避免"一刀切"把宽限期内客户直接拉黑）：
     * <ul>
     *   <li><b>超结算窗口、但在宽限期内</b>（{@code overdueDays ≤ settleWindowGraceDays}）：
     *       仅发<b>中危预警</b>（MEDIUM），车辆<b>保持 SOLD_PENDING、不标记 OOT</b>，
     *       即"告警但允许"，给车商筹措资金的时间；</li>
     *   <li><b>超宽限期仍未还</b>：升级为<b>脱信销售(OOT)嫌疑（HIGH）</b>——
     *       车辆转 OVERDUE、{@code ootSuspected=true}，触发最高优先级告警，
     *       后续由违约处置作业（{@link #runDefaultDisposal}）接力开单。</li>
     * </ul>
     * 日本新车渠道常见场景：客户贷款审批/过户延迟导致晚几天结清，属客观原因，
     * 不应与恶意脱信销售同等处置，因此宽限期是必要设计。</p>
     */
    @Transactional
    public Map<String, Object> runSettlementScan(LocalDate today) {
        supportRepository.markBatchRunning("SETTLE_WINDOW_SCAN", today);
        try {
            List<VehicleUnit> overdueUnits = vehicleUnitRepository.findSettlementOverdue(today);
            int ootCount = 0;
            int warnCount = 0;
            for (VehicleUnit unit : overdueUnits) {
                int overdueDays = (int) ChronoUnit.DAYS.between(unit.getSettleDueDate(), today);
                if (overdueDays <= settleWindowGraceDays) {
                    // 宽限期内：告警但允许，不改状态、不标记 OOT
                    supportRepository.createAlert(unit.getDealerId(), unit.getVin(), "SETTLE_GRACE", "MEDIUM",
                            "售出后超结算窗口 " + overdueDays + " 天（宽限期内 " + settleWindowGraceDays
                                    + " 天），请尽快结清，否则将升级为脱信销售(OOT)嫌疑");
                    warnCount++;
                } else {
                    // 超宽限期：升级为 OOT 嫌疑（最高优先级违约信号）
                    unit.setOotSuspected(true);
                    unit.toOverdue(overdueDays);
                    vehicleUnitRepository.save(unit);
                    supportRepository.createAlert(unit.getDealerId(), unit.getVin(), "OOT_SUSPECTED", "HIGH",
                            "售出后超结算窗口 " + overdueDays + " 天未还款（已超宽限期，脱信销售嫌疑）");
                    ootCount++;
                }
            }
            supportRepository.markBatchFinished("SETTLE_WINDOW_SCAN", today, true, null);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("jobName", "SETTLE_WINDOW_SCAN");
            data.put("bizDate", today.toString());
            data.put("status", "SUCCESS");
            data.put("ootCount", ootCount);
            data.put("warnCount", warnCount);
            return data;
        } catch (RuntimeException ex) {
            supportRepository.markBatchFinished("SETTLE_WINDOW_SCAN", today, false, ex.getMessage());
            throw ex;
        }
    }

    /**
     * 违约处置自动开单（业务闭环 P0-② 违约处置闭环的"自动触发"）。
     *
     * <p>扫描<b>已升级为逾期(OVERDUE)</b>且<b>尚无未结案处置工单</b>的车辆，
     * 自动调用 {@link DisposalApplicationService#open} 开单并转处置中(DISPOSING)，
     * 从而把"识别 → 收车 → 变现 → 损失认定"串成完整闭环，
     * 而非只发一条告警就结束（旧版缺口）。</p>
     */
    @Transactional
    public Map<String, Object> runDefaultDisposal(LocalDate today) {
        supportRepository.markBatchRunning("DEFAULT_DISPOSAL", today);
        try {
            List<VehicleUnit> candidates = vehicleUnitRepository.findByStatus(VehicleStatus.OVERDUE);
            int opened = 0;
            // B-2：返回本轮自动开出的单号，供调用方（运维/冒烟/对账）做闭环处理——
            // 自动开单会冻结整条额度线，若不留痕就无法定向撤销/结案配对解冻。
            List<String> openedCaseNos = new ArrayList<>();
            for (VehicleUnit unit : candidates) {
                var latest = disposalCaseRepository.findLatestByVin(unit.getVin());
                if (latest.isPresent() && !latest.get().getStatus().isTerminal()) {
                    continue; // 已有未结案工单，避免重复开单
                }
                Map<String, Object> openedCase = disposalApplicationService.open(unit.getVin(), "AUTO_OOT", "system");
                Object caseNo = openedCase.get("caseNo");
                if (caseNo != null) {
                    openedCaseNos.add(caseNo.toString());
                }
                opened++;
            }
            supportRepository.markBatchFinished("DEFAULT_DISPOSAL", today, true, null);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("jobName", "DEFAULT_DISPOSAL");
            data.put("bizDate", today.toString());
            data.put("status", "SUCCESS");
            data.put("openedCount", opened);
            data.put("openedCaseNos", openedCaseNos);
            return data;
        } catch (RuntimeException ex) {
            supportRepository.markBatchFinished("DEFAULT_DISPOSAL", today, false, ex.getMessage());
            throw ex;
        }
    }

    /**
     * 额度年审扫描（业务闭环 ⑪ 额度年审与冻结恢复 的"自动触发"）。
     *
     * <p>遍历所有 ACTIVE 额度：若当前无进行中(PENDING/IN_PROGRESS)年审，
     * 且距上次复核完成已超过 {@code annualReviewIntervalDays} 天（或无任何复核记录），
     * 自动调用 {@link AnnualReviewApplicationService#initiate} 启动年审（冻结占用额度）。
     * 已在进行中的不会重复触发（幂等）。</p>
     */
    @Transactional
    public Map<String, Object> runAnnualReviewScan(LocalDate today) {
        supportRepository.markBatchRunning("ANNUAL_REVIEW_SCAN", today);
        try {
            List<CreditLine> lines = creditLineRepository.findActive();
            int initiated = 0;
            for (CreditLine line : lines) {
                // 零占用的额度没有"在途业务"需要复核，冻结量也会是 0（freeze(0) 抛 VALID_002），
                // 直接跳过——同时避免一条历史脏数据毒化整个批处理作业。
                if (line.getUsedAmount() <= 0) {
                    continue;
                }
                if (annualReviewRepository.findPendingByCreditLine(line.getId()).isPresent()) {
                    continue; // 已有进行中年审
                }
                var latest = annualReviewRepository.findLatestByCreditLine(line.getId());
                boolean due = latest.isEmpty()
                        || (latest.get().getCompletedAt() != null
                        && ChronoUnit.DAYS.between(latest.get().getCompletedAt().toLocalDate(), today)
                        >= annualReviewIntervalDays);
                if (due) {
                    annualReviewApplicationService.initiate(line.getDealerId(), line.getId(), "ANNUAL", "system");
                    initiated++;
                }
            }
            supportRepository.markBatchFinished("ANNUAL_REVIEW_SCAN", today, true, null);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("jobName", "ANNUAL_REVIEW_SCAN");
            data.put("bizDate", today.toString());
            data.put("status", "SUCCESS");
            data.put("initiatedCount", initiated);
            return data;
        } catch (RuntimeException ex) {
            supportRepository.markBatchFinished("ANNUAL_REVIEW_SCAN", today, false, ex.getMessage());
            throw ex;
        }
    }

    /**
     * 计息流水按季度归档（预设 {@code floorplan.interest.quarter-archive=true}）。
     *
     * <p>interest_ledger 增长极快（每日每车一条），早于<b>本季度首日</b>的流水被复制到
     * history 表 {@code interest_ledger_archive} 后从主表删除，控制主表体积。
     * 归档以"本季度首日"为业务日做幂等标记，重复跑不会重复删除。</p>
     *
     * @param bizDate 用于确定归档季度的基准日（通常用当天）
     * @return 归档结果：cutoff（不含）与删除条数
     */
    @Transactional
    public Map<String, Object> runArchive(LocalDate bizDate) {
        // cutoff = 本季度首日（早于该日的流水归档）
        int q = (bizDate.getMonthValue() - 1) / 3;
        LocalDate cutoff = LocalDate.of(bizDate.getYear(), q * 3 + 1, 1);
        if (supportRepository.batchSucceeded("INTEREST_ARCHIVE", cutoff)) {
            return Map.of("jobName", "INTEREST_ARCHIVE", "cutoff", cutoff.toString(),
                    "skipped", true, "reason", "该归档周期已成功执行，幂等跳过");
        }
        supportRepository.markBatchRunning("INTEREST_ARCHIVE", cutoff);
        try {
            int deleted = interestLedgerRepository.archiveBefore(cutoff);
            supportRepository.markBatchFinished("INTEREST_ARCHIVE", cutoff, true, null);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("jobName", "INTEREST_ARCHIVE");
            data.put("cutoff", cutoff.toString());
            data.put("status", "SUCCESS");
            data.put("archivedCount", deleted);
            return data;
        } catch (RuntimeException ex) {
            supportRepository.markBatchFinished("INTEREST_ARCHIVE", cutoff, false, ex.getMessage());
            throw ex;
        }
    }
}
