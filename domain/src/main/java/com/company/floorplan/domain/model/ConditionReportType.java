package com.company.floorplan.domain.model;

/**
 * 車況票類型（USED 域 R2.2）。
 *
 * <p><b>取值定源</b>：D04 §3.8 的 {@code vehicle_condition_reports.report_type}
 * —— {@code VARCHAR(24) CHECK ∈ {AUCTION_INSPECTION(検査票), WMS_INBOUND_RECHECK(入库复核),
 * OWN_AUDIT(自盘)}}；D06 §2.1 UC-R2-2 的「report_type 三值」与之一致。
 * DB 侧双层约束在 V51 的 {@code ck_vcr_report_type}（{@code NOT NULL}，本表是事实台账，
 * 不允许"不知道是哪类票"的行）。</p>
 *
 * <p><b>为什么本包不建 {@code ConditionReportSource} 之类的第二个枚举</b>：
 * D04 §3.8 的「来源」已经由 {@code source_message_id}（报文引用）与本列表达，
 * 再造一个来源枚举会与 {@link MileageSource} 形成第三套口径。</p>
 */
public enum ConditionReportType {

    /** 検査票（拍卖场事前検査报告）。 */
    AUCTION_INSPECTION("検査票"),

    /** 入库复核（WMS 侧实车复验）。 */
    WMS_INBOUND_RECHECK("入库复核"),

    /** 自盘（本公司盘点）。 */
    OWN_AUDIT("自盘");

    /** 中文展示名。 */
    private final String label;

    ConditionReportType(String label) {
        this.label = label;
    }

    /**
     * 展示名。
     *
     * @return 展示名
     */
    public String getLabel() {
        return label;
    }
}
