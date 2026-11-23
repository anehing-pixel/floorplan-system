package com.company.floorplan.domain.model;

/**
 * WMS 在库快照的状态（三值）。
 *
 * <p>落库：V66__wms_snapshot_reconcile.sql:44-45 的 {@code chk_wms_snapshot_status}，
 * 并配部分唯一索引 {@code uk_wms_snapshot_active}
 * （{@code (warehouse_custodian_code, snapshot_date) WHERE status <> 'SUPERSEDED'}，V66:49-51）
 * 保证"同仓同业务日只有一份生效快照"。</p>
 *
 * <p><b>SUPERSEDED 不是错误态</b>：D07 §7.5 的重算入口 {@code POST /api/wms/snapshots/{id}/recompute}
 * 与"同 biz_date 再来一份"都会把旧快照置此态，同时让上一次对平结果失效
 * （错误码 {@code WMS_003}，409）。把它当失败会诱导入站侧重试覆盖，
 * 而覆盖后旧差异必须能追溯到来源快照——所以只改状态、绝不删行。</p>
 */
public enum WmsSnapshotStatus {

    /** 已受理：报文落库，明细未展开或对平未跑。 */
    RECEIVED("已受理"),

    /** 已参与对账：明细齐全，对平结果挂在这份快照上。 */
    APPLIED("已参与对账"),

    /** 被同日重推取代：保留供取证，不参与当前对平。 */
    SUPERSEDED("已被取代");

    private final String label;

    WmsSnapshotStatus(String label) {
        this.label = label;
    }

    /** 中文标签。 */
    public String getLabel() {
        return label;
    }

    /** 是否仍是"当前生效"的快照（差异重算与账实勾稽只读这一类）。 */
    public boolean isActive() {
        return this != SUPERSEDED;
    }

    /** 解析数据库文本。 */
    public static WmsSnapshotStatus from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
