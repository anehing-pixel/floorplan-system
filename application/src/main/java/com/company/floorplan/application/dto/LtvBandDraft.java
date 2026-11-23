package com.company.floorplan.application.dto;

import java.math.BigDecimal;

/**
 * 一条分档带（USED R3.2，V57 {@code ltv_policy_bands} 的入参形状）。
 *
 * <p>字段与列一一对应，不做「友好命名」：改档的人是风控，他手上拿的是 D08 §4.3 那张表，
 * 中间再套一层别名只会让「哪个维度没填」变成猜谜。</p>
 *
 * <p><b>维度留空＝该维不设限</b>（V57 列注释原话），不是「该维为 0」。
 * 这条语义在 {@code LtvBand.intervalOk} 里实现，本类只负责把 {@code null} 原样带过去：
 * 如果在 DTO 上把空值补成 0，就等于给每一条带都加了一个「車龄 &gt;= 0」的隐藏条件，
 * 而 0 与「不设限」在缺失数据（{@code mileage=null}）时的判定结果完全不同。</p>
 *
 * @param priority               命中顺序，小者优先（必填，&gt; 0；同政策内不得重复）
 * @param ageBandMinMonths       車龄带下限（月，含）
 * @param ageBandMaxMonths       車龄带上限（月，不含）
 * @param mileageBandMinKm       里程带下限（km，含）
 * @param mileageBandMaxKm       里程带上限（km，不含）
 * @param repairHistoryIn        修復歴集合（CSV，如 {@code NONE,MINOR}）
 * @param gradeMin               評価点下限（含）
 * @param gradeMax               評価点上限（不含）
 * @param inspectionRemainingMin 車検残存下限（月）
 * @param modelHeat              车型热度单值（{@code HIGH/NORMAL/LOW/UNKNOWN}）；空＝不设限
 * @param dealerGradeIn          车商等级集合（CSV）
 * @param maxRatio               该档上限成数（必填，&gt; 0）
 * @param rateAddonBps           利率加点（基点）
 * @param breachThreshold        该档突破预警线（0〜100 的百分比数值）
 * @param note                   备注
 */
public record LtvBandDraft(
        Integer priority,
        Integer ageBandMinMonths,
        Integer ageBandMaxMonths,
        Integer mileageBandMinKm,
        Integer mileageBandMaxKm,
        String repairHistoryIn,
        BigDecimal gradeMin,
        BigDecimal gradeMax,
        Integer inspectionRemainingMin,
        String modelHeat,
        String dealerGradeIn,
        BigDecimal maxRatio,
        Integer rateAddonBps,
        BigDecimal breachThreshold,
        String note) {
}
