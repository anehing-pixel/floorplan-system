package com.company.floorplan.recon.rule;

import com.company.floorplan.recon.model.Comparison;

/**
 * 精确相等型对拍规则（R5–R8 共用的判定骨架）。
 *
 * <p><b>ADR-Q27 / 《业务口径总定源》裁决</b>：对拍规则一律采用
 * 「两口径数值精确相等才无差异，不等即产出差异」，不做容差、不做舍入猜测、
 * 不做时间窗猜测。状态/计数型规则由仓储把布尔关系或笔数编码为整数（见 R2），
 * 判定层因此与金额型规则完全同构——差异只取决于 {@code expected != actual}。</p>
 *
 * <p><b>为什么四条共用一个类而不是各写一个</b>：R5–R8 的判定逻辑逐字相同，
 * 只有编号、左右口径标签不同。把差异类型与标签收敛到 {@link ReconRuleCode} 与构造参数，
 * 避免四份复制粘贴的 {@code expected != actual} 各自漂移；业务语义留在
 * 仓储的取数 SQL 与左右标签里，仍可逐条审阅。</p>
 *
 * <p>纯函数约束由 {@link AbstractReconRule#evaluate} 的 final 保证：不读库、不读时钟。</p>
 */
public class ExactValueReconRule extends AbstractReconRule {

    private final String expectedLabel;
    private final String actualLabel;

    /**
     * @param definition    规则编号（决定差异类型与标题）
     * @param expectedLabel 期望侧口径名，用于差异说明
     * @param actualLabel   实际侧口径名，用于差异说明
     */
    public ExactValueReconRule(ReconRuleCode definition, String expectedLabel, String actualLabel) {
        super(definition);
        if (!definition.implemented()) {
            throw new IllegalArgumentException("占位规则不能用精确相等实现：" + definition);
        }
        this.expectedLabel = expectedLabel;
        this.actualLabel = actualLabel;
    }

    @Override
    protected boolean breached(Comparison fact) {
        return fact.expected() != fact.actual();
    }

    @Override
    protected String detail(Comparison fact) {
        return expectedLabel + "=" + fact.expected()
                + "，" + actualLabel + "=" + fact.actual()
                + "，差额(期望-实际)=" + (fact.expected() - fact.actual());
    }
}
