package com.company.floorplan.domain.model;

import java.util.Locale;

/**
 * 估值用途（USED R3，{@code vehicle_valuations.purpose}）。
 *
 * <p>取值权威源 = D00 §8.5 四值，库内 CHECK 是 V55 {@code ck_val_purpose} 的同一集合。
 * <b>D08 §3.1 用的是三值旧名</b>（{@code ADVANCE_BASE / REVALUATION / DISPOSAL_FLOOR}），
 * V55 头注已登记这处不一致并按 D00 落四值；对应关系：
 * {@code REVALUATION → PERIODIC_RECHECK}（定期）与 {@code EVENT_RECHECK}（事件触发）、
 * {@code DISPOSAL_FLOOR → DISPOSAL_PRICING}。读 D08 那张表时按本映射理解，
 * 不要照抄名字进代码或 SQL——那会当场撞 CHECK。</p>
 *
 * <p>用途决定「哪条硬校验生效」（D08 §3.1 的作用域表），因此判定方法挂在枚举上：
 * {@link #requiresAdvanceBaseRule()} 是 §3.2 退化表的入口开关，
 * {@link #requiresSameDay()} 是处置定价「必须当日估值」的唯一表达。</p>
 */
public enum ValuationPurpose {

    /** 放款基准：全部闸门 + §3.2 的可用源要求，失败即拒付，无绕过。 */
    ADVANCE_BASE(true, false),

    /** 定期重估（在库监控）：允许单源，过期即触发重估作业。 */
    PERIODIC_RECHECK(false, false),

    /** 事件触发重估（跌价 / 入库 / 售出）：与定期同规则，只是触发源不同。 */
    EVENT_RECHECK(false, false),

    /** 处置定价：允许单源，但必须当日有效（D08 §3.1 末列），且人工值不得是唯一源（§3.4 禁止行）。 */
    DISPOSAL_PRICING(false, true);

    private final boolean advanceBaseRule;
    private final boolean sameDay;

    ValuationPurpose(boolean advanceBaseRule, boolean sameDay) {
        this.advanceBaseRule = advanceBaseRule;
        this.sameDay = sameDay;
    }

    /** 是否受「除人工覆盖外，单源不可作基准」这条硬约束管辖（D08 §3.2 裁决段）。 */
    public boolean requiresAdvanceBaseRule() {
        return advanceBaseRule;
    }

    /** 是否要求估值日就是当天（处置定价：隔日的行情不能作为再拍卖保留价依据）。 */
    public boolean requiresSameDay() {
        return sameDay;
    }

    /** 是否为在库监控类用途（允许单源、过期即重估）。 */
    public boolean isRecheck() {
        return this == PERIODIC_RECHECK || this == EVENT_RECHECK;
    }

    /** 解析入参词；非法词由用例层翻成 {@code VALID_001}。 */
    public static ValuationPurpose from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("估值用途不能为空");
        }
        return ValuationPurpose.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
