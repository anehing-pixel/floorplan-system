package com.company.floorplan.domain.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 合规核查类型（USED R1，G-A3）。
 *
 * <p>取值域 = {@code dealer_compliance_checks.ck_dchk_type}（V45）四值。
 * <b>四类都是必备项</b>：D06 §1.3 的 G-A3 原文是「四类 {@code check_type} 均有未过期
 * {@code result='PASS'}」，缺任何一类都算合规不通过，不存在「只查反社就够了」。</p>
 *
 * <p>{@link #all()} 是闸门遍历的依据。之所以由枚举自己提供「必备全清单」而不是让调用方
 * 硬编码四行：将来 CHECK 加了第五类，只需在此加一枚常量，闸门自动开始要求它——
 * 反过来（调用方自己列）会在新增类型时静默少查一项。</p>
 */
public enum ComplianceCheckType {

    /** 反社会的勢力チェック（暴力団排除条例）。 */
    ANTI_SOCIAL("反社核查"),

    /** マネーロンダリング対策（犯罪収益移転防止法の実査）。 */
    AML("AML 核查"),

    /** 本人確認（実在性・同一性）。 */
    IDENTITY("本人确认"),

    /** 征信机构查询。 */
    CREDIT_BUREAU("征信查询");

    private final String label;

    ComplianceCheckType(String label) {
        this.label = label;
    }

    /** 中文标签。 */
    public String getLabel() {
        return label;
    }

    /** 准入必备的全类型清单（G-A3 的「四类均有」就是遍历这个清单）。 */
    public static List<ComplianceCheckType> required() {
        return Arrays.asList(values());
    }

    /** 解析库内文本；空值返回 null。 */
    public static ComplianceCheckType from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (ComplianceCheckType t : values()) {
            if (t.name().equals(normalized)) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知的合规核查类型: " + value);
    }
}
