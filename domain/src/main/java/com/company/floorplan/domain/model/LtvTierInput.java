package com.company.floorplan.domain.model;

import java.math.BigDecimal;

/**
 * LTV 取带输入（USED R3.2，{@link LtvBand#matches(LtvTierInput)} 的入参，D08 §4.1 七维）。
 *
 * <p><b>七个维度全部允许 {@code null}，且 {@code null} 有明确语义：这个维度没有可信观测</b>。
 * D08 §4.4 末段把这条写成硬规则——「输入缺维度时该带不命中（不是视为满足）」，
 * 因为「缺数据 ⟹ 视为满足」会让最脏的车拿到最宽的档位（约束里程的带全都过），
 * 正好与保守方向相反。所以带集合若要覆盖这类车，必须显式把该维留 NULL（= 不设限）。</p>
 *
 * <p><b>调用方在进入本记录之前必须做的三件规整</b>（都在 application 层，理由见各字段）：</p>
 * <ol>
 *   <li>里程来源不可信（{@code mileage_source=UNKNOWN}）⟹ {@code mileageKm} 传 null，
 *       而不是传主数据里那个没出处的数。D08 §4.1 要求「按上一档处理」，
 *       在没有档位网格的纯函数里表达不了「上一档」，但 null 的效果等价且更强：
 *       凡是约束里程的带都不命中，只剩里程不设限的保守带（§4.3 表的 60〜90 带）能落。</li>
 *   <li>無評価点（相谈车）⟹ {@code grade} 传 null（§4.1「只允许命中 grade_min IS NULL 的带」）。</li>
 *   <li>修復歴 {@link RepairHistory#UNKNOWN} <b>照原值传</b>，不在这里折叠成 MAJOR——
 *       折叠点唯一在 {@link LtvBand#matches}（见那里的注释），两处都折就会出现
 *       「模拟用原值、放款用折叠值」的两套结论。</li>
 * </ol>
 *
 * @param ageMonths                 车龄（自然月，不足一月不计，D08 §4.1 与 Q14 的日历口径一致）
 * @param mileageKm                 里程（km）；不可信时传 null，见上
 * @param repairHistory             修復歴；UNKNOWN 由带匹配按 MAJOR 从严处理
 * @param grade                     評価点；相谈车无評価点时传 null
 * @param inspectionRemainingMonths 車検残存月数
 * @param modelHeat                 车型热度；本切片无推导阈值 ⟹ 通常为 null（见 {@link ModelHeat}）
 * @param dealerGrade               车商等级（V57 {@code dealer_grade_in} 的比对面）
 */
public record LtvTierInput(Integer ageMonths, Integer mileageKm, RepairHistory repairHistory,
                           BigDecimal grade, Integer inspectionRemainingMonths, ModelHeat modelHeat,
                           String dealerGrade) {

    /** 维度名 → 是否已提供，供 {@link LtvBand#unmetDimensions} 与试算响应复用同一份命名。 */
    public java.util.List<String> missingDimensions() {
        java.util.List<String> missing = new java.util.ArrayList<>(4);
        if (ageMonths == null) {
            missing.add("age_months");
        }
        if (mileageKm == null) {
            missing.add("mileage_km");
        }
        if (repairHistory == null) {
            missing.add("repair_history");
        }
        if (grade == null) {
            missing.add("grade");
        }
        if (inspectionRemainingMonths == null) {
            missing.add("inspection_remaining_months");
        }
        if (modelHeat == null) {
            missing.add("model_heat");
        }
        if (dealerGrade == null || dealerGrade.isBlank()) {
            missing.add("dealer_grade");
        }
        return missing;
    }
}
