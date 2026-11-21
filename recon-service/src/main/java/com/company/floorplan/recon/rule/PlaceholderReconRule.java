package com.company.floorplan.recon.rule;

import com.company.floorplan.recon.model.Comparison;
import com.company.floorplan.recon.model.ReconDiff;

import java.util.List;

/**
 * 占位规则：R5-R8 的等待确认实现。
 *
 * <p><b>为什么占位也必须是一个 ReconRule bean</b>：编排层对 8 条规则一视同仁地跑；
 * 若未实现的规则直接不存在，报告里就只剩 4 条，"另外 4 条去哪了"会变成悬案。
 * 占位规则出现在执行清单里、但产出 0 条差异，明确表达"跑了，还没实现，所以无可判定项"。</p>
 *
 * <p><b>TODO（业务确认后逐条落地）</b>：
 * <ul>
 *   <li>R5 利息计提 vs 计息流水累计：确认计提日切与舍入口径；</li>
 *   <li>R6 担保登记状态 vs 担保物台账：确认登记编号映射；</li>
 *   <li>R7 库龄递减计划 vs 实际还款进度：确认期数与容差；</li>
 *   <li>R8 处置损失 vs 账务核销：确认损失认定与冲销时点差。</li>
 * </ul>
 * 落地方式：参照 R1-R4，新增一个 AbstractReconRule 子类 + 仓储中的取数 SQL，
 * 并把 ReconRuleCode 中该条 implemented 置 true。编排层无需改动。</p>
 */
public class PlaceholderReconRule implements ReconRule {

    private final ReconRuleCode definition;

    public PlaceholderReconRule(ReconRuleCode definition) {
        if (definition.implemented()) {
            throw new IllegalArgumentException("已实现的规则不能用占位实现：" + definition);
        }
        this.definition = definition;
    }

    @Override
    public String code() {
        return definition.name();
    }

    @Override
    public String title() {
        return definition.title();
    }

    /**
     * TODO：当前不读账本、不判定，固定返回空列表。
     * 绝不返回"看起来通过"的假差异——未实现就必须显式显示为未实现。
     */
    @Override
    public List<ReconDiff> evaluate(List<Comparison> facts) {
        return List.of();
    }
}
