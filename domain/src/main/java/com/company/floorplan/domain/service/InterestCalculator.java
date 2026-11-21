package com.company.floorplan.domain.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 计息规则（纯领域逻辑，不依赖任何框架）。
 *
 * <p><b>业务方确定的口径（已锁定，标注【预设】）</b>：
 * <pre>
 *   日息 = 未偿本金 × 年利率 ÷ 365
 * </pre>
 * <ul>
 *   <li><b>含当日</b>：垫款/入库当天即起息（第 0 天计入）。
 *       实现位置在 {@code VehicleUnit.stockIn()}——把 {@code lastInterestDate}
 *       初始化为"入库日 − 1 天"，日终批处理从"上次计息日 + 1"补算，
 *       首轮恰好覆盖入库当天。详见 {@code BatchApplicationService.runDailyInterest}。</li>
 *   <li><b>逐日量化 + 四舍五入</b>：每天用 {@link BigDecimal} 高精度中间值算日息，
 *       再 {@code setScale(0, HALF_UP)} 量化为整数日元后写入 {@code interest_ledger}。</li>
 *   <li><b>结清以流水汇总为准</b>：结清时应收利息 = {@code interest_ledger} 按 VIN 求和，
 *       不重新计算，避免"重算值与流水值不一致"。</li>
 *   <li><b>按季度归档（预设）</b>：{@code interest_ledger} 增长快，
 *       早于本季度首日的流水由归档作业复制到 {@code interest_ledger_archive} 后删除
 *       （见 {@code BatchApplicationService.runArchive}）。</li>
 * </ul>
 * </p>
 *
 * <p><b>为什么放在领域层</b>：计息是库存融资最核心的业务规则，
 * 必须可独立单元测试（边界用例：提前还款、部分还款、利率中途变更、
 * 跨月、节假日、闰年、贴息轧差），不能被框架或持久化细节污染。</p>
 *
 * <p><b>计息基准 365 天</b>为预设值，需向客户确认是否采用 365 还是 360。</p>
 */
public final class InterestCalculator {

    /** 计息基准天数（预设 365，需客户确认）。 */
    public static final int DAYS_IN_YEAR = 365;

    private InterestCalculator() {
    }

    /**
     * 计算单台单日利息。
     *
     * @param principal  未偿本金（整数日元）
     * @param annualRate 年利率（如 0.03）
     * @return 当日利息（整数日元，四舍五入）
     */
    public static long dailyInterest(long principal, BigDecimal annualRate) {
        if (principal <= 0 || annualRate == null || annualRate.signum() <= 0) {
            return 0L;
        }
        BigDecimal daily = BigDecimal.valueOf(principal)
                .multiply(annualRate)
                .divide(BigDecimal.valueOf(DAYS_IN_YEAR), 10, RoundingMode.HALF_UP);
        return daily.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
