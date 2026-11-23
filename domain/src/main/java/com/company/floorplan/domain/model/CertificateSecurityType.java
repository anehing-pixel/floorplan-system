package com.company.floorplan.domain.model;

/**
 * 車検証所承载的担保形态（{@code inspection_certificates.security_type}）。
 *
 * <p><b>为什么不复用 LEGACY 的 {@link SecurityType}</b>：那个枚举只有
 * {@code ASSIGNMENT / RETENTION / MANUFACTURER_REPURCHASE / OTHER} 四值，
 * 而 V52 的 {@code ck_cert_security_type} 允许八值（多出
 * {@code INSPECTION_CERT_CUSTODY / MORTGAGE / TITLE_TRANSFER / NONE}）。
 * 直接给共享枚举加值会波及所有 {@code switch(SecurityType)} 与既有落库数据语义
 * （AGENTS.md §10「新依赖先看父 pom」的同型纪律：共享定义改动要波及面可控）。
 * 所以 USED 侧单独立一枚，取值与库内 CHECK 逐字一致，映射关系见 {@link #toLegacySecurityType()}。</p>
 *
 * <p>USED 域的<b>默认</b>担保形态是 [待定源 D-03]，业务未签字 ⟹
 * V52 头注明确「本列只存事实不做默认」，本类也因此不提供 default-of-the-day 常量。</p>
 */
public enum CertificateSecurityType {

    /** 譲渡担保（动産譲渡担保登記的对象，与 LEGACY 同名同义）。 */
    ASSIGNMENT,

    /** 所有权留保。 */
    RETENTION,

    /** 厂家回购承诺。 */
    MANUFACTURER_REPURCHASE,

    /** 車検証占有（靠持有证书取得对抗力，USED 域的主路之一）。 */
    INSPECTION_CERT_CUSTODY,

    /** 抵当権设定登记。 */
    MORTGAGE,

    /** 所有权移转（名义过户）。 */
    TITLE_TRANSFER,

    /** 无担保（自购车、已结清）。 */
    NONE,

    /** 其它（必须在 {@code reason}/审计里写清是什么，不是一类兜底）。 */
    OTHER;

    /**
     * 回落到 LEGACY 枚举，供只认旧四值的既有代码路径读取（如 {@code VehicleUnit.setSecurity}）。
     *
     * <p>映射是<b>有损</b>的：三种 USED 专有条件都落到 {@link SecurityType#OTHER}，
     * 因为把它们塞进 ASSIGNMENT 会让风控误读成「已做让渡担保登记」。
     * 需要精确语义时请读本枚举，不要读返回值。</p>
     */
    public SecurityType toLegacySecurityType() {
        return switch (this) {
            case ASSIGNMENT -> SecurityType.ASSIGNMENT;
            case RETENTION -> SecurityType.RETENTION;
            case MANUFACTURER_REPURCHASE -> SecurityType.MANUFACTURER_REPURCHASE;
            case INSPECTION_CERT_CUSTODY, MORTGAGE, TITLE_TRANSFER, NONE, OTHER -> SecurityType.OTHER;
        };
    }

    /** {@code null}/空白返回 {@code null}；未知字面量抛出。 */
    public static CertificateSecurityType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("未知的担保形态: " + raw, ex);
        }
    }
}
