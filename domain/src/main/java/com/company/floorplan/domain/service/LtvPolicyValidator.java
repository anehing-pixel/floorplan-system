package com.company.floorplan.domain.service;

import com.company.floorplan.domain.model.LtvBand;
import com.company.floorplan.domain.model.LtvPolicy;
import com.company.floorplan.domain.model.LtvTierInput;
import com.company.floorplan.domain.model.ModelHeat;
import com.company.floorplan.domain.model.RepairHistory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * LTV 政策不变式校验（USED R3.4，D08 §4.2 的 L1–L6，纯函数）。
 *
 * <p><b>为什么必须有它</b>：V57 把「每行可表达的 CHECK」都写进了 DDL（区间方向、比率范围、
 * priority &gt; 0），但不变式里有三条<b>库里表达不了</b>：L3 要跨行聚合（兜底必须严格小于
 * 所有带的最小成数）、L2 要对维度笛卡尔积取样、L1 的「同序多带」是带集合的组合性质。
 * 这三条不在保存/生效时点查，就只能等放款时炸出来——而那时已经有人在等这笔钱了。</p>
 *
 * <p><b>漏掉的代价是静默的</b>：L1 还有 {@link LtvTierResolver} 的运行时断言兜底，
 * L3 破口却表现为「兜底比正常档还宽」——分档表写得越差、越多车落兜底、放得越多钱。</p>
 *
 * <p>不在本类做的事：L5（ACTIVE 不得改带）与 L6（同码多 ACTIVE 生效期不重叠）
 * 都要读库/看状态，属政策用例的编排判定；L6 还额外被 V57 的 {@code uk_policy_active}
 * 结构性挡住（同码同时只能一行 ACTIVE，谈不上重叠）。</p>
 */
public final class LtvPolicyValidator {

    /** 取样上限：超过就收窄维度取值，并在报告里写明「未穷举」。宁可承认没查完，也不给假的「全覆盖」。 */
    private static final int MAX_SAMPLES = 20_000;

    /** 修復歴维度的固定取值集：UNKNOWN 必须在内（它按 MAJOR 从严参与匹配，见 {@link LtvBand}）。 */
    private static final List<RepairHistory> REPAIR_VALUES =
            List.of(RepairHistory.NONE, RepairHistory.MINOR, RepairHistory.MAJOR, RepairHistory.UNKNOWN);

    /** 热度维度同理：UNKNOWN 是一个合法档，不是「缺失」。 */
    private static final List<ModelHeat> HEAT_VALUES =
            List.of(ModelHeat.HIGH, ModelHeat.NORMAL, ModelHeat.LOW, ModelHeat.UNKNOWN);

    private LtvPolicyValidator() {
    }

    /**
     * 一条违规。
     *
     * @param invariant 不变式编号（L1/L2/L3/L4 或 BANDS_EMPTY/PRIORITY/RATIO 这类结构项）
     * @param detail    说清「哪一条带、哪一个维度、什么值」——只说「违反 L3」的报错等于让人重算一遍
     */
    public record Violation(String invariant, String detail) {
    }

    /**
     * 校验报告。
     *
     * @param violations        违规清单；空 = 通过
     * @param samplesChecked    L2 实际取样数
     * @param uncoveredCount    零命中的样本数（只统计；判违规的条件是「有缺口且无兜底」）
     * @param uncoveredExamples 未命中样本示例（最多 5 条，供改带的人照着补）
     * @param exhaustive        L2 是否穷举（false = 被 {@link #MAX_SAMPLES} 收窄过）
     */
    public record Report(List<Violation> violations, int samplesChecked, int uncoveredCount,
                         List<String> uncoveredExamples, boolean exhaustive) {

        public boolean valid() {
            return violations.isEmpty();
        }
    }

