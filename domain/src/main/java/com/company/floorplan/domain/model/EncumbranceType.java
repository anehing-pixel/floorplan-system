package com.company.floorplan.domain.model;

/**
 * 権利瑕疵類型（USED 域 R5.3 的词汇，本包只提供枚举与 DB 约束，阻断判定归放证/质押链路）。
 *
 * <p><b>取值定源</b>：D00 §8.2 的 {@code EncumbranceType} 🆕——
 * {@code NONE · MORTGAGE · OWNERSHIP_RESERVATION(前手所有権留保) · ATTACHMENT(差押) ·
 * THEFT_REPORT(盗難届) · UNKNOWN}，说明列写「除 {@code NONE} 外全部硬阻断（P-7）」。</p>
 *
 * <p><b>三处共用同一集合</b>（双层约束的 DB 半边）：V52 的
 * {@code ck_cert_encumbrance}（{@code inspection_certificates.encumbrance_status}）、
 * V52 的 {@code ck_cce_encumbrance}（{@code certificate_custody_events.encumbrance_type}）、
 * V54 的 {@code ck_crb_encumbrance}（{@code collateral_registration_batches.encumbrance_conclusion}）。
 * 三处刻意写成同一份六值清单，取值增删必须同时改，否则"明细说有问题、批次结论说没有"
 * 会在库里合法存在。</p>
 *
 * <p><b>{@code UNKNOWN} 按不通过处理</b>（V54 注释原文：不得当"没问题"）。
 * 本类不提供 {@code isBlocking()} 之外的判断，也不在这里做优先级排序——
 * 多权威源的结论如何聚合属 D09 §5 的未签字口径（U-02 系），不归本包。</p>
 */
public enum EncumbranceType {

    /** 無（清白）。硬阻断的唯一豁免值。 */
    NONE("无"),

    /** 抵当権。 */
    MORTGAGE("抵押"),

    /** 前手所有権留保。 */
    OWNERSHIP_RESERVATION("所有权保留"),

    /** 差押（查封）。 */
    ATTACHMENT("查封"),

    /** 盗難届。 */
    THEFT_REPORT("盗难申报"),

    /** 核查过但结论不明，按不通过处理（P-7）。 */
    UNKNOWN("未知");

    /** 中文展示名。 */
    private final String label;

    EncumbranceType(String label) {
        this.label = label;
    }

    /**
     * 中文展示名。
     *
     * @return 展示名
     */
    public String getLabel() {
        return label;
    }

    /**
     * 是否构成硬阻断（P-7：除 {@code NONE} 外全部阻断，含 {@code UNKNOWN}）。
     *
     * @return 非 {@code NONE} 返回 true
     */
    public boolean isBlocking() {
        return this != NONE;
    }
}
