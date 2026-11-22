package com.company.floorplan.loan.service;

import com.company.floorplan.loan.model.DpdStage;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * DPD（Days Past Due）计算与 M 系列分层 —— <b>纯函数</b>，无 I/O、无时钟、无 Spring 上下文。
 *
 * <p><b>为什么必须是纯函数</b>：DPD 判定是全系统（催收、额度冻结、处置、征信）共用的口径，
 * 一旦它隐式读时钟或读数据库，同一笔借据在不同时刻、不同节点会算出不同结果，
 * 而"五个下游口径统一"正是 ADR-008 的核心目标。因此"今天是哪天"由调用方显式传入 asOf。</p>
 *
 * <p><b>分档口径（设计稿 §4.3 种子数据，1-based 自然日；Q14 已业务定源）</b>：
 * <pre>
 *   DPD ≤ 0     → NONE（未逾期）
 *   1  – 30     → M1 早期逾期
 *   31 – 60     → M2 风险升高
 *   61 – 90     → M3 严重逾期
 *   ≥ 91        → M4 待认定违约候选
 * </pre>
 * <b>口径差异如实说明</b>：任务卡 AGENT_TASK.md 第 8 条把单测边界写成
 * "0天/1-29/30-59/60-90/90+"，ADR-008 §2.6 又写过 0-based 的 "M1≥31/M2≥61/M3≥91"。
 * 三者边界点不一致。按任务卡"按设计稿实现不要自己发明"的要求，本实现采用设计稿 §4.3
 * 种子数据的 1-based 口径（设计稿 §6.1 亦明确将 ADR-008 修订为 M1=1-30）。
 * 单测同时覆盖两套口径列出的所有边界点值（29/30/31、59/60/61、90/91），把边界钉死。</p>
 *
 * <p><b>宽限期</b>：逾期自 到期日+宽限期 起算，宽限期不计入 DPD（设计稿 §9 待确认第 3 项默认）。
 * 因此 effectiveDueDate = dueDate + gracePeriodDays，DPD = max(0, asOf − effectiveDueDate)。</p>
 */
public final class DpdCalculator {

    /** 与设计稿 dpd_stage_rules 种子数据一致的版本号，写入 overdue_records 快照。 */
    public static final String RULE_VERSION = "v1";
    /**
     * 逾期天数口径：自然日（CALENDAR）。
     *
     * <p><b>业务定源 Q14（2026-11-22）：M1/M2/M3 与 DPD 一律按自然日</b>，
     * 周末、节假日同样计入，不按营业日跳过。这是最终口径，不是「一期临时 / 待 holidays
     * 灌数据后切换营业日」的过渡态——系统不提供 BUSINESS 切换能力，
     * {@code overdue_records.day_basis_snapshot} 仅作为事后复现的历史快照字段保留。</p>
     *
     * <p>判定依据见 {@link #daysPastDue} 的 {@code ChronoUnit.DAYS.between}：纯日历差，
     * 天然不查营业日历；{@link DpdCalculatorTest#daysPastDue_calendarBasis_doesNotSkipWeekends()}
     * 用跨周末用例把该口径钉死，防止后来人误改为营业日。</p>
     */
    public static final String DAY_BASIS = "CALENDAR";
    /** 判定阈值快照，供事后复现当时口径。 */
    public static final String THRESHOLD_SNAPSHOT = "{\"M1\":30,\"M2\":60,\"M3\":90}";

    private DpdCalculator() {
    }

    /**
     * 计算逾期天数。纯函数：相同入参永远得到相同结果。
     *
     * @param asOf            判定日（由调用方传入，本方法不读系统时钟）
     * @param dueDate         合同/期次到期日
     * @param gracePeriodDays 宽限期天数（负数按 0 处理）
     * @return DPD，最小为 0（未到期或当天到期均为 0）
     */
    public static int daysPastDue(LocalDate asOf, LocalDate dueDate, int gracePeriodDays) {
        if (asOf == null || dueDate == null) {
            return 0; // 期限未录入 → 不视为逾期
        }
        LocalDate effectiveDue = gracePeriodDays > 0
                ? dueDate.plusDays(gracePeriodDays) : dueDate;
        long days = ChronoUnit.DAYS.between(effectiveDue, asOf);
        if (days <= 0) {
            return 0;
        }
        return (int) Math.min(days, Integer.MAX_VALUE);
    }

    /**
     * 按 DPD 归 M 系列阶段。纯函数。
     *
     * @param dpd 逾期天数（调用方保证 ≥0；传负数按未逾期处理）
     * @return 阶段；未逾期返回 {@link DpdStage#NONE}
     */
    public static DpdStage stageOf(int dpd) {
        if (dpd <= 0) return DpdStage.NONE;
        if (dpd <= 30) return DpdStage.M1;
        if (dpd <= 60) return DpdStage.M2;
        if (dpd <= 90) return DpdStage.M3;
        return DpdStage.M4;
    }

    /** 一次计算 DPD 与阶段，避免调用方两次传参不一致。 */
    public static DpdResult evaluate(LocalDate asOf, LocalDate dueDate, int gracePeriodDays) {
        int dpd = daysPastDue(asOf, dueDate, gracePeriodDays);
        return new DpdResult(dpd, stageOf(dpd));
    }

    /** DPD 判定结果：dpd + 阶段。 */
    public record DpdResult(int dpd, DpdStage stage) {
        /** 落库用阶段码：未逾期为 null（对应 loans.current_stage_code 可空）。 */
        public String stageCode() {
            return stage == DpdStage.NONE ? null : stage.getCode();
        }
    }
}
