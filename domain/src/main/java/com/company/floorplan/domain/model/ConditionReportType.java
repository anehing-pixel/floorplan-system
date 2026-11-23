package com.company.floorplan.domain.model;

/**
 * 车况票类型（{@code vehicle_condition_reports.report_type}，三值）。
 *
 * <p>取值集 = D04 §3.8，落库为 V51 {@code ck_vcr_report_type}。
 * 三型的<b>可信度不同且用途不同</b>：検査票是拍卖场的第三方鉴定（估值/车况分的主证据），
 * 入库复核是监管仓的实物观测（里程高优先），自盘是我方自己数（里程次高优先，
 * 但只有自盘能覆盖「无外部票据」的批发购入车）。</p>
 *
 * <p>{@link #mileageSourceOf()} 把「票型」翻译成「里程来源」，这样
 * {@code MileageResolutionPolicy} 只需要理解 {@link MileageSource} 一套优先级，
 * 不必为每种票各写一条 if。</p>
 */
public enum ConditionReportType {

    /** 検査票（拍卖场鉴定书）。 */
    AUCTION_INSPECTION,

    /** 入库复核（监管仓收货时的实物检查）。 */
    WMS_INBOUND_RECHECK,

    /** 自盘（我方盘点）。 */
    OWN_AUDIT;

    /**
     * 该型票对应的里程来源。
     *
     * <p>検査票的里程是<b>票面</b>值（可能被回表）⟹ {@link MileageSource#AUCTION_REPORT}；
     * 入库复核是<b>实物</b>观测 ⟹ {@link MileageSource#WMS_INBOUND}；自盘是其次一级的实物
     * ⟹ {@link MileageSource#OWN_AUDIT}。</p>
     */
    public MileageSource mileageSourceOf() {
        return switch (this) {
            case AUCTION_INSPECTION -> MileageSource.AUCTION_REPORT;
            case WMS_INBOUND_RECHECK -> MileageSource.WMS_INBOUND;
            case OWN_AUDIT -> MileageSource.OWN_AUDIT;
        };
    }

    /** 解析库内文本；空白返回 {@code null}，未知字面量抛出（库里存不下的值不该静默降级）。 */
    public static ConditionReportType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("未知的车况票类型: " + raw, ex);
        }
    }
}
