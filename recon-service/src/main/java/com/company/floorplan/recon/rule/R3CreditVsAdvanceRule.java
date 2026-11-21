package com.company.floorplan.recon.rule;

import com.company.floorplan.recon.model.Comparison;

/**
 * R3 额度占用 vs 在途垫款合计（按额度）。
 *
 * <p><b>对拍口径</b>：{@code credit_lines.used_amount} 是"账面上说占了多少额度"，
 * 未终结垫款（PENDING/PROCESSING/PAID）金额之和是"实际借出去/正在借的钱"。
 * 两者必须精确相等——循环授信的整个内控都建立在"额度占用=真实债权"之上。</p>
 *
 * <p><b>为什么只查 ACTIVE 额度</b>：已冻结/关闭的额度不会再有新的占用变动，
 * 其 used_amount 可能停留在历史值，纳入对拍只会产生无意义的噪音。</p>
 *
 * <p><b>为什么这是资损级检查</b>：used_amount 少了 → 系统以为还有额度 → 超额放款；
 * used_amount 多了 → 额度被凭空占住 → 车商借不出钱。两个方向都必须报差异。</p>
 */
public class R3CreditVsAdvanceRule extends AbstractReconRule {

    public R3CreditVsAdvanceRule() {
        super(ReconRuleCode.R3);
    }

    @Override
    protected boolean breached(Comparison fact) {
        return fact.expected() != fact.actual();
    }

    @Override
    protected String detail(Comparison fact) {
        return "已占用额度=" + fact.expected()
                + "，未终结垫款合计=" + fact.actual()
                + "，差额(占用-垫款)=" + (fact.expected() - fact.actual());
    }
}
