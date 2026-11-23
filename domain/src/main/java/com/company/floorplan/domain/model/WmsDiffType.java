package com.company.floorplan.domain.model;

/**
 * WMS 账实差异类型（六值）。
 *
 * <p>源：D03:309 与 D04:450 的 {@code diff_type} 取值集合，落库为
 * V66__wms_snapshot_reconcile.sql:111-113 的 {@code chk_wms_diff_type}。</p>
 *
 * <p><b>只有 MISSING_IN_WMS 被钉死为 HIGH</b>（V66 的 {@code chk_wms_diff_missing_is_high}）：
 * "账上有、仓库说没有"是担保物可能已经不在了的表述，严重度不能由人下调；
 * 其余五类的严重度由规则与人工认定共同决定。</p>
 */
public enum WmsDiffType {

    /** 账有实无（恒 HIGH，A8.1）。 */
    MISSING_IN_WMS("账有实无"),

    /** 账无实有（可能是未入账的重复融资车辆）。 */
    EXTRA_IN_WMS("账无实有"),

    /** 証書信息不一致（車検証与台账的証書番/登録番号对不上）。 */
    CERT_MISMATCH("証書不符"),

    /** 库位不一致（BIN 差异，不影响债权但影响放车可行性）。 */
    BIN_MISMATCH("库位不符"),

    /** 里程差异（D06:135 与 D07:286：与落札里程差 > 阈值或来源降级时记录，不阻断）。 */
    ODOMETER_DIFF("里程差异"),

    /** 车况差异（与回执侧的 DAMAGE_FOUND 是同一事实的两个登记面）。 */
    CONDITION_DIFF("车况差异");

    private final String label;

    WmsDiffType(String label) {
        this.label = label;
    }

    /** 中文标签。 */
    public String getLabel() {
        return label;
    }

    /** 严重度是否由 DB 约束钉死（只有 MISSING_IN_WMS 是）。 */
    public boolean isSeverityFixed() {
        return this == MISSING_IN_WMS;
    }

    /** 是否属于"担保物本体可能失控"——这类差异在未解决前压住出库（D09 S3 前置，A8.2）。 */
    public boolean blocksOutbound() {
        return this == MISSING_IN_WMS || this == EXTRA_IN_WMS || this == CERT_MISMATCH;
    }

    /** 解析数据库文本。 */
    public static WmsDiffType from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
