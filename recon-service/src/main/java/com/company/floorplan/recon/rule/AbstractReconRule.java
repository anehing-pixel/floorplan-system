package com.company.floorplan.recon.rule;

import com.company.floorplan.recon.model.Comparison;
import com.company.floorplan.recon.model.ReconDiff;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则模板：把"遍历事实 → 判定 → 构造差异"的公共骨架收在一处，
 * 子类只回答三件事：是不是差异、差异类型是什么、怎么解释。
 *
 * <p>{@link #evaluate(List)} 声明为 final，防止子类塞入 IO 或读时钟把纯函数破坏掉——
 * 这是"8 条规则可单测"这一约束在代码结构上的落点，而不是靠编码约定。</p>
 */
public abstract class AbstractReconRule implements ReconRule {

    private final ReconRuleCode definition;

    protected AbstractReconRule(ReconRuleCode definition) {
        this.definition = definition;
    }

    /** 单条事实是否构成差异。 */
    protected abstract boolean breached(Comparison fact);

    /** 差异说明（含口径与差额，争议时可自证）。 */
    protected abstract String detail(Comparison fact);

    @Override
    public final String code() {
        return definition.name();
    }

    @Override
    public final String title() {
        return definition.title();
    }

    /** 差异类型来自规则目录，避免各实现自由命名导致下游统计对不上。 */
    protected final String diffType() {
        return definition.diffType();
    }

    @Override
    public final List<ReconDiff> evaluate(List<Comparison> facts) {
        if (facts == null || facts.isEmpty()) {
            return List.of();
        }
        List<ReconDiff> diffs = new ArrayList<>();
        for (Comparison fact : facts) {
            if (fact == null || fact.subject() == null || fact.subject().isBlank()) {
                continue; // 无对拍对象的行无法落库（subject NOT NULL），跳过
            }
            if (breached(fact)) {
                diffs.add(ReconDiff.pending(code(), fact, diffType(), detail(fact)));
            }
        }
        return diffs;
    }
}
