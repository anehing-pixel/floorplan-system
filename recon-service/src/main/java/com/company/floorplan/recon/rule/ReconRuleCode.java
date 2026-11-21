package com.company.floorplan.recon.rule;

/**
 * 8 条对拍规则的总目录。编号、名称、是否实现、差异类型在这里唯一收敛。
 *
 * <p><b>为什么把未实现的 4 条也列进枚举</b>：对拍报告要能回答"8 条都跑了吗"。
 * 若未实现的规则连编号都不存在，报告里就只剩 4 条，读者会误以为另外 4 条通过了；
 * 列出来并标记 implemented=false，才能把"未实现"与"无差异"明确区分。</p>
 *
 * <p><b>TODO（R5-R8）</b>：规则细节需业务确认后再落地，当前以占位实现列出，
 * 不读库、不产出差异。确认口径后按 R1-R4 的同构方式补齐即可，
 * 不需要改应用服务的编排逻辑。</p>
 */
public enum ReconRuleCode {

    /** R1：单台垫款金额 = 未偿本金 + 累计已核销本金。 */
    R1("垫款余额 vs 还款核销累计", true, "BALANCE_MISMATCH"),

    /** R2：债权存续中的车辆必须存在未结清垫款（状态编码为 1/0 比较）。 */
    R2("在库车辆状态 vs 垫款未结清", true, "STATE_MISMATCH"),

    /** R3：已占用额度 = 未终结垫款金额合计。 */
    R3("额度占用 vs 在途垫款合计", true, "CREDIT_MISMATCH"),

    /** R4：银行已匹配入账 = 还款核销总额。 */
    R4("银行流水入账 vs 还款核销", true, "CASH_MISMATCH"),

    // ---- 以下 4 条为占位：规则细节待业务确认 ----
    /** TODO：利息计提 vs 计息流水累计——需确认计提口径（日切时点/舍入）与流水汇总范围。 */
    R5("利息计提 vs 计息流水累计", false, "TODO"),
    /** TODO：担保登记状态 vs 担保物台账——需确认登记编号与台账状态的映射关系。 */
    R6("担保登记状态 vs 担保物台账", false, "TODO"),
    /** TODO：库龄递减计划 vs 实际还款进度——需确认期数口径与逾期容差。 */
    R7("库龄递减计划 vs 实际还款进度", false, "TODO"),
    /** TODO：处置损失 vs 账务核销——需确认损失认定与账务冲销的时点差。 */
    R8("处置损失 vs 账务核销", false, "TODO");

    private final String title;
    private final boolean implemented;
    private final String diffType;

    ReconRuleCode(String title, boolean implemented, String diffType) {
        this.title = title;
        this.implemented = implemented;
        this.diffType = diffType;
    }

    public String title() { return title; }
    public boolean implemented() { return implemented; }
    public String diffType() { return diffType; }
}
