package com.company.floorplan.domain.model;

/**
 * 回执的<b>确认权威</b>三值——放车放证链路里唯一说明"这句话凭什么算数"的字段。
 *
 * <p><b>为什么它不能由调用方传入</b>（D09 §7.1 / D11 A1 前置 / D13 G6 定案）：
 * 权威值就是"证据强度"本身。让提交人自选证据强度，等于允许把"我自己说放了"
 * 标成"监管方回执说放了"，而后者是唯一能推结清、能解押、能放证的那个值——
 * 整条链的账实闭合会退化成一句自我声明。</p>
 *
 * <p><b>因此推导规则是唯一的写入路径</b>（{@code derive}）：
 * 只有携带 {@code inbound_message_id} 的行才可能是 PARTNER_RECEIPT，
 * 其余路径一律 INTERNAL_WITNESS 或 DEALER_SELF_DECLARED。
 * 数据库侧由 V67 的 {@code chk_receipt_partner_needs_inbox} 与
 * 部分唯一索引 {@code ux_receipt_inbox} 再钉一层（三层防伪：应用推导 / 同事务写入 / DB 约束）。</p>
 *
 * <p><b>风险分级不降 LEGACY（D13 G6 采纳口径）</b>：{@code INTERNAL_WITNESS} 在
 * LEGACY 语义下仍可结清（V24 折中，A2.5 要求这条回归不破）；
 * 在 USED 语义下<b>不可</b>结清（A2.4）。同一个值在两条产品线上的效力不同，
 * 所以判定必须读 {@link ReleaseProductLine}，不许在本枚枚举上写"能不能结清"。</p>
 */
public enum ConfirmationAuthority {

    /**
     * 合作方权威回执：由入站报文（I-07 / portal / partner API）落库带回执 id 才能取得。
     * <p>USED 域唯一可推 {@code SETTLED} / 放证 {@code RELEASED} / 解押的权威。</p>
     */
    PARTNER_RECEIPT("合作方权威回执"),

    /**
     * 内部双人见证：降级人工代录（R7.7）产生的权威。
     * <p>通道标识落在既有列 {@code release_receipts.source}（V19:162 定义
     * PORTAL/API/MANUAL/MQ 四类，V24:48 原文「source=MANUAL 时<b>一般为</b>
     * INTERNAL_WITNESS」——那个"一般"正是 V67 要用 CHECK 消掉的可伪造面）。
     * USED 域不得据此结清；LEGACY 域保持 V24 折中可用（A2.5 的回归要求）。</p>
     */
    INTERNAL_WITNESS("内部双人见证"),

    /**
     * 经销商自行声明：证据强度最弱，只用于登记事实，不产生任何资金或担保效力。
     */
    DEALER_SELF_DECLARED("经销商自行声明");

    private final String label;

    ConfirmationAuthority(String label) {
        this.label = label;
    }

    /** 中文标签。 */
    public String getLabel() {
        return label;
    }

    /**
     * 唯一推导入口：有入站回执 id 才是合作方权威，否则不是。
     *
     * <p>这个方法<b>故意不接收 authority 参数</b>——一旦接收，调用方就会把它当成
     * "透传字段"，而 A1.3/INV-5 要求的是内部路径根本写不出 PARTNER_RECEIPT。
     * 期望的 partner 权威（例如内部端点声称 partner）由应用层在进入本方法之前
     * 用 {@code SECURITY_001} 拒绝，而不是在这里"校正"。</p>
     *
     * @param inboundMessageId 入站报文 id，可为 null
     * @return 推导出的权威值；{@code inboundMessageId} 非空即 PARTNER_RECEIPT
     */
    public static ConfirmationAuthority derive(Object inboundMessageId) {
        return inboundMessageId != null ? PARTNER_RECEIPT : INTERNAL_WITNESS;
    }

    /** 是否为可用于 USED 域推进资金/担保结论的权威。 */
    public boolean isPartnerAuthoritative() {
        return this == PARTNER_RECEIPT;
    }

    /** 按数据库文本解析；空值返回 null（存量行允许 authority 为空，收紧在 V67 之后）。 */
    public static ConfirmationAuthority from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
