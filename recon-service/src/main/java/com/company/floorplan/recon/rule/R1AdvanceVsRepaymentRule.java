package com.company.floorplan.recon.rule;

import com.company.floorplan.recon.model.Comparison;

/**
 * R1 垫款余额 vs 还款核销累计（按 VIN）。
 *
 * <p><b>对拍口径</b>：一台车曾经放出去的钱，应当等于"还没还的本金 + 已经核销的本金"。
 * 左侧取 {@code advances.amount}（未终结状态：PENDING/PROCESSING/PAID），
 * 右侧取 {@code vehicle_units.principal_balance + SUM(repayments.principal)}。</p>
 *
 * <p><b>为什么要加"已核销"而不是直接比余额</b>：部分还款会把未偿本金减掉，
 * 若只比"垫款金额 vs 当前余额"，所有还过钱的车都会被误判成差异。
 * 把已核销本金加回来，恒等式 "借出 = 未还 + 已还" 对全额、部分、提前赎回三种路径都成立。</p>
 *
 * <p>判定是纯函数：金额必须精确相等（整数日元，无容差）。</p>
 */
public class R1AdvanceVsRepaymentRule extends AbstractReconRule {

    public R1AdvanceVsRepaymentRule() {
        super(ReconRuleCode.R1);
    }

    @Override
    protected boolean breached(Comparison fact) {
        return fact.expected() != fact.actual();
    }

    @Override
    protected String detail(Comparison fact) {
        return "垫款金额=" + fact.expected()
                + "，未偿本金+累计核销本金=" + fact.actual()
                + "，差额(借出-已核证)=" + (fact.expected() - fact.actual());
    }
}
