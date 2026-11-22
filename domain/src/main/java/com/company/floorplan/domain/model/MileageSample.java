package com.company.floorplan.domain.model;

import java.time.LocalDate;

/**
 * 里程观测样本（{@code MileageResolutionPolicy} 的输入，D06 §2.4 的 {@code MileageSample}）。
 *
 * <p>一条样本＝「某来源在某时点给出的一个读数」。三个字段缺一不可：
 * 光有数值排不出优先级，光有来源判不出同档先后。</p>
 *
 * <p>只读 VO，无业务判断（判定全在策略里），故不设 setter——
 * 防止"改一下来源就想让另一条胜出"这种绕过排序的写法。</p>
 */
public final class MileageSample {

    /** 观测来源。 */
    private final MileageSource source;

    /** 里程读数（km）；{@code UNKNOWN} 样本必须为 {@code null}（V50 的 {@code ck_vu_mileage_unknown}）。 */
    private final Integer mileageKm;

    /** 采集时点（同优先级取最新者）。 */
    private final LocalDate capturedOn;

    /**
     * 构造观测样本。
     *
     * @param source     来源
     * @param mileageKm  读数
     * @param capturedOn 采集时点
     */
    public MileageSample(MileageSource source, Integer mileageKm, LocalDate capturedOn) {
        this.source = source;
        this.mileageKm = mileageKm;
        this.capturedOn = capturedOn;
    }

    public MileageSource getSource() {
        return source;
    }

    public Integer getMileageKm() {
        return mileageKm;
    }

    public LocalDate getCapturedOn() {
        return capturedOn;
    }
}
