package com.company.floorplan.domain.model;

import java.util.Locale;

/**
 * 合规核查结论的来源（USED R1，R1.2）。
 *
 * <p>取值域 = {@code dealer_compliance_checks.ck_dchk_source}（V45）三值。</p>
 *
 * <p><b>来源决定证明强度</b>：D07 §3.8（I-11 反社/征信结果入站）明写
 * 「{@code source=MANUAL} ⇒ 要求 {@code evidence_path} ＋ 二人签认」。
 * 原因是人工录入的「查过了、没问题」没有对外可核验的凭据，
 * 而 {@code PARTNER_API} / {@code CREDIT_BUREAU} 的结论带着请求编号与回执附件，
 * 事后能回查到当初到底查了谁、什么时候查的。</p>
 *
 * <p>所以 {@link #requiresEvidenceAndCoSign()} 为真的一侧必须补齐证据链才允许落库；
 * 这条不是形式检查，是「结论能不能被采信」的判定。</p>
 */
public enum ComplianceSource {

    /** 人工录入（内勤代查代填）。 */
    MANUAL("人工录入"),

    /** 合作方 API 回传（I-11 入站）。 */
    PARTNER_API("合作方接口"),

    /** 征信机构报告。 */
    CREDIT_BUREAU("征信机构");

    private final String label;

    ComplianceSource(String label) {
        this.label = label;
    }

    /** 中文标签。 */
    public String getLabel() {
        return label;
    }

    /**
     * 是否需要「证据路径 + 第二人签认」。
     *
     * <p>只有带外部凭据的 {@code PARTNER_API}/{@code CREDIT_BUREAU} 免二人（结论不是本行产生的，
     * 本行没有改它的能力）；其余一律要。缺省（{@code null} 来源）由调用方按最严处理，
     * 因此这里对 {@code null} 也返回 true。</p>
     */
    public static boolean requiresEvidenceAndCoSign(ComplianceSource source) {
        return source != CREDIT_BUREAU;
    }

    /** 解析库内文本；空值返回 null。 */
    public static ComplianceSource from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (ComplianceSource s : values()) {
            if (s.name().equals(normalized)) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知的合规核查来源: " + value);
    }
}
