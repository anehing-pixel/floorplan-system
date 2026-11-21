package com.company.floorplan.recon.rule;

import java.util.List;

/**
 * 规则执行清单：固定 8 条的顺序，编排层按此顺序逐条对拍。
 *
 * <p><b>为什么用一个集合对象而不是让 Spring 自动收集 List&lt;ReconRule&gt;</b>：
 * 自动收集的顺序不保证，而差异报告的编号顺序应当稳定可读（R1 到 R8）。
 * 显式清单把"8 条、有序、含占位"这件事变成一处可审阅的配置。</p>
 *
 * @param rules 按 R1..R8 排列的规则列表
 */
public record ReconRuleSet(List<ReconRule> rules) {

    public ReconRuleSet {
        rules = List.copyOf(rules);
    }
}
