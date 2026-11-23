package com.company.floorplan.domain.model;

import java.util.Locale;

/**
 * 估值来源（USED R3.1，{@code vehicle_valuations.primary_source} 与
 * {@code valuation_inputs.source} 共用取值域）。
 *
 * <p>取值集以库内 CHECK 为准（V55 {@code ck_val_source} / {@code ck_input_source}），
 * 即 D00 §8.5 定源的五值；D08 §3.1 那张表是同一枚举的说明文字，不是另一套名字。</p>
 *
 * <p><b>本枚举带两枚判定属性，这是有意的</b>：「这个源能不能作放款基准」
 * 与「这个源算不算自动源」是两条会被闸门引用的结论，写在枚举上等于把它们和取值一起冻结。
 * 反面做法是在各处写 {@code if (source == INTERNAL_DEGRADED)}——
 * 那样每新增一个源就要把所有 if 串一遍，漏一处就是一个放款口子
 * （AGENTS §12「用可配置参数放宽安全策略」的姊妹形状：用散落比较放宽）。</p>
 *
 * <ul>
 *   <li>{@link #advanceBaseEligible()}——{@code INTERNAL_DEGRADED} 为 {@code false}（D03 §2.4 I3）。
 *       这条不靠调用方自觉：{@code PrudentValueCalculator} 与放款闸门都读它。</li>
 *   <li>{@link #auto()}——人工覆盖与系统兜底<b>不是</b>市场报的价，
 *       所以它们不进 D08 §3.4「覆盖上限 = 自动源最大值 × 115%」的分母。</li>
 * </ul>
 */
public enum ValuationSource {

    /** 落札价：场内真实成交，权威度最高（有支付事实）。 */
    AUCTION_WON_PRICE(true, true),

    /** 第三方查定书：独立机构出价。 */
    THIRD_PARTY_APPRAISAL(true, true),

    /** 相場行情：{@code valuation_market_snapshots} 的群体统计量。 */
    MARKET_MATRIX(true, true),

    /** 人工覆盖：唯一的「单源也能放款」例外，前提是二人签认（D08 §3.2 退化表末行）。 */
    MANUAL_OVERRIDE(true, false),

    /** 外部源全部不可得时的系统兜底推算：只进监控层，<b>不得</b>作放款基准（D03 I3 / 闸门 G4）。 */
    INTERNAL_DEGRADED(false, false);

    private final boolean advanceBaseEligible;
    private final boolean auto;

    ValuationSource(boolean advanceBaseEligible, boolean auto) {
        this.advanceBaseEligible = advanceBaseEligible;
        this.auto = auto;
    }

    /** 能否作为放款基准（ADVANCE_BASE）的输入。 */
    public boolean advanceBaseEligible() {
        return advanceBaseEligible;
    }

    /** 是否市场/机构报出的自动源（人工覆盖上限的分母只算这类）。 */
    public boolean auto() {
        return auto;
    }

    /**
     * 该源对应主表四个「来源事实」列中的哪一列。
     *
     * <p>V55 把四个源各存一列（供闸门直读），同时 {@code valuation_inputs} 存全部明细。
     * 映射写在枚举上，是为了让「新增一个源就要决定它落哪列」这件事在编译期被问到，
     * 而不是等到某列一直是 NULL、报表却把它当成「这台车没有这个源」。</p>
     *
     * @return 列名；{@code INTERNAL_DEGRADED} 没有对应列（它不是来源事实，是结论的降级标记），返回 {@code null}
     */
    public String primaryColumn() {
        switch (this) {
            case AUCTION_WON_PRICE:
                return "auction_won_price";
            case THIRD_PARTY_APPRAISAL:
                return "appraised_value";
            case MARKET_MATRIX:
                return "market_price";
            case MANUAL_OVERRIDE:
                return "manual_value";
            case INTERNAL_DEGRADED:
            default:
                return null;
        }
    }

    /**
     * 解析入参词。
     *
     * <p>非法词抛 {@link IllegalArgumentException} 由用例层翻成 {@code VALID_001}；
     * 这里<b>不</b>设 UNKNOWN 兜底值：D00 §8.5 的取值域就没有「未知来源」，
     * 造一个出来等于让「填错源名」变成一条合法的估值记录。</p>
     */
    public static ValuationSource from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("估值来源不能为空");
        }
        return ValuationSource.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
