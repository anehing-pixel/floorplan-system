package com.company.floorplan.collateral.model;

/**
 * 担保三源。priority 数字越小越优先。
 *
 * <p>默认裁决顺序为 "登记批次表 &gt; 担保台账 &gt; 车辆主数据"，
 * 理由是登记批次表是法务局公示依据。但这只是**默认实现**，
 * 由 AuthoritativeSourceStrategy 的开关控制，默认关闭，待 Q27 书面定源后再启用——
 * 本枚举不宣告业务口径已定。</p>
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
