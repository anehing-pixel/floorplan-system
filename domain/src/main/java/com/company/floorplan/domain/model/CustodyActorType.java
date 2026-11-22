package com.company.floorplan.domain.model;

/**
 * custody 事件的操作主体类型（D04 §3.10 的 {@code actor_type}，V52 的 {@code ck_cce_actor_type}）。
 *
 * <p><b>为什么只有两值且必须显式写</b>：R12.2 要求审计字段能区分「令牌自然人」与
 * {@code partner_code}（D00 §11 末段「partner 侧不是角色」）。留成自由文本就会出现
 * 「外部系统写的行看起来像内部员工写的」，出险时无法归责。
 * {@code actor} 列随之承载两种形态：{@code USER} 时是自然人标识（{@code AuditActor} 解析结果），
 * {@code PARTNER} 时是 {@code partner_code}。</p>
 */
public enum CustodyActorType {

    /** 令牌自然人（内部岗位）。 */
    USER("自然人"),

    /** 合作方系统（走验签，不走 RBAC）。 */
    PARTNER("合作方");

    /** 中文展示名。 */
    private final String label;

    CustodyActorType(String label) {
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
