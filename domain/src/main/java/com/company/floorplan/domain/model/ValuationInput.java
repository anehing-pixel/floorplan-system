package com.company.floorplan.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 一条估值输入（USED R3.1，{@code valuation_inputs}，V55 附表 / D04 §3.12）。
 *
 * <p><b>它是「证据」而不是「判断」</b>：一行只描述「谁、在哪一天、报了多少」，
 * 不含任何「该不该采信」的结论。审慎值必须由这些行复算得出来（D11 A6 验收点），
 * 所以主表那四列来源价只是这一行的直接输入快照，两者同事务写入、不允许只写一边
 * （只写主表 ⟹ 审计问「这个 1,120,000 怎么来的」答不出来；只写明细 ⟹ 闸门每次读都要聚合子表）。</p>
 *
 * <p><b>缺失 ≠ 0</b>：源没报价就连行都不写。V55 头注把这件事说得很直——
 * 用 0 代替「没报」会让 {@code MIN_OF_SOURCES} 把「这个源没接入」读成
 * 「市场认为这台车一文不值」，审慎值直接归零，而它看起来是完全合法的数字。</p>
 *
 * @param source   来源（枚举名即库值，见 {@link ValuationSource}）
 * @param value    该源报的价（整数日元，库内 {@code ck_input_value} 挡负数）
 * @param ref      源侧引用号（落札票号 / 查定书号 / 行情批次号）
 * @param provider 供应商或机构；{@code null} 时由仓储按库内唯一索引写成空串（见下）
 * @param asOf     报价口径日，可与落库日不同（行情常有前一交易日）
 * @param weight   权重，仅 {@link PrudentValueRule#WEIGHTED} 用；MIN 规则留 {@code null}
 * @param rank     同一源多行时的次序（D04 §3.12），单行留 {@code null}
 */
public record ValuationInput(ValuationSource source, long value, String ref, String provider,
                            LocalDate asOf, BigDecimal weight, Integer rank) {

    /**
     * {@code (source, provider, as_of)} 上挂着 V55 的 {@code uk_val_input} 唯一索引，
     * 而 Postgres 里 NULL 彼此不等 ⟹ provider 留 NULL 能插进两条同 (source, as_of) 的行，
     * 审慎值就随「最后写入」漂移且无痕迹。V55 注释给的处置是：
     * <b>人工类来源统一把 provider 规整成空串</b>，其余源必填 provider。
     * 本方法就是这个规整的唯一落点。
     */
    public ValuationInput normalizedProvider() {
        String p = provider == null ? null : provider.trim();
        if (p != null && p.isEmpty()) {
            p = null;
        }
        if (p == null && source == ValuationSource.MANUAL_OVERRIDE) {
            // 人工覆盖没有外部机构，留 NULL 会让"同一天两次覆盖"绕过唯一索引插成两行。
            p = "";
        }
        return new ValuationInput(source, value, ref, p, asOf, weight, rank);
    }

    /** 该输入是否能进放款基准（{@code INTERNAL_DEGRADED} 不能，D03 §2.4 I3）。 */
    public boolean advanceBaseEligible() {
        return source.advanceBaseEligible();
    }
}