    /**
     * 校验一份政策（含其带集合与兜底成数）。
     *
     * <p>调用时点：政策草稿保存后、批准生效前（UC-R3-4 的 validate）、试算前（UC-R3-11）。
     * 三处必须同一函数——否则「validate 说通过、approve 说不通过」会让人以为校验可协商。</p>
     */
    public static Report validate(LtvPolicy policy) {
        List<Violation> violations = new ArrayList<>();
        List<LtvBand> bands = policy == null || policy.getBands() == null ? List.of() : policy.getBands();
        BigDecimal fallback = policy == null ? null : policy.getDefaultRatio();

        if (bands.isEmpty()) {
            // 空带集合不单独是一条不变式，它是 L2 的极端形态：任意输入都零命中。
            // 单独立一条是因为报错更有用——"缺兜底成数"对一张空表是误导，真正的问题是还没有带。
            // ⚠ V57 头注把这条写作「D06 §6.3.1 的 V-11」，但该锚点在当前设计集里不存在
            //   （D06 §6 是 R6 监管仓）。本包按 L2 的原文表达同一条要求，不沿用那个查不到的编号。
            // V57 seed 就是 0 行（分档数值属 [待定源 D-06]），所以这条在本轮交付时点必然命中，
            // 命中它是「数值还没签」的正确表现，不是 bug（V57 头注原话）。
            violations.add(new Violation("BANDS_EMPTY",
                    "带集合为空：政策无可命中档，生效即等于全部落兜底或全部拒付"));
        }

        BigDecimal minBandRatio = null;
        Set<Integer> priorities = new LinkedHashSet<>();
        for (LtvBand band : bands) {
            if (band.priority() <= 0) {
                violations.add(new Violation("PRIORITY", "priority 必须 > 0，当前 " + band.priority()));
            }
            if (!priorities.add(band.priority())) {
                // L1 的存储前提。V57 uk_ltv_band_priority 在库里挡，但草稿与试算阶段还没落库，
                // 在这里先报出来，别等 insert 抛一个不带业务语义的 500。
                violations.add(new Violation("L1", "同一政策内 priority 重复：" + band.priority()
                        + "（同序多带会构成 LTV_002 歧义）"));
            }
            BigDecimal ratio = band.maxRatio();
            if (ratio == null || ratio.signum() <= 0) {
                violations.add(new Violation("RATIO", "priority=" + band.priority() + " 的带未给 max_ratio"));
            } else if (ratio.compareTo(BigDecimal.ONE) > 0) {
                // L4：>1 是融资本息之外的超额融资。库里 CHECK 是同一道闸，这里提前判是为了
                // 把「哪一条带」说清楚——CHECK 只会说"违反约束"。
                violations.add(new Violation("L4", "priority=" + band.priority() + " 的 max_ratio="
                        + ratio + " 超过 1.0"));
            } else if (ratio.compareTo(BigDecimal.ONE) == 0) {
                // 恰好 1.0 = 全额融资档：V57 的 CHECK 写作 <=1 允许它，但 D08 §5.4 禁止事项与
                // §4.3 自检要点 4（所有带 <=0.80）都不给这一档。判违规：库里允许、业务禁止的组合
                // 应由校验层拦下，而不是等出事再改 DDL。
                violations.add(new Violation("L4", "priority=" + band.priority()
                        + " 的 max_ratio=1.0 即全额融资档，D08 §5.4 禁止（CHECK 允许但业务不允许）"));
            } else if (minBandRatio == null || ratio.compareTo(minBandRatio) < 0) {
                minBandRatio = ratio;
            }
            if (band.breachThreshold() != null
                    && (band.breachThreshold().signum() <= 0
                        || band.breachThreshold().compareTo(new BigDecimal("100")) > 0)) {
                violations.add(new Violation("BREACH", "priority=" + band.priority()
                        + " 的 breach_threshold=" + band.breachThreshold() + " 不在 (0,100]（该列是百分比数值）"));
            }
        }

        if (fallback != null) {
            if (fallback.signum() <= 0 || fallback.compareTo(BigDecimal.ONE) >= 0) {
                violations.add(new Violation("L3", "兜底成数必须在 (0,1) 内，当前 " + fallback));
            } else if (minBandRatio != null && fallback.compareTo(minBandRatio) >= 0) {
                // D08 §4.3 读法 3 的原话：兜底 0.40 与最小带 0.40 相等即违反 L3（必须<b>严格</b>小于）。
                // 那张示例表因此是反面示例，正式表必须先改再上线——本条就是「改」的判据。
                violations.add(new Violation("L3", "兜底成数 " + fallback + " 未严格小于最小带成数 "
                        + minBandRatio + "（L3：兜底必须比任何正常档更保守）"));
            }
        }

        // ---- L2 全覆盖：维度笛卡尔积的边界样本 ----
        SampleSet samples = boundarySamples(bands);
        int uncovered = 0;
        List<String> examples = new ArrayList<>(5);
        for (LtvTierInput sample : samples.values()) {
            if (LtvTierResolver.resolve(sample, bands, fallback).status() == LtvTierResolver.Status.NO_TIER) {
                uncovered++;
                if (examples.size() < 5) {
                    examples.add(describe(sample));
                }
            }
        }
        if (uncovered > 0 && fallback == null) {
            violations.add(new Violation("L2", samples.values().size() + " 个边界样本中 " + uncovered
                    + " 个零命中，且政策未给兜底成数（L2 要求「任意合法输入至少命中一带或落入兜底」）"
                    + (samples.exhaustive() ? "；" : "（样本空间被收窄，未穷举）")
                    + "；示例 " + examples));
        }
        return new Report(List.copyOf(violations), samples.values().size(), uncovered,
                List.copyOf(examples), samples.exhaustive());
    }

