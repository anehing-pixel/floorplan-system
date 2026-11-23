package com.company.floorplan.domain.model;

/**
 * 里程观测样本（{@code vehicle_units} 主数据与各证据表之间的<b>比较输入</b>，本身不落库）。
 *
 * <p>D06 §2.3 的判定要同时看到「主数据现在的值与来源」和「这次新拿到的值与来源」，
 * 而这两份事实来自不同的表（{@code vehicle_units.mileage} /
 * {@code auction_lots.mileage_km} / {@code vehicle_condition_reports.odometer_km} /
 * {@code release_receipts.odometer_km}），列名三个样。本 record 是它们的公共形状：
 * 策略只认 {@link MileageSample}，不需要知道数据从哪张表来——这样 R6 接 WMS 回执时
 * 只要多构造一个样本，{@code MileageResolutionPolicy} 一行不用改。</p>
 *
 * <p>不落库是刻意的：库里每处里程都已经和自己那张表的业务含义绑定了（票面里程就是票面里程），
 * 再造一张「观测表」等于把同一事实存两份，两份之间还得再对账。</p>
 *
 * @param source     来源；{@code null} 按 {@link MileageSource#UNKNOWN} 处理
 * @param mileageKm  观测值（公里）；{@code null} = 这次没读到数
 * @param capturedOn 采集时点；同优先级来源靠它分先后（D06 §2.4「同优先级取最新」）
 * @param evidenceRef 证据引用（票号 / 快照号 / 回执号），写进差异工单与审计，便于回查
 */
public record MileageSample(MileageSource source, Integer mileageKm, java.time.LocalDate capturedOn,
                            String evidenceRef) {

    /** 主数据侧的样本（{@code vehicle_units.mileage} + {@code mileage_source} + {@code mileage_captured_on}）。 */
    public static MileageSample ofMaster(Integer mileageKm, String source, java.time.LocalDate capturedOn) {
        return new MileageSample(MileageSource.from(source), mileageKm, capturedOn, "VEHICLE_UNIT");
    }

    /** 该样本能否作为里程结论被采纳：UNKNOWN 或没数都不行（D06 §2.4 末段）。 */
    public boolean usable() {
        return mileageKm != null && source != null && source != MileageSource.UNKNOWN;
    }

    /** 有效优先级：{@code null} 来源排最后（比 UNKNOWN 还不可信，因为连来源都没记）。 */
    public int priority() {
        return source == null ? Integer.MAX_VALUE : source.priority();
    }
}
