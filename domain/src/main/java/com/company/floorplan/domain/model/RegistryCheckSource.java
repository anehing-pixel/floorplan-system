package com.company.floorplan.domain.model;

/**
 * 登録档案照会的来源（USED 域 R5.3）。
 *
 * <p><b>取值定源</b>：D00 §8.2 的 {@code RegistryCheckSource} 🆕——
 * {@code MANUAL · REGISTRY_AGENT(代办回执) · REGISTRY_API}，说明列写
 * 「替代现状 {@code check_source} 的 {@code OEM_PORTAL}（USED 域不适用厂家门户）」。</p>
 *
 * <p><b>为什么不复用现状的 {@code check_source} 取值</b>：现状
 * {@code financing_lien_checks.check_source} 是自由 {@code VARCHAR}，LEGACY 里已经混着
 * {@code OEM_PORTAL} 之类的值。V54 给该列加的 {@code ck_lien_check_source} 因为存量值
 * 未经核库确认，是以 {@code NOT VALID} 落地的（VALIDATE 由收口方在唯一主 schema 上跑）——
 * 本枚举只描述 USED 写入侧的合法取值，不复述 LEGACY 存量。</p>
 *
 * <p>DB 侧对应：V52 的 {@code ck_cce_check_source}（三值，允许 NULL）。</p>
 */
public enum RegistryCheckSource {

    /** 人工查询登录。 */
    MANUAL("人工"),

    /** 登記代辦所回执。 */
    REGISTRY_AGENT("代办回执"),

    /** 登録档案 API。 */
    REGISTRY_API("登记接口");

    /** 中文展示名。 */
    private final String label;

    RegistryCheckSource(String label) {
        this.label = label;
    }

    /**
     * 中文展示名。
     *
     * @return 展示名
     */
    public String getLabel() {
        return label;
    }
}
