package com.company.floorplan.collateral.model;

/**
 * 担保三源。priority 数字越小越优先。
 *
 * <p><b>ADR-Q27 已裁决</b>：优先级 "登记批次表 &gt; 担保台账 &gt; 车辆主数据" 已定稿，
 * 依据是登记批次表（V10）为法务局公示依据、后两者为派生状态。
 * 该优先级现在用于<b>对账体检诊断</b>，不再用于门禁判定——
 * 门禁直读 V10 权威源，不经过三源比较。</p>
 */
public enum SourceSystem {
    REGISTRATION_BATCH(1, "登记批次表（公示依据）"),
    COLLATERAL_LEDGER(2, "担保台账"),
    VEHICLE_UNITS(3, "车辆主数据担保字段");

    private final int priority;
    private final String description;

    SourceSystem(int priority, String description) {
        this.priority = priority;
        this.description = description;
    }

    public int getPriority() {
        return priority;
    }

    public String getDescription() {
        return description;
    }

    public String code() {
        return name();
    }

    /** 未知来源返回 null——裁决时忽略，不参与优先级比较。 */
    public static SourceSystem fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
