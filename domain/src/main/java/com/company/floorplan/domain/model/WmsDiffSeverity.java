package com.company.floorplan.domain.model;

/**
 * WMS 差异严重度（四值）；落库 V66:114 的 {@code chk_wms_diff_severity}。
 *
 * <p>不用 risk 侧 {@code Alert.severity}（那列是无约束 VARCHAR，Alert.java:30）：
 * USED 的严重度直接决定"能不能出库"（HIGH 及以上压住 D09 S3 前置），
 * 必须是有 CHECK 钉住的独立值域，不能跟着另一张表的自由文本走。</p>
 */
public enum WmsDiffSeverity {

    /** 仅登记事实，不阻断任何动作。 */
    INFO("提示"),

    /** 需在下一次盘点前解释，不阻断出库。 */
    WARNING("警告"),

    /** 阻断出库（D09 S3 前置：无未决 HIGH 差异才放行），MISSING_IN_WMS 恒为此级。 */
    HIGH("高"),

    /** 除阻断出库外还触发"担保落空"这一件闸门（R7.5，D09 §5.3 闸门三件套之一）。 */
    CRITICAL("严重");

    private final String label;

    WmsDiffSeverity(String label) {
        this.label = label;
    }

    /** 中文标签。 */
    public String getLabel() {
        return label;
    }

    /** 是否压住出库与放车指令（A8.2 的判据）。 */
    public boolean blocksOutbound() {
        return this == HIGH || this == CRITICAL;
    }

    /** 解析数据库文本。 */
    public static WmsDiffSeverity from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
