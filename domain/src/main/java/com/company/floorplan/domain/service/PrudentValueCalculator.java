package com.company.floorplan.domain.service;

import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.PrudentValueRule;
import com.company.floorplan.domain.model.ValuationInput;
import com.company.floorplan.domain.model.ValuationPurpose;
import com.company.floorplan.domain.model.ValuationSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 审慎值计算（USED R3.1，D08 §3.2，纯函数）。
 *
 * <p><b>本类是「放款基准从哪来」的唯一答案</b>。现状链①用
 * {@code invoice_amount × advance_ratio} 直接得出放款额（D08 §1 点名的 LEGACY 污染），
 * 换成多源审慎值之后仍然可能出现的漏洞是「各处各算一遍 MIN」——
 * 于是同一个 VIN 在建账、放款、报表三处得到三个审慎值。所有算审慎值的路径都必须走这里。</p>
 *
 * <p><b>核心裁决（2026-11-22 D13 §4.4 WU2-06，取保守侧）</b>：
 * D08 §3.1 写「ADVANCE_BASE 至少 2 个可用源」，§3.2 退化表又把「仅落札 / 仅查定」判成可放款，
 * 两句话相隔 15 行、都能被引用。现统一为
 * <b>除 {@code MANUAL_OVERRIDE} + 二人签认之外，单源一律不可作 {@code ADVANCE_BASE}</b>
 * （按「无有效放款基准估值」处理，{@code VALU_001}，并告警）。
 * 这条<b>没有开关</b>：D06 §16 的 {@code valuation.require-second-source-for-advance=false}
 * 与本裁决直接相反，接线就等于给已裁决的硬策略留一个可配置旁路
 * （AGENTS §8 对 Q21 {@code relaxedDealers} 的原话），因此不实现该键，
 * 差异登记在 {@code PROGRESS.md}。</p>
 *
 * <p><b>金额全程 long / {@link BigDecimal}</b>（AGENTS §6.3）：
 * WEIGHTED 走「先 Σ(v·w) ÷ Σw、再一次向下取整」。若先对每台四舍五入再求和，
 * 结果会偏高——误差方向正好和「审慎」相反，这种错不会被任何单测的「大致对」发现，
 * 所以 {@link #weighted} 只有一个除法、且显式 {@link RoundingMode#FLOOR}。</p>
 */
public final class PrudentValueCalculator {

    private PrudentValueCalculator() {
    }

    /**
     * 拒因。枚举止步于「为什么不能用」，HTTP 码与文案由 {@link #code()} 定源（D00 §9 表 A）。
     *
     * <p>做成枚举而不是直接返回 {@link ErrorCode}：同一个 ErrorCode 可能来自不同判定
     * （{@code VALU_001} 既是「一个源都没有」也是「只有单源」），审计要能分辨是哪一种，
     * 而文案「无有效估值」对后一种是不准确的——运维看到它会去找「为什么没录估值」，
     * 真相却是「录了但只有一个源」。</p>
     */
    public enum Reject {
        /** 没有任何可参与计算的输入。 */
        NO_SOURCE(ErrorCode.VALU_001, "无任何估值输入源"),
        /** 只有 {@code INTERNAL_DEGRADED}：外部全不可得的兜底推算，不得作放款基准（D03 §2.4 I3 / 闸门 G4）。 */
        DEGRADED_ONLY(ErrorCode.VALU_003, "仅系统降级推算，不得作为放款基准"),
        /** 单源且不是「人工覆盖 + 二人」——D08 §3.2 裁决段的唯一硬拒形态。 */
        SINGLE_SOURCE_NOT_MANUAL(ErrorCode.VALU_001, "仅单一来源，不足以支撑放款基准"),
        /** 人工覆盖缺第二人签认（D08 §3.4）。 */
        MANUAL_UNSIGNED(ErrorCode.VALU_004, "人工覆盖未经第二人签认"),
        /** 各源都有值但审慎值为 0：不得置 ACTIVE（D00 §9 表 A 的 VALU_006 行）。 */
        ZERO_PRUDENT(ErrorCode.VALU_006, "审慎值为 0，估值不得生效"),
        /** 处置定价只有人工值：处置必须面对市场（D08 §3.4 禁止行）。 */
        MANUAL_ONLY_FOR_DISPOSAL(ErrorCode.DSPO_002, "人工覆盖不得作为处置定价的唯一来源");

        private final ErrorCode code;
        private final String text;

        Reject(ErrorCode code, String text) {
            this.code = code;
            this.text = text;
        }

        public ErrorCode code() {
            return code;
        }

        public String text() {
            return text;
        }
    }

    /**
     * 计算结论。
     *
     * @param prudentValue       审慎值（整数日元）；{@link Reject#NO_SOURCE} 时为 0
     * @param primarySource      结论主源；退化表「取胜者所属源」，人工覆盖时是 {@code MANUAL_OVERRIDE}
     * @param eligibleSourceCount 参与计算的可用源数量（&gt;=1 才有意义）
     * @param advanceBaseEligible 能否作 {@code ADVANCE_BASE}：全部拒因为空才为 true
     * @param rejects            全部拒因（可能多条并列，例如「单源 + 值为 0」）
     * @param weightedApplied    本次是否真的按加权算（MIN 规则下权重被忽略，响应要如实说明）
     */
    public record Decision(long prudentValue, ValuationSource primarySource, int eligibleSourceCount,
                           boolean advanceBaseEligible, List<Reject> rejects, boolean weightedApplied) {

        /** 是否是一条干净的结论。 */
        public boolean clean() {
            return rejects.isEmpty();
        }

        /** 第一个拒因对应的错误码（无拒因时返回 {@code null}）。 */
        public ErrorCode firstCode() {
            return rejects.isEmpty() ? null : rejects.get(0).code();
        }
    }

    /**
     * 计算审慎值。
     *
     * @param rule               审慎规则；来自配置键，<b>不接受请求体传入</b>（D08 §3.2 加权限制）
     * @param purpose            估值用途，决定哪些硬校验生效（{@link ValuationPurpose#requiresAdvanceBaseRule()}）
     * @param inputs             已登记的输入源；{@code null} 元素忽略
     * @param manualOverrideSigned 人工覆盖是否已二人签认（{@code overriddenBy} 与不同的 {@code coSignedBy}）
     * @return 结论，永不为 {@code null}；调用方按 {@link Decision#clean()} 决定是否落 ACTIVE
     */
    public static Decision calculate(PrudentValueRule rule, ValuationPurpose purpose,
                                     List<ValuationInput> inputs, boolean manualOverrideSigned) {
        List<ValuationInput> present = new ArrayList<>();
        if (inputs != null) {
            for (ValuationInput in : inputs) {
                if (in != null) {
                    present.add(in);
                }
            }
        }
        if (present.isEmpty()) {
            return new Decision(0L, null, 0, false, List.of(Reject.NO_SOURCE), false);
        }

        // 降级源单独一档：它不是"市场报的价"，进 MIN 集合会把外部不可得伪装成"市场价真的这么低"。
        List<ValuationInput> eligible = new ArrayList<>();
        for (ValuationInput in : present) {
            if (in.source().advanceBaseEligible()) {
                eligible.add(in);
            }
        }
        if (eligible.isEmpty()) {
            // 全仓只有 INTERNAL_DEGRADED：VALU_003（闸门 G4 的判定），主源照实标降级。
            return new Decision(0L, ValuationSource.INTERNAL_DEGRADED, 0, false,
                    List.of(Reject.DEGRADED_ONLY), false);
        }

        boolean weighted = rule == PrudentValueRule.WEIGHTED;
        long value = weighted ? weighted(eligible) : minOf(eligible);
        ValuationSource primary = weighted ? weightedPrimary(eligible, value) : minWinner(eligible, value);

        List<Reject> rejects = new ArrayList<>(2);
        if (value == 0L) {
            rejects.add(Reject.ZERO_PRUDENT);
        }
        boolean manualOnly = eligible.size() == 1 && eligible.get(0).source() == ValuationSource.MANUAL_OVERRIDE;
        if (purpose != null && purpose.requiresAdvanceBaseRule()) {
            if (eligible.size() == 1 && !manualOnly) {
                // 仅落札 / 仅查定 / 仅相場：§3.2 退化表在 2026-11-22 之后统一判不可作基准。
                rejects.add(Reject.SINGLE_SOURCE_NOT_MANUAL);
            } else if (manualOnly && !manualOverrideSigned) {
                rejects.add(Reject.MANUAL_UNSIGNED);
            }
        }
        if (purpose == ValuationPurpose.DISPOSAL_PRICING && manualOnly) {
            rejects.add(Reject.MANUAL_ONLY_FOR_DISPOSAL);
        }
        return new Decision(value, primary, eligible.size(), rejects.isEmpty(),
                List.copyOf(rejects), weighted);
    }

    /**
     * {@code MIN_OF_SOURCES}：全部可用源取小。
     *
     * <p>0 参与取小（源报了 0 是事实，不是缺失），由 {@link Reject#ZERO_PRUDENT} 拦住生效。</p>
     */
    public static long minOf(List<ValuationInput> eligible) {
        long min = Long.MAX_VALUE;
        for (ValuationInput in : eligible) {
            if (in.value() < min) {
                min = in.value();
            }
        }
        return min == Long.MAX_VALUE ? 0L : min;
    }

    /**
     * {@code WEIGHTED}：{@code floor( Σ(v_i × w_i) / Σ w_i )}，权重和自动归一（D08 §3.2 公式）。
     *
     * <p>权重缺失（全为 {@code null}）时按<b>等权</b>处理：公式的分母是 {@code Σ w_i}，
     * 一台都没给权重时等价于每台权重相同，这是公式自己的退化，不是发明默认值。
     * 权重和为 0（有人显式填了 0）时无法归一 ⟹ 返回 0，由 {@link Reject#ZERO_PRUDENT} 拒生效，
     * 不在这里抛异常——domain 层不碰 {@code BusinessException} 以外的控制流约定，
     * 且单测要能直接断言「权重 0 ⟹ 不是一条 ACTIVE 结论」。</p>
     */
    public static long weighted(List<ValuationInput> eligible) {
        BigDecimal sumWeight = BigDecimal.ZERO;
        BigDecimal sumProduct = BigDecimal.ZERO;
        int counted = 0;
        for (ValuationInput in : eligible) {
            BigDecimal w = in.weight();
            if (w == null) {
                continue;
            }
            sumWeight = sumWeight.add(w);
            sumProduct = sumProduct.add(BigDecimal.valueOf(in.value()).multiply(w));
            counted++;
        }
        if (counted == 0) {
            // 等权退化：Σ(v·1)/n。
            long sum = 0L;
            for (ValuationInput in : eligible) {
                sum += in.value();
            }
            return BigDecimal.valueOf(sum)
                    .divide(BigDecimal.valueOf(eligible.size()), 0, RoundingMode.FLOOR)
                    .longValueExact();
        }
        if (sumWeight.signum() <= 0) {
            return 0L;
        }
        return sumProduct.divide(sumWeight, 0, RoundingMode.FLOOR).longValueExact();
    }

    /**
     * MIN 的主源：取胜者所属源（§3.2 退化表第一列的「取胜者」）。
     *
     * <p><b>并列时的次序是源权威度，不是入参顺序</b>：D08 §3.1 的权威度列
     * （落札 &gt; 查定 &gt; 相場 &gt; 人工）就是 {@link ValuationSource} 的声明序，
     * 用它决胜 ⟹ 同一批输入无论怎么排列都得到同一个 primary_source。
     * 用入参序会让「谁先调 API」决定审计口径，那是不可复现的结果。</p>
     */
    private static ValuationSource minWinner(List<ValuationInput> eligible, long min) {
        ValuationSource winner = null;
        for (ValuationInput in : eligible) {
            if (in.value() != min) {
                continue;
            }
            if (winner == null || in.source().ordinal() < winner.ordinal()) {
                winner = in.source();
            }
        }
        return winner;
    }

    /** 加权的主源：取「等于结果值」的源中权威度最高者；没有源等于结果时取权重最大的源。 */
    private static ValuationSource weightedPrimary(List<ValuationInput> eligible, long result) {
        ValuationSource exact = minWinner(eligible, result);
        if (exact != null) {
            return exact;
        }
        // 加权结果通常落在各源之间，不等于任何一个源——此时"主源"只能是权重最大那个。
        ValuationSource best = null;
        BigDecimal bestWeight = null;
        for (ValuationInput in : eligible) {
            BigDecimal w = in.weight() == null ? BigDecimal.ZERO : in.weight();
            if (bestWeight == null || w.compareTo(bestWeight) > 0
                    || (w.compareTo(bestWeight) == 0 && in.source().ordinal() < best.ordinal())) {
                bestWeight = w;
                best = in.source();
            }
        }
        return best;
    }

    /**
     * 自动源的最大值——D08 §3.4「覆盖不得超过可用自动源最大值的 115%」的分母。
     *
     * <p>只算 {@link ValuationSource#auto()} 的源：人工值与降级推算不是市场报的价，
     * 让它们进分母等于允许「用上一次的人工覆盖撑高这一次的人工覆盖」，
     * 115% 上限会随覆盖次数复利放大，那是这条约束最容易被绕开的方式。</p>
     *
     * @return 没有自动源时返回 {@code null}（⟹ 上限无法计算，纯人工覆盖走二人签认那条唯一例外）
     */
    public static Long maxAutoSourceValue(List<ValuationInput> inputs) {
        Long max = null;
        if (inputs == null) {
            return null;
        }
        for (ValuationInput in : inputs) {
            if (in == null || !in.source().auto()) {
                continue;
            }
            if (max == null || in.value() > max) {
                max = in.value();
            }
        }
        return max;
    }

    /**
     * 人工覆盖允许的上限：{@code FLOOR(maxAuto × uplift)}。
     *
     * <p>向下取整而不是四舍五入：上限本身是「不得超过」的界，
     * 四舍五入会把界抬高半日元，边界样本单测（D08 §10 {@code manualOverrideCappedAt115Percent}）
     * 就会在 1 日元上翻脸。</p>
     */
    public static long overrideCeiling(long maxAuto, BigDecimal upliftRatio) {
        if (upliftRatio == null || upliftRatio.signum() <= 0) {
            // 没有正的倍数就没有"允许抬高"这件事：上限退化为自动源本身。
            return maxAuto;
        }
        return BigDecimal.valueOf(maxAuto).multiply(upliftRatio)
                .setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    /** 按源汇总（响应与审计展示用），保持 {@link ValuationSource} 的声明序。 */
    public static Map<ValuationSource, Long> bySource(List<ValuationInput> inputs) {
        Map<ValuationSource, Long> map = new EnumMap<>(ValuationSource.class);
        if (inputs != null) {
            for (ValuationInput in : inputs) {
                if (in != null) {
                    map.put(in.source(), in.value());
                }
            }
        }
        return map;
    }
}
