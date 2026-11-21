package com.company.floorplan.domain.model;

/**
 * 采购订单导入通道（ADR-007 决策四）。
 *
 * <p>两条通道<b>并存且长期有效</b>，不是成熟度替代关系：</p>
 * <ul>
 *   <li>{@link #API} —— 主机厂销售公司、区域总代理、拍卖平台、经销商 DMS/ERP 直连。
 *       数据源可信、有契约保证，<b>可豁免落库前人工确认</b>；</li>
 *   <li>{@link #EXCEL} —— 经销商提交的车辆采购清单（Excel/CSV）。
 *       面向长尾中小经销商与零星补录，<b>必须 preview → commit 人工确认</b>。</li>
 * </ul>
 *
 * <p>两条通道产出<b>同一份采购订单主单</b>，下游（放款校验、台账）无感知通道差异。</p>
 */
public enum IngestChannel {

    /** API 直连（上游系统推送）。 */
    API("API 直连"),

    /** Excel/CSV 批量导入（经销商提交清单）。 */
    EXCEL("Excel 导入");

    private final String label;

    IngestChannel(String label) {
        this.label = label;
    }

    /**
     * 该通道是否允许豁免落库前人工确认。
     *
     * @return true 表示可豁免（仅 API 通道）
     */
    public boolean allowsAutoConfirm() {
        return this == API;
    }

    /** 中文展示名。 */
    public String getLabel() {
        return label;
    }
}
