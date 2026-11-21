package com.company.floorplan.domain.model;

import java.util.Set;

/**
 * 经销商（借款人）状态枚举。
 *
 * <p><b>为什么单独建枚举</b>：状态值会散落在多处判断里
 * （准入校验、垫款建账、额度冻结、后台编辑…）。写成字符串字面量时，
 * 一次拼写错误就是"静默失效"——不会报错，只是条件永远不成立。
 * 枚举把取值收敛到一处，编译期即可发现遗漏。</p>
 *
 * <p><b>流转白名单</b>：与 {@link VehicleStatus} 保持同一套约定——
 * 终态不可回滚。{@code TERMINATED} 是终态，因为合作终止意味着
 * 该主体不再承接新业务；若允许复活，会与历史台账的语义打架。</p>
 */
public enum DealerStatus {

    /** 正常经营：可发生新的垫款业务。 */
    ACTIVE("正常经营"),

    /** 暂停合作：存量业务照常推进，但禁止新增垫款建账。 */
    SUSPENDED("暂停合作"),

    /** 终止合作：不再承接任何新业务（终态）。 */
    TERMINATED("终止合作");

    /** 终态集合：进入终态后不允许再流转。 */
    private static final Set<DealerStatus> TERMINAL = Set.of(TERMINATED);

    private final String label;

    DealerStatus(String label) {
        this.label = label;
    }

    /**
     * 判断是否允许流转到目标状态。
     *
     * @param target 目标状态
     * @return true 表示允许流转
     *
     * <p><b>白名单</b>：
     * <pre>
     *   ACTIVE     → SUSPENDED / TERMINATED
     *   SUSPENDED  → ACTIVE / TERMINATED
     *   TERMINATED → 不可再流转（终态）
     * </pre>
     * 同状态流转（target == this）视为无操作，视为允许——
     * 后台重复提交同一个状态不应该报错。
     */
    public boolean canTransitTo(DealerStatus target) {
        if (target == null) {
            return false;
        }
        if (this == target) {
            return true;
        }
        return switch (this) {
            case ACTIVE -> target == SUSPENDED || target == TERMINATED;
            case SUSPENDED -> target == ACTIVE || target == TERMINATED;
            case TERMINATED -> false;
        };
    }

    /** 是否为终态。 */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** 是否仍可承接新业务（新增垫款建账）。 */
    public boolean canAcceptNewBusiness() {
        return this == ACTIVE;
    }

    /** 中文展示名。 */
    public String getLabel() {
        return label;
    }
}
