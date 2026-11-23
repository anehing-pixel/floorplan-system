package com.company.floorplan.domain.model;

import java.util.Locale;

/**
 * 车商许可状态（USED R1，G-A1 判定输入）。
 *
 * <p>取值域 = {@code dealer_licenses.ck_dlic_status}（V45）四值。
 * 本枚举的重点不是「有哪些值」，而是<b>只有 ACTIVE 算有效</b>：
 * {@code EXPIRED}（自然到期）、{@code REVOKED}（被处分取消）、
 * {@code SUPERSEDED}（被新证替换）三种都表示「手上看不到有效的証書」。</p>
 *
 * <p>{@code SUPERSEDED} 由系统在换发时自动置位：{@code dealer_licenses} 的不变式是
 * 「同一车商同时最多一条 ACTIVE 且未过期」（D03 §2.1）。新证登记时若不把旧证降级，
 * 就会出现两条 ACTIVE，闸门读到哪条算哪条——那是最典型的「看起来有数据、其实口径二义」。</p>
 */
public enum LicenseStatus {

    /** 有效。 */
    ACTIVE("有效"),

    /** 自然到期（由到期扫描作业置位）。 */
    EXPIRED("已到期"),

    /** 被行政处分取消。 */
    REVOKED("被取消"),

    /** 被更新的一条许可替换（换发）。 */
    SUPERSEDED("已被替换");

    private final String label;

    LicenseStatus(String label) {
        this.label = label;
    }

    /** 中文标签。 */
    public String getLabel() {
        return label;
    }

    /** 是否处于「可支撑准入」的状态；只认 ACTIVE，其余三态一律无效。 */
    public boolean isValid() {
        return this == ACTIVE;
    }

    /** 解析库内文本；空值返回 null（无许可记录，闸门按 QUAL_001 阻断）。 */
    public static LicenseStatus from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (LicenseStatus s : values()) {
            if (s.name().equals(normalized)) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知的许可状态: " + value);
    }
}
