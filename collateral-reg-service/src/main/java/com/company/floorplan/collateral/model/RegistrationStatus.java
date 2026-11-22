package com.company.floorplan.collateral.model;

/**
 * 担保权属状态。与 CollateralClient.RegistrationVO.status 的字符串取值保持一致。
 *
 * <p>ADR-Q27：门禁判定由 {@code CollateralRegistrationService#getCurrent} 直读 V10
 * 有效登记批次产出 REGISTERED / NONE；本枚举只描述取值，不承担判定规则。</p>
 */
public enum RegistrationStatus {
    REGISTERED,
    PENDING,
    CANCELLED,
    NONE;

    /** 宽松解析：null / 空 / 未知值一律归 NONE（fail-closed，绝不误判为已登记）。 */
    public static RegistrationStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return NONE;
        }
    }
}
