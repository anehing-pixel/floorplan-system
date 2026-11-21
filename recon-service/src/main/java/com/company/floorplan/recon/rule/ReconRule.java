package com.company.floorplan.recon.rule;

import com.company.floorplan.recon.model.Comparison;
import com.company.floorplan.recon.model.ReconDiff;

import java.util.List;

/**
 * 对拍规则的判定契约：<b>纯函数</b>。
 *
 * <p>输入是仓储读好的账本事实 {@link Comparison} 列表，输出是差异列表。
 * 不查库、不写库、不读时钟、不读配置、不改入参——同样的输入永远得到同样的输出。
 * 这条约束是 8 条规则都能脱离数据库做参数化单测的前提。</p>
 *
 * <p>SQL 负责"把两个口径的数取出来"，本接口负责"这两个数该不该算差异"。
 * 把判定留在 Java 侧而不是写进 SQL 的 WHERE，是为了让判定可解释、可单测、
 * 可复用（同一判定可用于离线回放历史账本快照）。</p>
 */
public interface ReconRule {

    /** 规则编号，如 R1。 */
    String code();

    /** 规则中文名。 */
    String title();

    /**
     * 判定一批账本事实。
     *
     * @param facts 账本事实（由仓储读取；占位规则会收到空列表）
     * @return 差异列表；无差异返回空列表，绝不返回 null
     */
    List<ReconDiff> evaluate(List<Comparison> facts);
}
