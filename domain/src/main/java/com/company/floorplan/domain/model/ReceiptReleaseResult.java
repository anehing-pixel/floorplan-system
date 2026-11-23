package com.company.floorplan.domain.model;

/**
 * 单条回执声明的<b>释放结果</b>（四值）。
 *
 * <p>落库列：V68__release_item_receiver.sql:176-178 的 {@code chk_receipt_result}
 * 钉在 {@code release_receipts.release_result}（可空——存量回执没有这一列的值，
 * 新链路必须写）。</p>
 *
 * <p><b>与 {@link UsedReleaseItemStatus} 的区别</b>：本枚举是"这份回执<b>声称</b>发生了什么"，
 * 项状态是"系统<b>认定</b>发生了什么"。两者可以不一致——例如声称 RELEASED 但项集对不上，
 * 就要按 {@code RELS_004} 落 PARTIAL。这个分层是 D09 §7.2 项集校验能成立的前提：
 * 如果直接把回执值写进项状态，"校验"就没有对象了。</p>
 */
public enum ReceiptReleaseResult {

    /** 声称对方已受理（尚未实际释放）。 */
    ACKNOWLEDGED("已受理"),

    /** 声称全部释放项已完成。 */
    RELEASED("已释放"),

    /** 声称部分释放（须与指令项集比对后聚合，A3.1）。 */
    PARTIAL("部分释放"),

    /** 声称拒绝（须带 {@code refuse_reason}，V68 列）。 */
    REFUSED("拒绝");

    private final String label;

    ReceiptReleaseResult(String label) {
        this.label = label;
    }

    /** 中文标签。 */
    public String getLabel() {
        return label;
    }

    /** 是否已经产生"实放"事实（只有 RELEASED/PARTIAL 会推进项状态与证据链）。 */
    public boolean assertsRelease() {
        return this == RELEASED || this == PARTIAL;
    }

    /** 解析数据库文本，空值返回 null（存量行为 NULL，由调用方决定如何解释）。 */
    public static ReceiptReleaseResult from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
