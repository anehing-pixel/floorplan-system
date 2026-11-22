package com.company.floorplan.recon.rule;

/**
 * 8 条对拍规则的总目录。编号、名称、是否实现、差异类型在这里唯一收敛。
 *
 * <p><b>2026-11-22 全量落地</b>：R5–R8 原为占位（{@code implemented=false} / {@code diffType=TODO}），
 * 现按《业务口径总定源》ADR 裁决全部补成真实判定。8 条规则一律采用
 * 「取数 SQL + 纯函数精确相等判定」：两口径数值精确相等才无差异，不等即产出差异，
 * 不做容差、不做舍入猜测。至此 8 条全部 {@code implemented=true}，
 * 对拍报告的 {@code ruleCount} 恒为 8，不再有「跑了但没实现」的假绿。</p>
 *
 * <p><b>为什么把规则目录留成一处</b>：差异类型由目录统一分配，避免各实现自由命名
 * 导致下游统计对不上；{@code implemented} 是编排层唯一的取数开关，
 * 新增规则只需加枚举 + 一条 SQL + 一个 {@link ExactValueReconRule} 装配。</p>
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

    // ---- R5-R8：按《业务口径总定源》ADR 裁决落地，取数口径见 JdbcReconRepository ----
    /** R5：台账累计计提利息 = 计息流水累计（主表 + 季度归档表），按 VIN。 */
    R5("利息计提 vs 计息流水累计", true, "INTEREST_MISMATCH"),
    /** R6：V10 有效登记编码 1/0 = 担保台账 REGISTERED 编码 1/0，按 VIN。 */
    R6("担保登记状态 vs 担保物台账", true, "COLLATERAL_MISMATCH"),
    /** R7：库龄递减计划已付期数 = 递减核销流水笔数，按 VIN。 */
    R7("库龄递减计划 vs 实际还款进度", true, "CURTAILMENT_MISMATCH"),
    /** R8：已结案处置认定损失 = 借据 WRITTEN_OFF 未偿敞口，按 VIN。 */
    R8("处置损失 vs 账务核销", true, "WRITEOFF_MISMATCH");

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
