package com.company.floorplan.recon.rule;

import com.company.floorplan.recon.model.Comparison;

/**
 * R4 银行流水入账 vs 还款核销（按 VIN）。
 *
 * <p><b>对拍口径</b>：左侧是银行事实——{@code payment_transactions} 中已匹配到该 VIN、
 * 金额为正的入账合计；右侧是我方认定——{@code repayments.total_amount} 合计。
 * 两者必须精确相等。这是 V15 建流水表的核心动机：
 * 还款核销是"自己说自己收到了"，只有银行流水能自证钱真的到了。</p>
 *
 * <p><b>未匹配流水不计入</b>：UNMATCHED 的入账还不知道对应哪台车，
 * 强行摊到某个 VIN 只会制造假差异。它们由 PAYMENT_MATCH 流程消化，
 * 消化后自然进入本规则的对拍范围。</p>
 *
 * <p><b>已知局限（TODO）</b>：银行入账与核销之间存在时差（T+1、节假日、人工补录），
 * 当日对拍可能报出"在途差异"。当前实现不做时间窗匹配，
 * 规则细节（账期切分与容差）需业务确认后再细化，详见 recon-service/HANDOFF.md。</p>
 */
public class R4BankVsRepaymentRule extends AbstractReconRule {

    public R4BankVsRepaymentRule() {
        super(ReconRuleCode.R4);
    }

    @Override
    protected boolean breached(Comparison fact) {
        return fact.expected() != fact.actual();
    }

    @Override
    protected String detail(Comparison fact) {
        return "银行已匹配入账=" + fact.expected()
                + "，还款核销总额=" + fact.actual()
                + "，差额(银行-核销)=" + (fact.expected() - fact.actual());
    }
}
