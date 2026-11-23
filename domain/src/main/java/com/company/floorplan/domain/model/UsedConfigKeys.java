package com.company.floorplan.domain.model;

import java.util.List;

/**
 * USED 域可调参数的键名清单（{@code used_config_params.param_key}）。
 *
 * <p><b>本类只登记「代码里真的有读方」的键</b>。D06 §16 全表 28 个键，
 * 把没实现的键也写成常量，会让人以为那些口径已经落地——这是 AGENTS.md §12
 * 「表建了就算功能有了」在配置维度上的同型错误。每实现一个切片就往这里加一枚，
 * 同时必须在 {@code PROGRESS.md} 的号段/参数登记行写明值种在哪个迁移。</p>
 *
 * <p>命名统一带 {@code floorplan.used.} 前缀（D00 §12 要求与 LEGACY 配置物理隔离），
 * 键名本身照 D06 §16 原文，不在这里做二次发明。</p>
 */
public final class UsedConfigKeys {

    /** R1 G-A3：合规结论的默认有效期（天）；缺值时应用层 fail-closed 要求显式期限。 */
    public static final String COMPLIANCE_VALID_DAYS = "floorplan.used.compliance-valid-days";

    /** R1 规则 4：许可/会员到期预警窗口（天），命中即 EXPIRING。 */
    public static final String LICENSE_EXPIRY_ALERT_DAYS = "floorplan.used.license-expiry-alert-days";

    /**
     * R1 G-A4（D06 §16 标「红线·不可关」）：双维评分是否需要二人签认。
     *
     * <p>读到 {@code false} 时代码<b>仍按 true 执行</b>并打 ERROR——
     * 保留这个键不是为了提供关闭能力，而是让「有人试图关闭红线」在库里留下可读的痕迹。
     * 与 AGENTS.md §8「可配置绕过会不会把硬策略软放行」是同一个问题的两种答案，
     * 这里的答案是：允许被观察，不允许被生效。</p>
     */
    public static final String DEALER_RATING_TWO_PERSON_REQUIRED =
            "floorplan.used.reporting.dealer-rating-two-person-required";

    /**
     * R2.3 里程勾稽阈值（公里）：观测值与主数据之差超过它就开一条 {@code ODOMETER_DIFF} 差异。
     *
     * <p>值来自 D06 §2.5「配置」行原文（{@code mileage-diff-threshold-km=1000}），<b>没有待定源标记</b>，
     * 所以这条不是「等业务签字的默认值」，而是设计已给的定值；采纳 1000 km 的理由是日本二手车市场
     * 把 1,000 km 以内当作「运输/整备里程」的常规容差，超过它才可能是回表。
     * 外置成配置键仍然必要：收紧口径不该发版，而且键留在库里冒烟才能证伪「这个数来自代码」。</p>
     *
     * <p>⚠️ V78 的 {@code source_ref} 里写着 {@code [待定源 D-10]} 是误标（D01 §5 到 D-09 为止，
     * D-10 在 D10 §5 是「计算书编制↔签认」那对 SOD）。迁移已落库不可改（checksum），
     * 回正语句挂在下一次 USED 迁移里，登记见 {@code PROGRESS.md §4.1}。</p>
     */
    public static final String MILEAGE_DIFF_THRESHOLD_KM = "floorplan.used.mileage-diff-threshold-km";

    private UsedConfigKeys() {
    }

    /** 当前已接线的键清单（冒烟与自检用：种了没人读 / 读了没种，都能从这里对出来）。 */
    public static List<String> wired() {
        return List.of(COMPLIANCE_VALID_DAYS, LICENSE_EXPIRY_ALERT_DAYS,
                DEALER_RATING_TWO_PERSON_REQUIRED, MILEAGE_DIFF_THRESHOLD_KM);
    }
}
