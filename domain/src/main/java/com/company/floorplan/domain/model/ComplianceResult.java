package com.company.floorplan.domain.model;

/**
 * 合规核查的结论（文档原词「コンプライアンス確認の結論」；USED 域 R1.1）。
 *
 * <p><b>取值定源</b>：D04 §3.3 的 {@code dealer_compliance_checks.result}
 * —— {@code VARCHAR(16) CHECK ∈ {PASS, FAIL, UNKNOWN}}，并注明
 * 「{@code UNKNOWN} 按不通过处理（P-7）」；DB 侧约束在 V45 的 {@code ck_dchk_result}。
 * D03 §2.1 的聚合注释重复了同一句（「结论 UNKNOWN 视为不通过」），两侧一致，无冲突。</p>
 *
 * <p><b>为什么不把 UNKNOWN 折叠成 FAIL</b>：D00 §8.1 对 {@code MileageSource.UNKNOWN}
 * 已定过同一条口径——UNKNOWN 与"没有记录"是两种事实，必须显式写这个值，
 * 不许用 NULL 代替。UNKNOWN 说明「查过但查不清」，NULL/无行说明「没查过」；
 * 两者的补查动作不同（前者要复核来源，后者要发起核查），审计口径也不同。
 * 折叠之后，D04 §3.3 的 {@code source} 列就会失去可解释的样本。</p>
 */
public enum ComplianceResult {

    /** 通过。 */
    PASS("通过"),

    /** 不通过（硬阻断）。 */
    FAIL("不通过"),

    /** 结论不明。<b>判定上等同不通过</b>（P-7），但台账上保留"查过且不明"这一事实。 */
    UNKNOWN("未知");

    /** 中文展示名（前端文案基线）。 */
    private final String label;

    ComplianceResult(String label) {
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
     * 准入判定用的「算不算通过」。
     *
     * <p>只有 {@code PASS} 为 true——{@code UNKNOWN} 按不通过处理是 D04 §3.3 与 D03 §2.1
     * 的共同口径（P-7），谓词放在这里是为了让 {@code DealerAdmissionPolicy} 与各查询投影
     * 共用同一个定义，不出现"列表按 UNKNOWN 显示黄色、闸门按 FAIL 阻断"的分叉。</p>
     *
     * @return {@code PASS} 返回 true
     */
    public boolean isPass() {
        return this == PASS;
    }
}
