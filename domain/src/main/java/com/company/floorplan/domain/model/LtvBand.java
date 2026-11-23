package com.company.floorplan.domain.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 一条 LTV 分档带（USED R3.2，{@code ltv_policy_bands}，V57 / D04 §3.14）。
 *
 * <p><b>本类的唯一职责是 {@link #matches}，而它必须是一个「区间包含 / 集合包含」的判定，
 * 不含任何默认值推断</b>（D08 §4.4 原话）。之所以把匹配写在实体上而不是散在闸门里：
 * 政策试算（UC-R3-11）、生效前校验、放款判定三处必须用同一个匹配函数，
 * 否则会出现「模拟说命中 30 带、放款落在 50 带」——那是最难自证的分档缺陷。</p>
 *
 * <p><b>区间一律半开 {@code [min, max)}</b>。依据不是风格，是 V57 的 CHECK：
 * {@code ck_band_age} 要求 {@code min < max}（严格），注释写「相等意味着零宽区间，永不含输入」。
 * 这条只在半开语义下成立（闭区间 [36,36] 含 36），所以建表方就是按半开写的。
 * 落到业务上：0〜30,000km 带不含 30,000km 整，30,000 落进下一个里程带 ——
 * 方向是保守的（里程更大 ⇒ 成数更低）。</p>
 *
 * <p><b>{@code NULL} = 该维不设限</b>（V57 表注释），不是「该维为 0」。
 * 输入缺该维（{@code null}）而带约束它 ⇒ 不命中（D08 §4.4 P-2）。</p>
 *
 * @param id                       主键
 * @param priority                 命中顺序，小者优先（同政策内唯一，V57 {@code uk_ltv_band_priority}）
 * @param ageBandMinMonths         车龄下界（含）
 * @param ageBandMaxMonths         车上界（不含）
 * @param mileageBandMinKm         里程下界（含）
 * @param mileageBandMaxKm         里程上界（不含）
 * @param repairHistoryIn          修復歴集合（CSV，如 {@code NONE,MINOR}）
 * @param gradeMin                 評価点下界（含）
 * @param gradeMax                 評価点上界（不含）
 * @param inspectionRemainingMin   車検残存下界（含，月）
 * @param modelHeat                车型热度（V57 是<b>单值</b>列 {@code model_heat}，
 *                                 D08 §4.2 写的集合名 {@code model_heat_in} 未落库——差异已登记）
 * @param dealerGradeIn            车商等级集合（CSV）
 * @param maxRatio                 该档上限成数（{@code 0 < r <= 1}，L4 由 CHECK 与领域断言双挡）
 * @param rateAddonBps             利率加点（基点）
 * @param breachThreshold          该档突破预警线（0〜100，百分比数值而非比率，V57 CHECK 决定）
 * @param note                     备注
 */
public record LtvBand(Long id, int priority, Integer ageBandMinMonths, Integer ageBandMaxMonths,
                      Integer mileageBandMinKm, Integer mileageBandMaxKm, String repairHistoryIn,
                      BigDecimal gradeMin, BigDecimal gradeMax, Integer inspectionRemainingMin,
                      String modelHeat, String dealerGradeIn, BigDecimal maxRatio,
                      Integer rateAddonBps, BigDecimal breachThreshold, String note) {

    /** 维度名常量：{@link #unmetDimensions} 与试算响应共用，避免同一维度两套叫法。 */
    public static final String DIM_AGE = "age_months";
    public static final String DIM_MILEAGE = "mileage_km";
    public static final String DIM_REPAIR = "repair_history";
    public static final String DIM_GRADE = "grade";
    public static final String DIM_INSPECTION = "inspection_remaining_months";
    public static final String DIM_HEAT = "model_heat";
    public static final String DIM_DEALER = "dealer_grade";

    /** 本带是否命中该输入。 */
    public boolean matches(LtvTierInput input) {
        return unmetDimensions(input).isEmpty();
    }

    /**
     * 逐维判定，返回<b>不满足</b>的维度名（命中判定与「为什么没命中」用同一段代码，
     * 这样试算给出的解释不可能与实际判定分叉）。
     */
    public List<String> unmetDimensions(LtvTierInput input) {
        List<String> unmet = new ArrayList<>(3);
        if (input == null) {
            // 整个输入缺失：不去猜任何一个维度，直接宣告七维全不满足。
            unmet.addAll(Arrays.asList(DIM_AGE, DIM_MILEAGE, DIM_REPAIR, DIM_GRADE,
                    DIM_INSPECTION, DIM_HEAT, DIM_DEALER));
            return unmet;
        }
        if (!intervalOk(ageBandMinMonths, ageBandMaxMonths, input.ageMonths())) {
            unmet.add(DIM_AGE);
        }
        if (!intervalOk(mileageBandMinKm, mileageBandMaxKm, input.mileageKm())) {
            unmet.add(DIM_MILEAGE);
        }
        if (!setOk(repairHistoryIn, repairWord(input.repairHistory()))) {
            unmet.add(DIM_REPAIR);
        }
        if (!intervalOk(gradeMin, gradeMax, input.grade())) {
            unmet.add(DIM_GRADE);
        }
        // 車検残存只有下界一列（V57 无 max 列）：带约束而输入缺失 ⇒ 不命中。
        if (inspectionRemainingMin != null
                && (input.inspectionRemainingMonths() == null
                    || input.inspectionRemainingMonths() < inspectionRemainingMin)) {
            unmet.add(DIM_INSPECTION);
        }
        // 单值列（不是 CSV）：与 D08 §4.2 的集合写法差异见类注释。
        if (modelHeat != null && !modelHeat.isBlank()) {
            String heat = input.modelHeat() == null ? null : input.modelHeat().name();
            if (!modelHeat.trim().toUpperCase(Locale.ROOT).equals(heat)) {
                unmet.add(DIM_HEAT);
            }
        }
        if (!setOk(dealerGradeIn, input.dealerGrade())) {
            unmet.add(DIM_DEALER);
        }
        return unmet;
    }

    /**
     * 整数维度的半开区间判定。
     *
     * <p>带不约束（上下界都 NULL）⇒ 恒过；带约束而输入缺失 ⇒ 不过（P-2「缺维度不是视为满足」）。</p>
     */
    private static boolean intervalOk(Integer min, Integer max, Integer value) {
        if (min == null && max == null) {
            return true;
        }
        if (value == null) {
            return false;
        }
        return (min == null || value >= min) && (max == null || value < max);
    }

    /** {@link BigDecimal} 维度（評価点）的同一条规则，比较用 {@code compareTo} 不用 equals。 */
    private static boolean intervalOk(BigDecimal min, BigDecimal max, BigDecimal value) {
        if (min == null && max == null) {
            return true;
        }
        if (value == null) {
            return false;
        }
        return (min == null || value.compareTo(min) >= 0) && (max == null || value.compareTo(max) < 0);
    }

    /** CSV 集合包含：集合为空 = 该维不设限。 */
    private static boolean setOk(String csv, String value) {
        if (csv == null || csv.isBlank()) {
            return true;
        }
        if (value == null || value.isBlank()) {
            return false;
        }
        String target = value.trim().toUpperCase(Locale.ROOT);
        for (String token : csv.split(",")) {
            if (target.equals(token.trim().toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 修復歴的从严折叠：<b>{@code UNKNOWN} 按 {@code MAJOR} 参与集合匹配</b>（D08 §4.1
     * 「UNKNOWN 与 MAJOR 同档处理」、§10 用例 {@code unknownRepairHistoryTreatedAsMajor}）。
     *
     * <p>为什么在带匹配里折、而不是要求政策把 UNKNOWN 也写进 CSV：V57 的列注释
     * 明确「UNKNOWN 不写入任何带的集合内」。两边都要照顾到，只剩这一种落法。
     * 直接后果：{@code repair_history_in='NONE,MINOR'} 的带永远不含 UNKNOWN 车，
     * UNKNOWN 车只能落进「修復歴不设限」的带 ⟹ 成数更低，与设计意图一致。</p>
     */
    private static String repairWord(RepairHistory history) {
        if (history == null) {
            return null;
        }
        return history == RepairHistory.UNKNOWN ? RepairHistory.MAJOR.name() : history.name();
    }

    /**
     * 该带是否给出了突破预警线。
     *
     * <p>缺失时监控层（UC-R3-8 突破判定）无法算「实际 LTV 是否越界」，只能按「无阈值」告警。
     * 本切片只把它读出来带着走：突破判定属监控层，与放款基准无关（D08 §2 额度层不重算）。</p>
     */
    public boolean hasBreachThreshold() {
        return breachThreshold != null;
    }
}
