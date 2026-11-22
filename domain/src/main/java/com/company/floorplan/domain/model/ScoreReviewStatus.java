package com.company.floorplan.domain.model;

/**
 * 双维评级评审流水的状态（USED 域 R1.3）。
 *
 * <p><b>取值定源</b>：V46 的 {@code dealer_score_reviews.status}
 * —— {@code CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED'))}。
 * D04 §3 没有为这张表写枚举段（本表是 V46 内为承载 SOD 证据而新增的，
 * 见 V46 注释与实现说明的"新增表面向收口方的待裁决项"），
 * 所以<b>取值集的唯一来源是库侧 CHECK</b>，本枚举与之逐字对齐。</p>
 *
 * <p><b>为什么不建状态机类</b>：三值之间只有 {@code PENDING → CONFIRMED} 与
 * {@code PENDING → CANCELLED} 两条边（终态不可回退，历史行只追加），
 * 转移条件与"第二人签认"绑在一起，已由
 * {@code DealerScoreReview#confirm(String)} 与
 * {@code DealerScoreReviewRepository#confirm} 的 SQL 状态守卫两处把关；
 * 再抽一个 {@code XxxStateMachine} 只会多出第三处需要同步维护的白名单
 * （D00 §8 约定③还要求状态机配单元测试，本轮禁测，不新起）。</p>
 */
public enum ScoreReviewStatus {

    /** 候选评分，尚未第二人签认；此状态的分数<b>不得</b>回写 {@code dealers} 两列（G-A4）。 */
    PENDING("待签认"),

    /** 已由第二人签认的终值；只有此状态允许回写 {@code dealers.operating_score/stock_quality_score}。 */
    CONFIRMED("已签认"),

    /** 作废（录入错误、评审年度变更等）；历史行保留，不物理删除。 */
    CANCELLED("已作废");

    /** 中文展示名。 */
    private final String label;

    ScoreReviewStatus(String label) {
        this.label = label;
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
     * 是否为可被签认/作废的在途候选。
     *
     * @return {@code PENDING} 返回 true
     */
    public boolean isPending() {
        return this == PENDING;
    }

    /**
     * 是否为终态（终态不可再流转，只能新开一行）。
     *
     * @return {@code CONFIRMED} 或 {@code CANCELLED} 返回 true
     */
    public boolean isTerminal() {
        return this == CONFIRMED || this == CANCELLED;
    }
}
