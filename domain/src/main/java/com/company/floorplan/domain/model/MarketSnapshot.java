package com.company.floorplan.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 行情快照（USED R3.3，{@code valuation_market_snapshots}，V56 / D04 §3.13，入站通道 I-12）。
 *
 * <p><b>一行 = 一组车的行情结论，不是一台车的估值</b>：键是车型归一化键
 * {@code model_key}（车型/年式/里程带/修復歴），{@code vehicle_valuations.market_snapshot_id}
 * 指回来，于是「这台车估 1,120,000」能还原成「当时那批同型车行情多少、样本几条、趋势如何」。
 * 没有它，相場源就是一个无源之水的数字（V56 头注）。</p>
 *
 * <p>实体设计成 record：本表<b>只追加不修改</b>（表注释原话）。
 * 给了 setter 就等于允许「把上一期的行情改掉」，而估值行引用的是 id，
 * 改了值就改了历史估值的复算结果——那是 D08 §2「事实层只增不改」的反例。</p>
 *
 * <p>{@code marketPrice} 允许 {@code null} = 当期无成交，这是合法事实；
 * <b>不许用 0 代替</b>（V56 列注释：0 会被审慎值当成「分文不值」）。</p>
 *
 * @param snapshotKey   业务键 {@code MKT-{model_key}-{yyyyMMdd}}（D00 §13.1），库内 UNIQUE
 * @param modelKey      车型归一化键；归一化规则属外部接口约定，本仓不重定义（V56 头注）
 * @param brand         厂牌（展示与人工核对用，不参与键）
 * @param model         车型
 * @param modelYearFrom 年式区间下界（含）；与 to 成对出现，V57/56 的 {@code ck_snapshot_year_pair}
 * @param modelYearTo   年式区间上界（含）
 * @param mileageBand   里程带（如 {@code 3-5万km}）：字符串而非两个整数列，带边界由行情源定义
 * @param repairHistory 修復歴维度；{@code UNKNOWN} 的行情行按 D00 §8.5 不得用于放款
 * @param marketPrice   相场（整数日元）；{@code null} = 当期无成交
 * @param sampleCount   样本条数（0 合法 = 无成交）
 * @param trend30d      30 日趋势，正 = 上涨；窗口固定 30 日
 * @param provider      行情源；{@code null} 时按 {@code ''} 落库（同 {@link ValuationInput} 的键规整理由）
 * @param asOfDate      行情口径日（业务日，非落库时间戳）
 * @param sourceMessageId I-12 入站溯源（跨上下文 = 逻辑引用，无 FK）
 */
public record MarketSnapshot(Long id, String snapshotKey, String modelKey, String brand, String model,
                             Integer modelYearFrom, Integer modelYearTo, String mileageBand,
                             RepairHistory repairHistory, Long marketPrice, int sampleCount,
                             BigDecimal trend30d, String provider, LocalDate asOfDate,
                             Long sourceMessageId) {

    /** 业务键日期后缀的格式：与 D00 §13.1 的 {@code yyyymmdd} 一致，全仓不留第二套写法。 */
    private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    /** {@code (model_key, as_of_date, provider)} 是 V56 的业务去重键，本方法生成对应的 snapshot_key。 */
    public static String buildKey(String modelKey, LocalDate asOfDate) {
        return "MKT-" + modelKey + "-" + (asOfDate == null ? "NA" : asOfDate.format(KEY_DATE));
    }

    /** 是否有成交价（无成交的行只能进趋势，不能进任何取小集合）。 */
    public boolean hasPrice() {
        return marketPrice != null;
    }

    /**
     * 这一行能不能支撑放款基准。
     *
     * <p>两个否决条件都来自列注释，不是本类发明的：
     * ① 无成交（价格 NULL）；② {@code repair_history = UNKNOWN}——
     * V56 原文「UNKNOWN 不得用于放款（取最审慎档），故引用本行的估值必须走降级判定」。
     * 调用方拿到 {@code false} 时的正确动作是<b>不把这一行当相場源</b>，
     * 而不是「用它但打个折」——后者没有任何出处。</p>
     */
    public boolean supportsAdvanceBase() {
        return hasPrice() && repairHistory != RepairHistory.UNKNOWN;
    }
}
