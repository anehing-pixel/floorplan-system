package com.company.floorplan.domain.model;

import java.util.Locale;

/**
 * 车商许可类型（USED R1）。
 *
 * <p>取值域只有一个 {@code FURIGUSHO}，这不是偷懒，是库内 CHECK 定的：
 * {@code dealer_licenses.ck_dlic_license_type CHECK (license_type = 'FURIGUSHO')}（V45）。
 * 二手车库存垫资的准入要件就是《古物営業法》上的古物商許可，
 * 其余牌照（auto 整備・輸出等）本轮不作为放款前置。</p>
 *
 * <p>要扩类型必须<b>同时</b>改库 CHECK 与本枚举，并回答「新增的牌照是不是放款前置」——
 * 只改本枚举会让写入撞上 CHECK 报错；只改 CHECK 会让闸门少一个要件却看不出来。</p>
 */
public enum LicenseType {

    /** 古物商許可（管轄公安委員会発行）。 */
    FURIGUSHO("古物商許可");

    private final String label;

    LicenseType(String label) {
        this.label = label;
    }

    /** 中文标签。 */
    public String getLabel() {
        return label;
    }

    /** 解析库内文本；空值返回 null（缺失许可由准入闸门按 QUAL_001 处理，不在这里抛）。 */
    public static LicenseType from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (LicenseType t : values()) {
            if (t.name().equals(normalized)) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知的许可类型: " + value);
    }
}
