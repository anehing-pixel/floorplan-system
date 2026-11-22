package com.company.floorplan.domain.model;

/**
 * 許可証生命周期状态（USED 域 R1.1）。
 *
 * <p><b>取值定源</b>：D04 §3.1 的 {@code dealer_licenses.status}
 * —— {@code VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', CHECK ∈ {ACTIVE,EXPIRED,REVOKED,SUPERSEDED}}；
 * DB 侧的双层约束在 V45 的 {@code ck_dlic_status}。</p>
 *
 * <p><b>本枚举刻意不实现 {@code canTransit} 状态机</b>，两条依据：
 * ① D00 §8 约定③ 要求「状态类枚举必须附 canTransit 白名单 + 硬编码单测」，
 * 而本轮用户明令「不写测试、不编译、不连库」，做成状态机会留下一个必然欠着的单测；
 * ② D04 §3.1 只把本列定义成台账取值，没有任何文档给出許可状态的转移白名单
 * （D03 §2.1 给的是「同一 dealerId 同时最多一条 ACTIVE 且未过期」这一<b>不变式</b>，
 * 由 V45 的部分唯一索引 {@code uk_dealer_license_active} 承担，不是转移表）。
 * 因此订正（{@code EXPIRED -> ACTIVE}，实务上届出やり直し后確实会发生）不作禁止，
 * 但订正必须走应用服务与审计，不允许在代码外直接改库。</p>
 *
 * <p>USED 域按状态机实现的是 {@link CertificateStatus}（D03 给了完整转移图），
 * 载体是 {@code CertificateStateMachine}，与本枚举分工不同。</p>
 *
 * <p><b>「过期」的两条口径</b>：D03 §2.1 审核规则 1 用 {@code expiresOn} 与业务日比较判过期，
 * 而本列的 {@code EXPIRED} 要等入站/年审人工确认后才写。同一车商可能落在
 * 「status 仍是 ACTIVE、但 expiresOn 已过」。本包的处理是<b>两条都在
 * {@code DealerAdmissionPolicy} 里评估、取更严的一侧</b>：不在本枚举里用日期改写状态，
 * 也不把日期判定塞进 {@link #isUsable()}（否则同一谓词活两层，改一侧必漏另一侧）。</p>
 */
public enum LicenseStatus {

    /** 有效（业务原词「有効」）。同一 dealerId 下此状态最多一行（V45 部分唯一索引 {@code uk_dealer_license_active}）。 */
    ACTIVE("有效"),

    /** 已过期（原词「期限切れ」）。写入时机＝入站或年审的人工确认，不由定时任务改库。 */
    EXPIRED("已过期"),

    /** 已取消（许可失效处分）。判定上与 EXPIRED 同为不可用，台账上是不同事实，故分列。 */
    REVOKED("已取消"),

    /** 届出更新后旧行的替换标记；旧行不物理删除（无物理删除纪律）。 */
    SUPERSEDED("已替换");

    /** 中文展示名（与库内枚举值一一对应，前端提示与审计说明用）。 */
    private final String label;

    LicenseStatus(String label) {
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
     * 审核侧「当前是否可用」的状态判定（只看状态，不看日期）。
     *
     * <p>刻意不提供 {@code isExpiredOn(bizDate)}：日期口径的唯一归属地是
     * {@code DealerAdmissionPolicy}，理由见类注释「过期」的两条口径。</p>
     *
     * @return {@code ACTIVE} 返回 true
     */
    public boolean isUsable() {
        return this == ACTIVE;
    }
}
