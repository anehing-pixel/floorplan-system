package com.company.floorplan.domain.service;

import java.math.BigDecimal;

/**
 * 回款冲抵顺序策略接口。
 *
 * <p><b>[待定源 X-1]</b>：冲抵顺序（本金 / 利息 / 罚息 / 费用 谁先谁后）在需求与设计集里
 * 没有签字口径，本轮<b>只定义接缝，不落地任何一种顺序</b>。
 * 这条纪律来自 §3.6：配置开关
 * {@code repayment-alloc-fee-then-interest-then-principal} 必须带 {@code [待定源 X-1]} 标记，
 * <b>不得实现成"已定源的双分支默认"</b>——一旦写死默认分支，未签字的裁决就会以
 * "代码里本来就是这么做的"的形式变成既成事实，而冲抵顺序直接决定
 * 什么时候算"敞口 = 0"，也就是什么时候能放车（{@link ReleaseFundsGate} 的 G1）。</p>
 *
 * <p><b>为什么这里允许出现 BigDecimal</b>：比例与天数的中间量不是金额本体。
 * 落库金额一律是日元整数 {@code BIGINT}（AGENTS §10），
 * 由实现类在返回前完成取整，取整口径同样属 {@code [待定源 X-1]}。</p>
 *
 * <p><b>本轮唯一实现</b>：{@code UnresolvedRepaymentAllocationStrategy}（application 层），
 * 它不做分配、直接抛 {@code BusinessException}——"没定源就报错"比"没定源就猜一个"安全。
 * 守恒式（本金＋利息＋罚息＋费用＝收款额）的<b>列结构</b>可以先行，
 * 但费用与罚息没有规格支撑，本轮不造列（§3.6）。</p>
 */
public interface RepaymentAllocationStrategy {

    /**
     * 把一笔回款分配到各科目。
     *
     * @param input 分配输入（金额、余额、科目可用性由调用方给出，策略不读库、不读配置）
     * @return 各科目分配额；实现必须保证 {@link Allocation#isConserved()} 成立
     */
    Allocation allocate(AllocationInput input);

    /** 分配输入：只带金额事实，不带"顺序"参数——顺序正是策略本身。 */
    final class AllocationInput {
        /** 本次回款额（日元整数）。 */
        public final long amount;
        /** 本金余额。 */
        public final long principalOutstanding;
        /** 利息余额。 */
        public final long interestOutstanding;
        /** 费用/罚息余额；无规格支撑时由调用方传 0 并在审计里说明。 */
        public final long feeOutstanding;

        public AllocationInput(long amount, long principalOutstanding,
                               long interestOutstanding, long feeOutstanding) {
            this.amount = amount;
            this.principalOutstanding = principalOutstanding;
            this.interestOutstanding = interestOutstanding;
            this.feeOutstanding = feeOutstanding;
        }
    }

    /** 分配结果：四个科目的分配额，以及守恒自检。 */
    final class Allocation {
        public final long principal;
        public final long interest;
        public final long penalty;
        public final long fee;

        public Allocation(long principal, long interest, long penalty, long fee) {
            this.principal = principal;
            this.interest = interest;
            this.penalty = penalty;
            this.fee = fee;
        }

        /** 守恒式：四科目之和必须等于本次回款额（多一分少一分都算不守恒）。 */
        public boolean conservedAgainst(long receivedAmount) {
            return Math.addExact(Math.addExact(Math.addExact(principal, interest), penalty), fee)
                    == receivedAmount;
        }

        /** 大额日元的比例中间量工具：策略实现取整前用，避免各处手写 double 乘除。 */
        public static BigDecimal ratio(long part, long total) {
            return total == 0L ? BigDecimal.ZERO
                    : BigDecimal.valueOf(part).divide(BigDecimal.valueOf(total), 8, java.math.RoundingMode.HALF_UP);
        }
    }
}
