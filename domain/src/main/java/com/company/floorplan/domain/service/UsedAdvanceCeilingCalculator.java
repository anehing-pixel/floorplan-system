package com.company.floorplan.domain.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 最大可贷额计算（USED R3，D08 §5.1，纯函数）。
 *
 * <pre>
 * max_advance = FLOOR( prudent_value × max_ratio )      -- 整数日元，向下取整
 * </pre>
 *
 * <p><b>为什么单独一个类而不是并进 {@link LtvTierResolver}</b>：定档是「这台车属于哪一档」，
 * 算额是「这一档能给多少钱」。合成一个函数后，政策试算（UC-R3-11）就只能连金额一起算，
 * 而试算要回答的问题是「改一档影响多少存量敞口」——它需要的是同一个 resolve 套到别的车上。
 * 分开两个纯函数，试算与放款才不会各写一套。</p>
 *
 * <p><b>取整方向必须是 FLOOR</b>。现状 {@code AdvanceApplicationService:176-177} 用的是
 * {@code RoundingMode.HALF_UP}（D08 §5.1.1 实测表点名），四舍五入在这里最多抬 0.5 日元，
 * 但方向是「放得比上限多」——闸门的上限一旦可以被舍入越过，它就不是上限。
 * USED 分支接进那条链路时（R5 放款闸门切片）要一并换掉，不能沿用。</p>
 *
 * <p><b>单台不设额外绝对上限</b>（D08 §5.1）：绝对额管的是集中度风险，
 * 落在闸门 G8（{@code dealer.ltv-limit} / {@code aging-exposure-limit}），
 * 混进本函数会让「改一个数」同时影响单台风险与集中度两件事。</p>
 */
public final class UsedAdvanceCeilingCalculator {

    private UsedAdvanceCeilingCalculator() {
    }

    /**
     * 上限金额。
     *
     * @param prudentValue 审慎值（整数日元，来自估值行，不得用发票额或落札额代打）
     * @param maxRatio     命中档上限成数（{@code 0 < r <= 1}）
     * @return 允许上限（整数日元，向下取整）；任一入参非法时返回 0（由调用方按「无上限」拒绝，
     *         而不是抛异常——闸门失败必须不产生任何写入，但不能因为一个 null 比率变成 500）
     */
    public static long maxAdvance(Long prudentValue, BigDecimal maxRatio) {
        if (prudentValue == null || prudentValue <= 0L || maxRatio == null || maxRatio.signum() <= 0) {
            return 0L;
        }
        return BigDecimal.valueOf(prudentValue).multiply(maxRatio)
                .setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    /** 申请额是否在上限内（相等算通过：上限的定义就是「最多能给这么多」）。 */
    public static boolean withinCeiling(Long requestedAmount, long maxAdvance) {
        if (requestedAmount == null || requestedAmount < 0L) {
            return false;
        }
        return requestedAmount <= maxAdvance;
    }

    /**
     * 还差多少（用于 LTV_001 的 details 与前端提示）。
     *
     * @return 超出额；未超出时为 0，绝不返回负数——负数会被下游当成「还能再借」读。
     */
    public static long exceedAmount(Long requestedAmount, long maxAdvance) {
        if (requestedAmount == null) {
            return 0L;
        }
        long over = requestedAmount - maxAdvance;
        return Math.max(0L, over);
    }
}
