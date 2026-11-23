package com.company.floorplan.domain.model;

import java.util.Locale;

/**
 * 估值行状态（USED R3，{@code vehicle_valuations.status}，D03 §3.7 状态机）。
 *
 * <pre>
 * DRAFT ──生效──► ACTIVE ──到期作业──► EXPIRED
 *                    └──新行生效（同事务）──► SUPERSEDED
 * </pre>
 *
 * <p><b>没有 CANCELLED</b>：D03 §3.7 的取值集就是这四值，V55 的 CHECK 照抄。
 * 「作废」在 USED 域的表达是 {@link #SUPERSEDED}（被后来的结论取代），
 * 而不是抹掉这一行——估值是放款基准的证据，证据只能被更新不能被撤回。</p>
 *
 * <p><b>不可逆</b>：{@code EXPIRED}/{@code SUPERSEDED} 是终态，不允许改回 ACTIVE。
 * 需要重新作基准就写新行（P20「重估不回溯」的存储保证，V55 表注释）。
 * 这条不许靠调用方自觉，{@link #canMoveTo} 在用例层判，库里 {@code uk_val_active}
 * 再兜住「同 VIN 两行 ACTIVE」这一种最坏结果。</p>
 */
public enum ValuationStatus {

    /** 草稿：结论已算出但尚未生效，不参与闸门。 */
    DRAFT,

    /** 生效中：同 VIN 同时最多一行（V55 {@code uk_val_active}）。 */
    ACTIVE,

    /** 已过有效期（{@code ValuationExpiryJob} 置位）。 */
    EXPIRED,

    /** 被新结论取代（新行生效的同一事务内置位）。 */
    SUPERSEDED;

    /** 是否终态：终态行只读，任何改写都视为改历史。 */
    public boolean isTerminal() {
        return this == EXPIRED || this == SUPERSEDED;
    }

    /** 是否参与放款闸门（只有 ACTIVE 参与，且还要另判 valid_until）。 */
    public boolean isActive() {
        return this == ACTIVE;
    }

    /**
     * 状态迁移是否合法（D03 §3.7）。
     *
     * <p>规则表写死在代码里而不是配置：允许运维「加一条迁移」等于给估值状态开旁路，
     * 而 {@code SUPERSEDED → ACTIVE} 这种迁移的实质是复活一份已被取代的放款基准。</p>
     */
    public boolean canMoveTo(ValuationStatus target) {
        if (target == null || target == this) {
            return false;
        }
        switch (this) {
            case DRAFT:
                return target == ACTIVE || target == SUPERSEDED;
            case ACTIVE:
                return target == EXPIRED || target == SUPERSEDED;
            default:
                return false;
        }
    }

    /** 解析入参词；非法词由用例层翻成 {@code VALID_001}。 */
    public static ValuationStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("估值状态不能为空");
        }
        return ValuationStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
