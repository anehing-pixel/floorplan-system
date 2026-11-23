package com.company.floorplan.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 估值录入命令（USED R3.1，D07 §7.2 的 {@code POST /api/valuations}）。
 *
 * <p><b>枚举词一律用字符串</b>（同 {@link RecordConditionReportCmd} 的理由）：
 * 非法取值必须是 400 {@code VALID_001} 并说清合法值域，而不是被 Jackson 反序列化成一堆
 * {@code null} 后在业务层变成「参数缺失」——那是两种完全不同的排障方向。</p>
 *
 * <p><b>没有 {@code prudentRule} 字段</b>，这是有意的：D08 §3.2 规定审慎值算法默认取小，
 * 加权只能由政策侧改写，不能由每次录入请求临时挑一个对自己有利的算法。
 * 算法读自配置键 {@code valuation.default-prudent-rule}。</p>
 *
 * @param vin             車台番号（必填，且必须已在账）
 * @param purpose         估值用途（必填，{@code ADVANCE_BASE} / {@code PERIODIC_RECHECK}
 *                        / {@code EVENT_RECHECK} / {@code DISPOSAL_PRICING}）
 * @param sources         输入源清单（至少一条；{@code INTERNAL_DEGRADED} 一律拒绝，D07 §7.2 / U-12）
 * @param valuationDate   估值日；省略取服务器业务日
 * @param validUntil      有效截止日；省略时按配置键 {@code valuation.valid-days} 推导
 * @param coSignedBy      第二签认人；<b>仅当 sources 含 {@code MANUAL_OVERRIDE} 时必填</b>，
 *                        且必须不同于当前操作人（D08 §3.4 / V55 {@code ck_val_override_pair}）
 * @param auctionLotId    溯源落札（逻辑引用 V61）
 * @param marketSnapshotId 溯源行情（逻辑引用 V56）
 * @param appraisalProvider 查定机构
 * @param appraisalNo       查定书番号
 * @param reportPath        评估报告附件路径
 * @param reportDigest      附件摘要
 * @param valuationKey      自带幂等键（{@code VAL-{vin}-{yyyymmdd}-{seq}}）；省略由服务端生成
 */
public record RecordValuationCmd(
        String vin,
        String purpose,
        List<SourceLine> sources,
        LocalDate valuationDate,
        LocalDate validUntil,
        String coSignedBy,
        Long auctionLotId,
        Long marketSnapshotId,
        String appraisalProvider,
        String appraisalNo,
        String reportPath,
        String reportDigest,
        String valuationKey) {

    /**
     * 一条输入源。
     *
     * <p>{@code value} 用包装类型：0 是「源报了零」这个<b>事实</b>（V55 头注），
     * 而「没报」必须由整行缺席表达。用 {@code long} 会把两者混成一个值，
     * 结果是一台没接入行情源的车审慎值直接归零、全量拒付，且库里看不出异常。</p>
     *
     * @param source   {@code ValuationSource} 词
     * @param value    该源报的价（整数日元，&gt;= 0）
     * @param ref      源侧引用号（落札票号 / 查定书号 / 行情批次号）
     * @param provider 供应商；人工覆盖类留空由仓储规整成空串（{@code uk_val_input} 的 NULL 边界）
     * @param asOf     报价口径日；省略按估值日
     * @param weight   权重（仅配置为 WEIGHTED 时生效）
     * @param rank     同源多行的次序
     */
    public record SourceLine(String source, Long value, String ref, String provider,
                             LocalDate asOf, BigDecimal weight, Integer rank) {
    }
}
