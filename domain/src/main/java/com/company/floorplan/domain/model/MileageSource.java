package com.company.floorplan.domain.model;

/**
 * 里程来源（USED R2，{@code vehicle_units.mileage_source}）。
 *
 * <p><b>取值域以库内 CHECK 为准，不照抄 D06 §2.4 的名字。</b>
 * D06 那份优先级清单写的是 {@code WMS_SNAPSHOT / AUCTION_TICKET / REGISTRY / MANUAL}，
 * 而 V50 落库的 {@code ck_vu_mileage_source} 只允许
 * {@code AUCTION_REPORT / WMS_INBOUND / OWN_AUDIT / DEALER_DECLARED / UNKNOWN} 五个值。
 * 迁移已落库禁改（AGENTS §6.1），而这两个名字指向的是同一批事实，所以这里按库内字典建模，
 * 对应关系记在下面；D06 独有的 {@code REGISTRY}（登録情報＝陸運局滞后登记）库里没有对应值，
 * 归入 {@link #DEALER_DECLARED}（同为「非实物观测的申报类来源」），已列为待签字项。</p>
 *
 * <p><b>优先级就是本枚举的声明顺序</b>（{@link #priority()} 取 {@code ordinal()}）：
 * 实物观测 &gt; 自盘 &gt; 票面 &gt; 申报 &gt; 未知。D06 §2.4 的原话是
 * 「WMS_INBOUND / WMS_SNAPSHOT（实物观测）&gt; AUCTION_TICKET（票面）&gt; REGISTRY（登记，滞后）
 * &gt; MANUAL &gt; UNKNOWN」。<b>顺序不要随手调</b>：{@code MileageResolutionPolicy} 直接吃 ordinal，
 * 调换声明顺序等于改业务口径，而不只是改代码风格。</p>
 */
public enum MileageSource {

    /** WMS 入库实车读取（最可信：人在车里、表在车上）。 */
    WMS_INBOUND,

    /** 自营盘点（D06 的 {@code WMS_SNAPSHOT} 与 {@code MANUAL} 都落在这里：自己数出来的）。 */
    OWN_AUDIT,

    /** 拍卖票面（落札明細书上的走行距離，日本二手车市场的主流来源，但可能被回表）。 */
    AUCTION_REPORT,

    /** 车商/登记申报（D06 的 {@code REGISTRY} 与 {@code MANUAL}，滞后且非实物，仅兜底）。 */
    DEALER_DECLARED,

    /**
     * 未知。
     *
     * <p>库内 {@code ck_vu_mileage_unknown} 规定：来源是 UNKNOWN 时 {@code mileage} <b>必须为 NULL</b>。
     * 这条约束的意图是「不许用一个没有来源的里程数参与估值」——
     * 所以「全为 UNKNOWN」这一步在业务上是<b>允许建账但禁止放款</b>（D06 §2.4），
     * 不是数据缺失的借口。</p>
     */
    UNKNOWN;

    /** 优先级序号，越小越可信；与声明顺序同源，避免两处各维护一份表而过期。 */
    public int priority() {
        return ordinal();
    }

    /** 是否比 {@code other} 更可信；{@code other} 为 null 视为不可比（本值胜出）。 */
    public boolean outranks(MileageSource other) {
        return other == null || priority() < other.priority();
    }

    /**
     * 按库内字典值解析；{@code null}/空白 → {@link #UNKNOWN}。
     *
     * <p>未知字面量<b>不</b>降级成 UNKNOWN 而是抛出来：那说明调用方给了一个库里存不下的值，
     * 静默转成 UNKNOWN 会让「里程被抹掉」这种数据损失看起来像正常业务分支。</p>
     */
    public static MileageSource from(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("未知的里程来源: " + raw, ex);
        }
    }
}
