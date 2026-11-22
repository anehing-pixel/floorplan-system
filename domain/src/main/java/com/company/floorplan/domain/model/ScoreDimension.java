package com.company.floorplan.domain.model;

/**
 * 双维评级的维度（USED 域 R1.3；D06 §1.2 {@code scoreDimension(dealerId, dimension, input, idemKey)} 的入参）。
 *
 * <p><b>取值定源</b>：D06 §1.4 三口径表 + {@code dealers}/{@code dealer_score_reviews} 的两列列名
 * （{@code operating_score}／{@code stock_quality_score}，D04 §5.2 与 V46）。
 * 本枚举<b>只做命名，不做口径</b>：两维各自的算法、分档、权重与合成方式是
 * {@code [待定源 U-11]}（D06 §1.4 明写"三者不互相推导、不共享中间分"），
 * 因此这里不挂权重、不设满分常量、也不提供"两维→一个等级"的任何推导方法。
 * 一旦在这里写了换算，未签字的口径就被焊进代码了。</p>
 *
 * <p><b>为什么两维要分开录</b>：D06 §1.4 给出的计算方不同——
 * 经营资质分是人工评（+ G-A4 第二人确认），在库车辆质量分由
 * {@code StockQualityScoreCalculator} 每次重估刷新。两者产出时点不同，
 * 所以一条 {@code PENDING} 候选行允许只填其中一维（V46 已把两列放宽为可空，
 * 并由 {@code ck_dsr_score_present} 保证"至少填一维"）。</p>
 *
 * <p><b>{@code StockQualityScoreCalculator} 不在本包</b>：那是 U-11 签字之后的算法活，
 * 本包只负责把"已经算出/评出的分数"按维度落到评审流水并走二人签认。</p>
 */
public enum ScoreDimension {

    /** 经营资质分（資本金／年商／展位／年限／整备资质的合成分；口径 U-11 未签字）。 */
    OPERATING("经营资质分", "operating_score"),

    /** 在库车辆质量分（年式／里程／修復歴／評価点的加权；口径 U-11 未签字，计算方在 Calculator 侧）。 */
    STOCK_QUALITY("在库车辆质量分", "stock_quality_score");

    /** 中文展示名。 */
    private final String label;

    /** 对应的库列名（{@code dealers} 与 {@code dealer_score_reviews} 同名）。 */
    private final String columnName;

    ScoreDimension(String label, String columnName) {
        this.label = label;
        this.columnName = columnName;
    }

    /**
     * 中文展示名。
     *
     * @return 展示名
     */
    public String getLabel() {
        return label;
    }

    /**
     * 对应库列名。
     *
     * <p>给仓储与差异工单用：工单要说清"哪一列变了"，而不是让读工单的人去猜枚举与列的映射。
     * 本值是常量，不参与 SQL 拼接（仓储里两列各自显式绑定，见
     * {@code DealerScoreReviewRepository#updateCandidateDimensionScore}）。</p>
     *
     * @return 列名
     */
    public String getColumnName() {
        return columnName;
    }
}
