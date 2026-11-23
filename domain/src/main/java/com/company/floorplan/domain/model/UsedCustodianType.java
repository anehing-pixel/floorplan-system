package com.company.floorplan.domain.model;

/**
 * USED 域监管方类型（六值）。
 *
 * <p>定名源 D00 §7:403：现有四值 ＋ {@code WMS} 🆕 ＋ {@code AUCTION_VAULT} 🆕。
 * 落库为 V65__custodian_wms.sql:75-77 的 {@code chk_custodian_type}
 * （NOT VALID 落地、同文件内 VALIDATE）。</p>
 *
 * <p><b>{@code isPrimary()} 按产品域分叉，不改 LEGACY 判定</b>（D00 §7:403 与 D09 §6 都要这条）：
 * LEGACY 的"主监管方"是 {@code CERTIFICATE_DEPOSITORY}/第三方仓那一类外部保管者，
 * USED 的主监管方是 WMS 与拍卖场金库。两枚判定分开，LEGACY 的
 * {@code CustodianType#isPrimary()} 与 {@code requiresWrittenApproval()} 一律不动。</p>
 */
public enum UsedCustodianType {

    /** 经销商自有场地（自监管）。 */
    DEALER_WAREHOUSE("经销商场地"),

    /** 証書保管者（車検証押印的落点，R5.1）。 */
    CERTIFICATE_DEPOSITORY("証書保管方"),

    /** 第三方仓库。 */
    THIRD_PARTY_WAREHOUSE("第三方仓库"),

    /** 现场监管员（只看不保管）。 */
    ON_SITE_SUPERVISOR("现场监管"),

    /** WMS 系统运营方：账实比对的另一侧（R6.2）。 */
    WMS("WMS 运营方"),

    /** 拍卖场金库：落札车辆的保管与放车接收方（R4/R6）。 */
    AUCTION_VAULT("拍卖场金库");

    private final String label;

    UsedCustodianType(String label) {
        this.label = label;
    }

    /** 中文标签。 */
    public String getLabel() {
        return label;
    }

    /** USED 域的主监管方：只有这两类能承担 WMS 快照与放车接收（D09 §6）。 */
    public boolean isPrimaryForUsed() {
        return this == WMS || this == AUCTION_VAULT;
    }

    /** 是否持有实体物（车或証書）——只有持物方才可能是放车/放证的接收方。 */
    public boolean holdsCustody() {
        return this == CERTIFICATE_DEPOSITORY || this == WMS
                || this == AUCTION_VAULT || this == THIRD_PARTY_WAREHOUSE;
    }

    /** 是否必须书面批准才能放物（LEGACY 的同名判定保留在 release-service 那枚枚举里）。 */
    public boolean requiresWrittenApproval() {
        return this == CERTIFICATE_DEPOSITORY || this == AUCTION_VAULT;
    }

    /** 解析数据库文本；NULL 返回 null 由调用方按 custodian 主数据缺失处理（D09 §4 → RELS_006）。 */
    public static UsedCustodianType from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
