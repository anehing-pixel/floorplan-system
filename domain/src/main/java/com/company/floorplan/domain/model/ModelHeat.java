package com.company.floorplan.domain.model;

import java.util.Locale;

/**
 * 车型热度（USED R3.2 取带维度之一，{@code ltv_policy_bands.model_heat} 取值集 =
 * V57 {@code ck_band_heat}）。
 *
 * <p><b>本切片不推导这个值</b>。D08 §4.1 把它的权威源定为「由
 * {@code valuation_market_snapshots.trend_30d + sample_count} 推导」，
 * 而推导要的两个阈值（多少样本算「有热度」、涨跌多少算 HIGH）在 D06 §16 全表里<b>没有对应键</b>，
 * 也没有任何一份文档给出数值。自己定一个数就是把「分档数值 [待定源 D-06]」
 * 变成「实现方随手发明」，所以这里只把取值域定义清楚，
 * 由 {@code LtvTierInput} 携带（可为 {@code null} = 未推导），
 * 带集合若不约束这一维（{@code model_heat IS NULL}，V57 注释：NULL = 该维不设限）就能正常命中。</p>
 *
 * <p>登记为缺口：阈值定源后在 {@code AdvanceCeilingQueryService} 里补一段推导，
 * 不影响已放款笔（D08 §2 额度层不重算）。</p>
 */
public enum ModelHeat {

    /** 热销：同型车成交活跃、趋势向上。 */
    HIGH,

    /** 正常。 */
    NORMAL,

    /** 冷门：折价与滞库风险高。 */
    LOW,

    /**
     * 未采集／样本不足。
     *
     * <p>D08 §4.1 建议「UNKNOWN 按 LOW 处理（保守）」。<b>本切片不在输入侧折叠</b>：
     * 折叠会让带集合里 {@code model_heat='UNKNOWN'} 这一档永远命中不到（V57 的取值集
     * 明确包含 UNKNOWN，说明设计允许单独给它一档），保守方向变成实现方说了算。
     * 保守由带集合表达——要「UNKNOWN 与 LOW 同档」就在 {@code model_heat_in} 里写两个值。</p>
     */
    UNKNOWN;

    /** 解析入参词；非法词由用例层翻成 {@code VALID_001}。 */
    public static ModelHeat from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("车型热度不能为空");
        }
        return ModelHeat.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
