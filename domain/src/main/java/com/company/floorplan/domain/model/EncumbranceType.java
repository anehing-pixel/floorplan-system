package com.company.floorplan.domain.model;

/**
 * 权利瑕疵类型（USED P-7 红线，{@code inspection_certificates.encumbrance_status} /
 * {@code certificate_custody_events.encumbrance_type} /
 * {@code collateral_registration_batches.encumbrance_conclusion}）。
 *
 * <p>取值集 = D00 §8.2，落库为 V52 的 {@code ck_cert_encumbrance} 与
 * {@code ck_cce_encumbrance}、V54 的 {@code ck_crb_encumbrance}（三处同一份六值）。</p>
 *
 * <p><b>除 {@link #NONE} 外全部硬阻断设质/放款</b>（D03 §2.5 I3 / P-7），
 * {@link #UNKNOWN} 按<b>不通过</b>处理——「没查」不能当成「没问题」，
 * 与 R1 合规核查 UNKNOWN 不折叠成通过是同一条纪律。</p>
 *
 * <p>{@code null}（该列从未回填）与 {@link #UNKNOWN}（查了但查不清）是两种事实，
 * 数据层不合并（V50 对 mileage_source 的注释同款理由）；判定侧两者都阻断，
 * 但审计要能分开「没查」和「查不清」。</p>
 */
public enum EncumbranceType {

    /** 无任何瑕疵——唯一放行值。 */
    NONE,

    /** 抵当権（银行/厂家对同一车已设抵押）。 */
    MORTGAGE,

    /** 所有权留保（卖家的所有权保留买卖未付清）。 */
    OWNERSHIP_RESERVATION,

    /** 差押/假处分（法院查封）。 */
    ATTACHMENT,

    /** 盗難届（报案失窃车辆）。 */
    THEFT_REPORT,

    /** 核查过但结论不明：阻断，且不因为「不明」而放宽。 */
    UNKNOWN;

    /**
     * 是否放行。
     *
     * <p>判定侧统一读这个谓词，不在业务代码里写 {@code type != NONE}：
     * 后者一旦出现 {@code type != UNKNOWN} 这种「反向」写法，红线就从「只放 NONE」
     * 滑成了「只拦某一种」。集中一处才审得出来。</p>
     */
    public boolean isClear() {
        return this == NONE;
    }

    /** {@code null}/空白返回 {@code null}（＝从未回填）；未知字面量抛出。 */
    public static EncumbranceType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("未知的权利瑕疵类型: " + raw, ex);
        }
    }
}
