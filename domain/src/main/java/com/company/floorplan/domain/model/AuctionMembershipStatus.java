package com.company.floorplan.domain.model;

/**
 * 拍卖场会员资格（落札会員／会员证）的状态（USED 域 R1.1）。
 *
 * <p><b>取值定源</b>：D04 §3.2 的 {@code dealer_auction_memberships.status}
 * —— {@code VARCHAR(16) CHECK ∈ {ACTIVE, SUSPENDED, EXPIRED, TERMINATED}}；
 * DB 侧约束在 V45 的 {@code ck_dmem_status}。本枚举不在 D00 §8 枚举字典里
 * （同 {@link DealerLicenseType}／{@link LicenseStatus}，属「D00 §8 未覆盖 R1 侧枚举」的登记项）。</p>
 *
 * <p><b>同样不实现状态机</b>：依据与 {@link LicenseStatus} 一致（D00 §8 约定③ 要求配套单测，
 * 本轮禁测；且无任何文档给出会员证的转移白名单）。
 * 与 {@code TERMINATED}（会场抹消）和 {@code EXPIRED}（年会费未纳等期限问题）分列是必要的：
 * 前者不可恢复、后者缴费即恢复，R1.1 验收要能回答「这个会场为什么落不了札」。</p>
 *
 * <p><b>与拍卖场主数据的关系</b>：{@code auction_house_code} 逻辑引用
 * {@code auction_houses}（D04 §3.4，归 W-U2 的 V60 号段），本包<b>不建物理 FK</b>——
 * V45 跑在 V60 之前，被引用表还不存在；引用完整性靠应用层校验与 W-U2 接线后的一次性核对。</p>
 */
public enum AuctionMembershipStatus {

    /** 有效（原词「有効」）：该会场可落札。 */
    ACTIVE("有效"),

    /** 暂停（原词「一時停止」，会场侧处分）。本包判定按不可用处理。 */
    SUSPENDED("暂停"),

    /** 已过期（原词「期限切れ」，年会费未纳等）。 */
    EXPIRED("已过期"),

    /** 已抹消（原词「抹消」）：无恢复手段。 */
    TERMINATED("已抹消");

    /** 中文展示名（前端提示与审计说明用；日文原词见各行注释）。 */
    private final String label;

    AuctionMembershipStatus(String label) {
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

    /**
     * 「该会场当前能否落札」的判定（{@code DealerAdmissionPolicy} 规则 3 用）。
     *
     * <p>判定收敛在本方法而不是让调用方逐个 {@code == ACTIVE} 比较，是为了防止
     * 后续新增值漏进调用点的分支（{@link DealerLicenseType} 的不可用值是"加一个少一个坑"的形状）。</p>
     *
     * @return {@code ACTIVE} 返回 true
     */
    public boolean isUsable() {
        return this == ACTIVE;
    }
}
