package com.company.floorplan.domain.model;

/**
 * 修復歴（USED R2.1 / R2.3，{@code vehicle_units.repair_history} 与
 * {@code vehicle_condition_reports.repair_history} 共用取值域）。
 *
 * <p>取值集以库内 CHECK 为准（V50 {@code ck_vu_repair_history}、V51
 * {@code ck_vcr_repair_history}）：{@code NONE / MINOR / MAJOR / UNKNOWN}，
 * 即 D00 §8.1 RepairHistoryFlag 定源四值。两处列都<b>允许 NULL</b>——
 * NULL 是「LEGACY 存量行从未采集」，UNKNOWN 是「采集过但判不清」，
 * 与里程那对语义（V50 mileage_source 注释）完全同构，不得在数据层合并成一类。</p>
 *
 * <p><b>取严方向（D06 §2.3）</b>：任一证据说是 MAJOR，主数据就是 MAJOR；
 * UNKNOWN 只能盖住 NULL，<b>不能</b>把已知的 NONE 改成 UNKNOWN（「未知」不该让历史变差，
 * 也不该让已经确认的轻微修复被抹掉）。{@link #severityRank()} 就是这个偏序。</p>
 */
public enum RepairHistory {

    /** 無修復（最轻）。 */
    NONE(0),

    /** 軽微な修復：换件/钣金，未切焊。 */
    MINOR(1),

    /** 修復歴あり：框架/切焊级别，直接压估值（R3 取带的主输入之一）。 */
    MAJOR(2),

    /**
     * 判定不明。
     *
     * <p>排最后（rank 3）而不是排中间：它的业务效果取决于「有没有已知结论」——
     * {@code MileageResolutionPolicy} 之外的取严规则是「UNKNOWN 不优于已知」，
     * 所以比较时先看 {@link #isKnown()}，rank 只在同为已知值时用来取严。</p>
     */
    UNKNOWN(3);

    private final int severityRank;

    RepairHistory(int severityRank) {
        this.severityRank = severityRank;
    }

    /** 严重度序（越大越严重）；{@link #UNKNOWN} 不参与「取更严重」的比较，见类注释。 */
    public int severityRank() {
        return severityRank;
    }

    /** 是否是一个有内容的结论（NONE/MINOR/MAJOR）。 */
    public boolean isKnown() {
        return this != UNKNOWN;
    }

    /**
     * 取严合并（D06 §2.3）：已知结论优先，两者都已知时取更严重的一方。
     *
     * <p>为什么 {@code MAJOR + UNKNOWN = MAJOR} 而不是 UNKNOWN：估值要的是
     * 「我们已经知道它撞过」，把确定的事实换成不确定的描述等于丢证据。</p>
     */
    public RepairHistory stricterThan(RepairHistory other) {
        if (other == null) {
            return this;
        }
        if (!this.isKnown()) {
            return other.isKnown() ? other : this;
        }
        if (!other.isKnown()) {
            return this;
        }
        return other.severityRank > this.severityRank ? other : this;
    }

    /** 解析库内文本；{@code null}/空白返回 {@code null}（＝从未采集，不同于 UNKNOWN）。 */
    public static RepairHistory from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("未知的修復歴取值: " + raw, ex);
        }
    }
}
