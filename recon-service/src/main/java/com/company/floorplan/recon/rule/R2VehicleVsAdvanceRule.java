package com.company.floorplan.recon.rule;

import com.company.floorplan.recon.model.Comparison;

/**
 * R2 在库车辆状态 vs 垫款未结清（按 VIN）。
 *
 * <p><b>对拍口径</b>：车辆处于"债权应当存续"的状态（在途 FINANCED / 在库 IN_STOCK /
 * 已售待还 SOLD_PENDING / 逾期 OVERDUE / 处置中 DISPOSING）时，
 * 账上必须存在一笔未结清垫款（PENDING/PROCESSING/PAID）；反之则不应存在。</p>
 *
 * <p><b>为什么编码成 1/0 而不是直接比字符串</b>：这里对拍的是两个不同域的事实——
 * 台账状态是"车在哪、处于什么业务环节"，垫款状态是"钱付没付、债权在不在"。
 * 把"是否应当存在债权"归一成 1/0，判定就退化成精确相等，
 * 而业务语义留在 SQL 的 CASE 里，便于审阅口径而不污染判定函数。</p>
 *
 * <p><b>已结清/赎回车辆不进本规则</b>：它们在账上可以合法地没有未结清垫款，
 * 但也可能因为历史原因残留——这类检查属于 R1/额度规则的范畴，避免规则间重复报同一问题。</p>
 */
public class R2VehicleVsAdvanceRule extends AbstractReconRule {

    public R2VehicleVsAdvanceRule() {
        super(ReconRuleCode.R2);
    }

    @Override
    protected boolean breached(Comparison fact) {
        return fact.expected() != fact.actual();
    }

    @Override
    protected String detail(Comparison fact) {
        String expectedText = fact.expected() == 1 ? "应有未结清垫款" : "不应有未结清垫款";
        String actualText = fact.actual() == 1 ? "存在未结清垫款" : "不存在未结清垫款";
        return "车辆状态口径=" + expectedText + "，账本事实=" + actualText;
    }
}
