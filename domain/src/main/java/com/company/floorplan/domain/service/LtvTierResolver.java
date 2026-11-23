package com.company.floorplan.domain.service;

import com.company.floorplan.domain.model.LtvBand;
import com.company.floorplan.domain.model.LtvTierInput;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LTV 分档命中判定（USED R3.2，D08 §4.4，纯函数）。
 *
 * <p><b>命中语义取 D08 §4.4 的方案 ②「priority 首命中制」</b>（该文明确「本文默认」，
 * 数值与语义一并属 <code>[待定源 D-06]</code>，签字前不得改动方向）：
 * 按 priority 升序取首个命中带；<b>不同序的多带命中是设计内结果</b>（§4.3 示例表就是嵌套带），
 * 只有「同 priority 且同时命中」才算歧义并抛 {@code LTV_002}。
 * 之所以不能选方案 ①（区间互斥 + 命中数必须为 1）：那要求把 §4.3 的九个带五个维度全部重写，
 * 而那张表是业务要签的数值；实现方无权为了自己好写而改动待签口径。</p>
 *
 * <p><b>「靠顺序从严」是隐含知识</b>（D08 §4.4 方案②代价栏原话）：后人插一个带、
 * 顺序排错一位，成数就静默变了。所以 {@link Outcome} 把命中集合整个带出来，
 * 用例层负责把它写进审计与响应——一次放款的「为什么是这一档」必须可事后回答。</p>
 *
 * <p><b>不做的事</b>：不在这里取数（维度由 application 层从 {@code vehicle_units} 与估值行组装）、
 * 不算上限金额（{@link UsedAdvanceCeilingCalculator}）、不碰政策行（L5 的判定在政策用例里）。
 * 三件事分开，才能保证试算与放款用的是同一个 resolve。</p>
 */
public final class LtvTierResolver {

    private LtvTierResolver() {
    }

    /** 判定结局。 */
    public enum Status {
        /** 命中一个优先档。 */
        HIT,
        /** 零命中，落入政策行的兜底成数（须计入分档缺口报表，D08 §4.2 L2 / {@code LTV_005} 告警）。 */
        FALLBACK,
        /** 同 priority 多带同时命中：真歧义，必须阻断而不是「取第一条」（D08 §4.2 P-2）。 */
        AMBIGUOUS,
        /** 零命中且政策没有兜底成数：政策覆盖有缺口，resolve 失败（见下）。 */
        NO_TIER
    }

    /**
     * 判定结果。
     *
     * @param status       结局
     * @param band         命中的带；{@link Status#FALLBACK}/{@link Status#NO_TIER}/{@link Status#AMBIGUOUS} 时为 {@code null}
     * @param maxRatio     生效成数（兜底时是政策行的 {@code default_ratio}）
     * @param rateAddonBps 利率加点；兜底时无带可取 ⟹ {@code null}
     * @param breachThreshold 该档突破预警线；兜底档为 {@code null}（监控层按「无阈值」告警）
     * @param hits         全部命中带（按 priority 升序），供审计与试算展示；AMBIGUOUS 时是造成歧义的那几条
     */
    public record Outcome(Status status, LtvBand band, BigDecimal maxRatio, Integer rateAddonBps,
                          BigDecimal breachThreshold, List<LtvBand> hits) {

        public Outcome {
            hits = hits == null ? Collections.emptyList() : List.copyOf(hits);
        }

        public boolean resolved() {
            return status == Status.HIT || status == Status.FALLBACK;
        }

        /** 是否落入兜底档（L2 缺口生效，必须进报表，D08 §5.4「兜底带用于正常业务」是禁止项）。 */
        public boolean usedFallback() {
            return status == Status.FALLBACK;
        }
    }

    /**
     * 方案②算法本体（D08 §4.4 伪码的逐行实现）。
     *
     * @param input         七维输入（各维可为 null，语义见 {@link LtvTierInput}）
     * @param bands         政策生效版本的带集合
     * @param fallbackRatio 政策行的兜底成数，{@code null} = 未签字所以没值
     */
    public static Outcome resolve(LtvTierInput input, List<LtvBand> bands, BigDecimal fallbackRatio) {
        List<LtvBand> matched = new ArrayList<>();
        if (bands != null) {
            for (LtvBand band : bands) {
                if (band != null && band.matches(input)) {
                    matched.add(band);
                }
            }
        }
        if (matched.isEmpty()) {
            if (fallbackRatio != null && fallbackRatio.signum() > 0) {
                return new Outcome(Status.FALLBACK, null, fallbackRatio, null, null, List.of());
            }
            // 零命中 + 无兜底：V57 头注要求"必须抛错、不得返回默认成数、不得回退 0.90 发票公式"。
            // 码位用 LTV_002 而不是 V57 注释里的 LTV_003——D00 §9.1 表 B 是逐码定源，
            // LTV_002 的定义含"resolve() 失败"，LTV_003 专指残值跌破阈值需追加还款。差异已登记 PROGRESS。
            return new Outcome(Status.NO_TIER, null, null, null, null, List.of());
        }
        matched.sort((a, b) -> Integer.compare(a.priority(), b.priority()));
        int top = matched.get(0).priority();
        List<LtvBand> tied = new ArrayList<>();
        for (LtvBand b : matched) {
            if (b.priority() == top) {
                tied.add(b);
            }
        }
        if (tied.size() > 1) {
            // 库里 uk_ltv_band_priority 让这一支进不来；留着是因为政策表也可能被运维直插改坏，
            // 而放款只有几十毫秒的判断窗口（D08 §4.2 L1 的"运行时断言不是多余的"）。
            return new Outcome(Status.AMBIGUOUS, null, null, null, null, tied);
        }
        LtvBand hit = matched.get(0);
        return new Outcome(Status.HIT, hit, hit.maxRatio(), hit.rateAddonBps(), hit.breachThreshold(), matched);
    }
}