    /** 样本集合 + 是否穷举。 */
    private record SampleSet(List<LtvTierInput> values, boolean exhaustive) {
    }

    /**
     * 从带集合自身导出边界取值，组成笛卡尔积样本。
     *
     * <p>为什么「从带集合导出」而不是固定网格：L2 的破口恰好出现在带与带的接缝上
     * （36 月与 37 月、30,000km 与 30,001km），固定网格会整片跳过接缝。
     * 每个边界都取「含」与「不含」两侧，接缝两侧就都在样本里了
     * （区间是半开 {@code [min,max)}，所以 {@code max} 本身属于下一档，两侧都要试）。</p>
     *
     * <p>另外无条件加入稀疏样本：全维度缺失、只给单一维度。
     * 「输入缺维度时该带不命中」（D08 §4.4）意味着这类样本几乎必然零命中，
     * 它们能不能落兜底是 L2 最容易漏的一半。</p>
     */
    private static SampleSet boundarySamples(List<LtvBand> bands) {
        Set<Integer> ageSet = new LinkedHashSet<>();
        Set<Integer> mileageSet = new LinkedHashSet<>();
        Set<BigDecimal> gradeSet = new LinkedHashSet<>();
        Set<Integer> inspectionSet = new LinkedHashSet<>();
        Set<String> dealerSet = new LinkedHashSet<>();
        ageSet.add(0);
        mileageSet.add(0);
        gradeSet.add(BigDecimal.ZERO);
        inspectionSet.add(0);
        for (LtvBand b : bands) {
            around(ageSet, b.ageBandMinMonths(), b.ageBandMaxMonths());
            around(mileageSet, b.mileageBandMinKm(), b.mileageBandMaxKm());
            aroundDecimal(gradeSet, b.gradeMin(), b.gradeMax());
            if (b.inspectionRemainingMin() != null) {
                addNonNegative(inspectionSet, b.inspectionRemainingMin() - 1);
                addNonNegative(inspectionSet, b.inspectionRemainingMin());
            }
            dealerSet.addAll(csv(b.dealerGradeIn()));
        }
        List<Integer> ages = new ArrayList<>(ageSet);
        List<Integer> mileages = new ArrayList<>(mileageSet);
        List<BigDecimal> grades = new ArrayList<>(gradeSet);
        List<Integer> inspections = new ArrayList<>(inspectionSet);
        List<ModelHeat> heats = new ArrayList<>(HEAT_VALUES);
        List<String> dealers = new ArrayList<>(dealerSet);
        int dealerSize = Math.max(1, dealers.size());

        List<LtvTierInput> samples = new ArrayList<>();
        // 稀疏样本先塞：数量小但覆盖「缺维度」这一整类，不能被后面的收窄挤掉。
        samples.add(new LtvTierInput(null, null, null, null, null, null, null));
        for (RepairHistory r : REPAIR_VALUES) {
            samples.add(new LtvTierInput(null, null, r, null, null, null, null));
        }
        samples.add(new LtvTierInput(null, null, null, null, 1, null, null));

        long product = (long) ages.size() * mileages.size() * REPAIR_VALUES.size()
                * grades.size() * inspections.size() * heats.size() * dealerSize;
        boolean exhaustive = true;
        // 收窄到上限以内：只砍"中间值"，首尾保留（接缝还在）。
        while (product > MAX_SAMPLES && !allMinimal(ages, mileages, grades, inspections, heats)) {
            ages = thinOnce(ages);
            mileages = thinOnce(mileages);
            grades = thinOnce(grades);
            inspections = thinOnce(inspections);
            heats = thinOnce(heats);
            product = (long) ages.size() * mileages.size() * REPAIR_VALUES.size()
                    * grades.size() * inspections.size() * heats.size() * dealerSize;
            exhaustive = false;
        }
        for (Integer age : ages) {
            for (Integer mileage : mileages) {
                for (RepairHistory repair : REPAIR_VALUES) {
                    for (BigDecimal grade : grades) {
                        for (Integer inspection : inspections) {
                            for (ModelHeat heat : heats) {
                                for (int d = 0; d < dealerSize; d++) {
                                    samples.add(new LtvTierInput(age, mileage, repair, grade, inspection,
                                            heat, dealers.isEmpty() ? null : dealers.get(d)));
                                }
                            }
                        }
                    }
                }
            }
        }
        // 收窄后仍超上限（某一维取值太多，例如 dealer 集合很大）：如实报"未穷举"，
        // 而不是偷偷只跑前 N 条然后说覆盖没问题。
        return new SampleSet(samples, exhaustive && product <= MAX_SAMPLES);
    }

