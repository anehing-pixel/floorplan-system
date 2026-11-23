package com.company.floorplan.domain.model;

/**
 * USED 域放车指令的释放项类型（六值）。
 *
 * <p>定名源 D00 §7:405：在现有三值（{@code VEHICLE}/{@code CERTIFICATE}/{@code KEY}）上
 * 加 {@code INSPECTION_CERT}（車検証）/ {@code REGISTRATION_DOCS}（登记文件）/
 * {@code OUTBOUND_DOC}（出库单）。落库为 V68 的 {@code chk_roi_item_type}。</p>
 *
 * <p><b>{@code CERTIFICATE} 与 {@code INSPECTION_CERT} 必须分开</b>（D00 §7:120 明确裁决）：
 * 前者是 V19 注释定义的<b>车辆合格证</b>（不是法务局登记证明书），
 * 后者是日本車検証。USED 域<b>不复用</b> {@code release_orders.certificate_no} 存車検証番号，
 * 走 V67 新增的 {@code inspection_certificate_no}。合用一列会让 LEGACY 的合格证查询
 * 突然返回日本登记文书。</p>
 *
 * <p>为什么另建一枚而不是扩 release-service 的 {@code ReleaseItemType}：
 * {@code domain} 看不到 release-service（依赖方向相反），而 USED 的项类型要被
 * core 的解押编排与 release-service 的端点同时使用，只能落在公共祖先模块。
 * LEGACY 那枚与其 {@code defaultCustodianType()} 分支一律不动。</p>
 */
public enum UsedReleaseItemType {

    /** 实车。 */
    VEHICLE("实车"),

    /** 车辆合格证（LEGACY 语义，与車検証不是一回事）。 */
    CERTIFICATE("合格证"),

    /** 钥匙（日本渠道通常不扣钥匙，D03 环节④）。 */
    KEY("钥匙"),

    /** 車検証（USED 放证线的对象，R5/R7.2）。 */
    INSPECTION_CERT("車検証"),

    /** 登记文件（移転登録/抵当権设定/抹消的申请书与证明书，R5.6）。 */
    REGISTRATION_DOCS("登记文件"),

    /** 出库单（WMS 侧放行的凭据，R6.5）。 */
    OUTBOUND_DOC("出库单");

    private final String label;

    UsedReleaseItemType(String label) {
        this.label = label;
    }

    /** 中文标签。 */
    public String getLabel() {
        return label;
    }

    /**
     * 该项走的是<b>放证线</b>还是<b>放车线</b>。
     *
     * <p>D00 §7:405 的裁决原话是「放车线 vs 放证线分属不同接收方（R7.2）」：
     * 実车在监管方仓库或 WMS，証書在証書保管者手里，一条指令两类对象时
     * 必须拆成两个接收方各自回执，否则"车放了证没放"会被一份合并回执掩盖（A3.1）。</p>
     */
    public boolean isCertificateLine() {
        return this == INSPECTION_CERT || this == REGISTRATION_DOCS;
    }

    /** 该项是否属于"担保物本体"——放掉它就意味着担保失控，须有质押前置（C1/C3）。 */
    public boolean requiresPledgeFirst() {
        return this == INSPECTION_CERT;
    }

    /** 解析数据库文本；未知值抛异常，不静默归为 VEHICLE（项类型猜错等于接收方选错）。 */
    public static UsedReleaseItemType from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
