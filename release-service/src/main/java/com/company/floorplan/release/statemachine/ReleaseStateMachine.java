package com.company.floorplan.release.statemachine;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.release.model.ReleaseStatus;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 出库指令状态机——<b>纯函数、无 Spring 依赖、无 I/O</b>。
 *
 * <p><b>为什么单独成类，而不是把规则塞进枚举或服务</b>：
 * 状态迁移是本服务最核心的业务不变量，必须能被单测穷举。
 * 放在服务里就要启动 Spring 上下文才能测；放在枚举里会和"状态语义"混在一起。
 * 这里做成静态纯函数，参数化单测可以把 6×6 种组合全部跑一遍。</p>
 *
 * <p><b>两条不可逾越的红线</b>（任务卡 T2-9 核心约束）：</p>
 * <ol>
 *   <li><b>未经 CONFIRMED 不得 RELEASED</b>：禁止"跳步放车"。
 *       {@code PENDING_RELEASE → RELEASED}、{@code SENT → RELEASED} 一律拒绝。</li>
 *   <li><b>未 RELEASED 不得 LIEN_DISCHARGED</b>：禁止"未放车先解抵"。
 *       解抵只能从 {@link ReleaseStatus#RELEASED} 出发，
 *       否则会出现"车还在库里、质押已解除"的资损敞口。</li>
 * </ol>
 *
 * <p><b>合法迁移白名单</b>（唯一入口）：
 * <pre>
 *   PENDING_RELEASE → SENT | CANCELLED
 *   SENT            → CONFIRMED | CANCELLED
 *   CONFIRMED       → RELEASED | CANCELLED
 *   RELEASED        → LIEN_DISCHARGED
 *   LIEN_DISCHARGED → （终态，不可再流转）
 *   CANCELLED       → （终态，不可再流转）
 * </pre>
 * 自迁移（A → A）同样拒绝：无状态变化的"流转"只会污染状态历史、掩盖真实动作。</p>
 */
public final class ReleaseStateMachine {

    private static final Map<ReleaseStatus, Set<ReleaseStatus>> ALLOWED;

    static {
        Map<ReleaseStatus, Set<ReleaseStatus>> table = new EnumMap<>(ReleaseStatus.class);
        table.put(ReleaseStatus.PENDING_RELEASE,
                EnumSet.of(ReleaseStatus.SENT, ReleaseStatus.CANCELLED));
        table.put(ReleaseStatus.SENT,
                EnumSet.of(ReleaseStatus.CONFIRMED, ReleaseStatus.CANCELLED));
        table.put(ReleaseStatus.CONFIRMED,
                EnumSet.of(ReleaseStatus.RELEASED, ReleaseStatus.CANCELLED));
        table.put(ReleaseStatus.RELEASED,
                EnumSet.of(ReleaseStatus.LIEN_DISCHARGED));
        table.put(ReleaseStatus.LIEN_DISCHARGED, Collections.emptySet());
        table.put(ReleaseStatus.CANCELLED, Collections.emptySet());
        ALLOWED = Collections.unmodifiableMap(table);
    }

    private ReleaseStateMachine() {
    }

    /**
     * 是否允许从 {@code from} 流转到 {@code to}（无副作用）。
     *
     * @param from 当前状态，允许为 null（建单前，无迁移可发生）
     * @param to   目标状态，允许为 null（一律拒绝）
     */
    public static boolean canTransit(ReleaseStatus from, ReleaseStatus to) {
        if (from == null || to == null) {
            return false;
        }
        Set<ReleaseStatus> targets = ALLOWED.get(from);
        return targets != null && targets.contains(to);
    }

    /**
     * 校验并拒绝非法流转，供应用服务在写库前调用。
     *
     * @throws BusinessException 非法流转时抛，错误码 VEHICLE_002（状态非法流转，HTTP 422）
     */
    public static void requireTransition(ReleaseStatus from, ReleaseStatus to) {
        if (canTransit(from, to)) {
            return;
        }
        String detail = (from == null ? "null" : from.name()) + " -> "
                + (to == null ? "null" : to.name());
        throw new BusinessException(ErrorCode.VEHICLE_002,
                "出库指令状态非法流转：" + detail + "（合法后继：" + nextStates(from) + "）");
    }

    /**
     * 当前状态的合法后继集合（只读）。建单前返回空集。
     */
    public static Set<ReleaseStatus> nextStates(ReleaseStatus from) {
        if (from == null) {
            return Collections.emptySet();
        }
        return ALLOWED.getOrDefault(from, Collections.emptySet());
    }
}