    /** 某一维收窄到 2 个值就不再收了：再收就丢掉首尾之外的所有接缝。 */
    private static boolean allMinimal(List<Integer> ages, List<Integer> mileages, List<BigDecimal> grades,
                                      List<Integer> inspections, List<ModelHeat> heats) {
        return ages.size() <= 2 && mileages.size() <= 2 && grades.size() <= 2
                && inspections.size() <= 2 && heats.size() <= 2;
    }

    private static <T> List<T> thinOnce(List<T> values) {
        if (values.size() <= 2) {
            return values;
        }
        List<T> out = new ArrayList<>(3);
        out.add(values.get(0));
        out.add(values.get(values.size() / 2));
        out.add(values.get(values.size() - 1));
        return out;
    }

    /** 生成示例文本（放进违规详情，改带的人能照着复现）。 */
    private static String describe(LtvTierInput in) {
        return "{age=" + in.ageMonths() + ",km=" + in.mileageKm() + ",repair=" + in.repairHistory()
                + ",grade=" + in.grade() + ",inspection=" + in.inspectionRemainingMonths()
                + ",heat=" + in.modelHeat() + ",dealer=" + in.dealerGrade() + "}";
    }

    private static void around(Set<Integer> target, Integer min, Integer max) {
        if (min != null) {
            addNonNegative(target, min - 1);
            addNonNegative(target, min);
        }
        if (max != null) {
            addNonNegative(target, max - 1);
            addNonNegative(target, max);
        }
    }

    /** 负值不进样本：库内 CHECK 要求这些列 &gt;= 0，测负值只能证明「非法输入不命中」，那是废话。 */
    private static void addNonNegative(Set<Integer> target, int value) {
        if (value >= 0) {
            target.add(value);
        }
    }

    /** 評価点标度 NUMERIC(3,1)，接缝按 0.1 取（4.4/4.5 两侧都在）。 */
    private static void aroundDecimal(Set<BigDecimal> target, BigDecimal min, BigDecimal max) {
        BigDecimal step = new BigDecimal("0.1");
        if (min != null) {
            addGrade(target, min.subtract(step));
            addGrade(target, min);
        }
        if (max != null) {
            addGrade(target, max.subtract(step));
            addGrade(target, max);
        }
    }

    private static void addGrade(Set<BigDecimal> target, BigDecimal value) {
        if (value.signum() >= 0 && value.compareTo(new BigDecimal("10")) <= 0) {
            target.add(value);
        }
    }

    private static List<String> csv(String raw) {
        List<String> out = new ArrayList<>(2);
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String token : raw.split(",")) {
            if (!token.isBlank()) {
                out.add(token.trim());
            }
        }
        return out;
    }
}
