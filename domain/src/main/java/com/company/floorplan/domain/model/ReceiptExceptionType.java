package com.company.floorplan.domain.model;

/**
 * 回执异常类型（九值）。
 *
 * <p>取值源：<b>D00 §7:408 的 {@code ReceiptExceptionType} 定名</b>，
 * 落库为 V68__release_item_receiver.sql:156-158 的 {@code chk_receipt_exception_type}。
 * 其中 NONE/REJECTED/PARTIAL/DELAYED 四个是 V19:157 注释里就写明的旧四类
 * （那列一直没有 CHECK），本轮按 D00 §7 补出五个車検/实物类异常值。</p>
 *
 * <p><b>加 {@code NONE} 而不是留 NULL</b>：D00 §7 同一行明确
 * 「现状 {@code exception_type} 从不写入，本文要求必填」。NULL 与"确认无异常"
 * 在 SQL 判断里长得一样，会把"没人填过"漏成"没有异常"，进而不进降级报表。
 * 【已登记偏离】D00 的"必填"本轮只做到 <b>CHECK 值域 + 存量回填 NONE + 列默认 NONE</b>，
 * <b>没有加 NOT NULL</b>——那会让 LEGACY 里不写该列的 {@code saveReceipt} 路径当场落库失败，
 * 属 §5 禁区外的既有行为变更，留给收口方裁决。</p>
 */
public enum ReceiptExceptionType {

    /** 无异常（显式值，不用 NULL 表达，见类头）。 */
    NONE("无异常"),

    /** 整单拒收。 */
    REJECTED("拒收"),

    /** 部分释放（与 {@link UsedReleaseStatus#PARTIAL} 对应，项集不一致）。 */
    PARTIAL("部分释放"),

    /** 延迟交付（超 {@code valid_until} 前收到但迟了，计入时效统计）。 */
    DELAYED("延迟交付"),

    /** 现场找不到这台车（账实断裂的直接证据，触发 MISSING_IN_WMS 比对）。 */
    VEHICLE_NOT_FOUND("车辆未找到"),

    /** 单证与车辆不符（証書番/登録番号/VIN 三者对不上）。 */
    DOC_MISMATCH("单证不符"),

    /** 里程差异超出容忍带宽（证据链字段，V19 的 {@code odometer_km} 由此才有解释对象）。 */
    ODOMETER_DIFF("里程差异"),

    /** 车况异常（与 diff_type 侧的 {@code CONDITION_DIFF} 是同一事实的两个登记面）。 */
    DAMAGE_FOUND("车况异常"),

    /** 封条破损（监管方仓/拍卖场保管链断裂，属 D09 §11 证据链要否证的实物项之一）。 */
    SEALED_BROKEN("封条破损");

    private final String label;

    ReceiptExceptionType(String label) {
        this.label = label;
    }

    /** 中文标签。 */
    public String getLabel() {
        return label;
    }

    /** 是否表示"确实发生了异常"（NONE 之外全是），供降级报表与证据链完整性判定复用。 */
    public boolean isException() {
        return this != NONE;
    }

    /**
     * 是否属于"实物与单证对不上"这一类硬异常。
     *
     * <p>这一类不能靠"下一份回执"覆盖掉：必须先做差异承认或实地盘点
     * （D09 §11 证据链条件里的账实一致项），否则就是把断裂洗成完成。</p>
     */
    public boolean requiresReconcileAck() {
        return this == VEHICLE_NOT_FOUND || this == DOC_MISMATCH
                || this == DAMAGE_FOUND || this == SEALED_BROKEN;
    }

    /** 解析数据库文本；V68 回填前存量 NULL 由调用方先置 NONE，本方法不猜。 */
    public static ReceiptExceptionType from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
