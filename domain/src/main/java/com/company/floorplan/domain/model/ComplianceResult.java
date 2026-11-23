package com.company.floorplan.domain.model;

import java.util.Locale;

/**
 * 合规核查结论（USED R1，G-A3）。
 *
 * <p>取值域 = {@code dealer_compliance_checks.ck_dchk_result}（V45）三值。</p>
 *
 * <p><b>{@code UNKNOWN} 按不通过处理（P-7，D03 §2.1 / D06 §1.3）</b>：
 * 「查不到结论」和「查出没问题」在风控上是两件事。若把 UNKNOWN 当成放行，
 * 外部核查服务一挂，全量车商就自动变成合规通过——这是最容易在事故当天发生的假放行。
 * 因此 {@link #isPassing()} 只认 {@code PASS}，且判定写成「白名单」而不是「非 FAIL」：
 * 将来 CHECK 里加第四种结论，默认落在不通过一侧。</p>
 */
public enum ComplianceResult {

    /** 通过。 */
    PASS("通过"),

    /** 不通过。 */
    FAIL("不通过"),

    /** 无法得出结论（查询失败、回复超期、资料不足）。 */
    UNKNOWN("结论未知");

    private final String label;

    ComplianceResult(String label) {
        this.label = label;
    }

    /** 中文标签。 */
    public String getLabel() {
        return label;
    }

    /** 是否算合规通过；UNKNOWN 与 FAIL 一样不通过（P-7）。 */
    public boolean isPassing() {
        return this == PASS;
    }

    /** 解析库内文本；空值返回 null，调用方按「无记录」处理。 */
    public static ComplianceResult from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (ComplianceResult r : values()) {
            if (r.name().equals(normalized)) {
                return r;
            }
        }
        throw new IllegalArgumentException("未知的合规结论: " + value);
    }
}
