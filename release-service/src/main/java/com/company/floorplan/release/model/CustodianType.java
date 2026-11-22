package com.company.floorplan.release.model;

/**
 * 接收方（监管方）类型 —— Q9「出库指令接收方是谁」建议口径的代码落点。
 *
 * <p><b>为什么只有两类是主用</b>：《业务流程文档》§6 已确认本系统面向日本新车渠道，
 * 物理监管只做实地盘点，不做扣押钥匙 / GPS / 封签 / 驻场监管——那是中国第三方
 * 监管公司对二手车、高风险车商的做法。车物理上就在经销商自己的库里，
 * 金融公司没有第三方替它看车，真正握住的控制手段是权属凭证。</p>
 *
 * <p>因此放车线与放证线各有一类主用接收方；另两类保留枚举是为了不推翻历史数据，
 * 但必须业务书面确认后才能启用，不得作为默认配置。</p>
 */
public enum CustodianType {

    /** 放车线：经销商自有仓库，收款后执行交付。 */
    DEALER_WAREHOUSE(true),

    /** 放证线：合格证 / 让与担保证凭证保管点，交出凭证即交出控制力。 */
    CERTIFICATE_DEPOSITORY(true),

    /** 中国二手车监管假设，与日本渠道口径冲突，需业务书面启用。 */
    THIRD_PARTY_WAREHOUSE(false),

    /** 驻场监管，已被业务流程文档 §6 明确移除，需业务书面启用。 */
    ON_SITE_SUPERVISOR(false);

    private final boolean primary;

    CustodianType(boolean primary) {
        this.primary = primary;
    }

    /** 是否与已确认的日本新车渠道口径一致、可直接启用。 */
    public boolean isPrimary() {
        return primary;
    }

    /** 是否与已确认口径冲突、须业务书面确认后方可启用。 */
    public boolean requiresWrittenApproval() {
        return !primary;
    }
}
