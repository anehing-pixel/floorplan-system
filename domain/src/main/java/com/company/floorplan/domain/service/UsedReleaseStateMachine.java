package com.company.floorplan.domain.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.UsedReleaseStatus;

import java.util.Map;
import java.util.Set;

/**
 * USED 域放车指令状态机（十态白名单）。
 *
 * <p>迁移表源 D03 §3.3 的 mermaid（docs/…-03:434-455），叠加 D09 §12 RL-4
 * 「CONFIRMED 后 /cancel → RELS_001」。LEGACY 的 {@code ReleaseStateMachine}
 * 与它的 6×6 期望表一字不动（RL-5 明令「不得为 USED 修改」），本类与它并存，
 * 由 {@code ReleaseProductLine} 选表。</p>
 *
 * <p><b>三条设计选择</b>：</p>
 * <ol>
 *   <li><b>穷举 switch 而不是 EnumMap</b>：LEGACY 那张 {@code EnumMap} 只有 6 个键，
 *       加值不加键会运行期返回 null（D13 G11）。这里用 {@code Map.ofEntries} 显式列出
 *       十个键，键少一个就编译不过，值集合不可变。</li>
 *   <li><b>RL-4 的落点在表里，不在调用方</b>：USED 下 CANCELLED 的入边只有
 *       PENDING_RELEASE / EXPIRED / REJECTED 三条，SENT 与 CONFIRMED 都不在内——
 *       于是"已下发/已确认后来撤销"必然走到 {@code requireTransition} 抛
 *       {@code RELS_001}，不需要每个 Controller 各写一句 if。</li>
 *   <li><b>RB-1 的落点在来源参数</b>：EXPIRED/REJECTED 这类"外部事实"只能由
 *       作业或回执路径写入（{@link TransitionOrigin}），调用方在请求体里自报终态一律拒绝（A1.5）。</li>
 * </ol>
 *
 * <p><b>与 D03 §3.3 的"LEGACY 兼容"表述的矛盾（已登记）</b>：D03 要求
 * 「旧六态之间的合法迁移集在 USED 枚举下保持完全一致」，但同一节的 mermaid
 * 把 LEGACY 的 {@code SENT→CANCELLED} 与 {@code CONFIRMED→CANCELLED} 两条边删掉了，
 * D09 RL-4 又明确要求后者必须被拒。两条断言不可能同时成立。
 * 本轮按 <b>RL-4 + mermaid</b> 实现（撤销不可越态是有错误码、可冒烟的那条），
 * 并把兼容断言收窄为"USED 不得<b>新增</b> LEGACY 六态之间的边，只允许删"，
 * 由收口方在 D03/D09 之间定一版措辞。</p>
 */
public final class UsedReleaseStateMachine {

    /** 工具类，不实例化。 */
    private UsedReleaseStateMachine() {
    }

    /**
     * 迁移允许的<b>来源</b>：同一对状态，谁来提意思完全不同。
     *
     * <p>{@link #CALLER} = HTTP 请求体/路径参数带来的意图；
     * {@link #RECEIPT} = 入站回执链（authority 已由 inbound_message_id 定死）；
     * {@link #JOB} = 定时作业（过期检查 J1）；
     * {@link #SYSTEM} = 同一事务内的系统内推导（如下发重校验发现已过期就地置 EXPIRED，
     * D07 §7.6 重校验表第 2 行）。RB-1 禁的是 CALLER 写终态，不是禁终态本身。</p>
     */
    public enum TransitionOrigin {
        /** 调用方直接发起。 */
        CALLER,
        /** 回执链内部推进。 */
        RECEIPT,
        /** 批处理作业推进。 */
        JOB,
        /** 同事务内的系统内推导。 */
        SYSTEM
    }

    /**
     * 十态白名单表：键 = 当前态，值 = 允许到达的态集合（不可变）。
     *
     * <p>每条边的来源在行内注释标注：{@code [L]} = LEGACY 已有边
     * （release-service/.../ReleaseStateMachine.java:43-57 原样抄入，语义未变），
     * {@code [U]} = USED 新增。</p>
     */
    private static final Map<UsedReleaseStatus, Set<UsedReleaseStatus>> ALLOWED = Map.ofEntries(
            // [L] PENDING_RELEASE→SENT（下发成功）/ PENDING_RELEASE→CANCELLED（下发前撤销，RL-4 唯一允许的撤销入口）
            // [U] →REJECTED/EXPIRED：下发即可能被拒或已过期，重校验清单（D07 §7.6）要求这两件事在 dispatch 时就能落定
            Map.entry(UsedReleaseStatus.PENDING_RELEASE, Set.of(
                    UsedReleaseStatus.SENT, UsedReleaseStatus.CANCELLED,
                    UsedReleaseStatus.REJECTED, UsedReleaseStatus.EXPIRED)),
            // [L] SENT→CONFIRMED；[U] SENT→ACKNOWLEDGED（对方受理）
            // [U] SENT→REJECTED/EXPIRED（D09 §7.2 无回执超时）
            // 注意：LEGACY 的 SENT→CANCELLED 在 USED 下不存在（mermaid 未列，RL-4 精神：已下发不可单方撤）
            Map.entry(UsedReleaseStatus.SENT, Set.of(
                    UsedReleaseStatus.CONFIRMED, UsedReleaseStatus.ACKNOWLEDGED,
                    UsedReleaseStatus.REJECTED, UsedReleaseStatus.EXPIRED)),
            // [U] 受理后：全量回执→CONFIRMED，部分→PARTIAL，拒收/超时→REJECTED/EXPIRED
            Map.entry(UsedReleaseStatus.ACKNOWLEDGED, Set.of(
                    UsedReleaseStatus.CONFIRMED, UsedReleaseStatus.PARTIAL,
                    UsedReleaseStatus.REJECTED, UsedReleaseStatus.EXPIRED)),
            // [U] PARTIAL→CONFIRMED（补齐剩余项回执，A3.2「取最不利」的反向只在补齐时发生）
            Map.entry(UsedReleaseStatus.PARTIAL, Set.of(
                    UsedReleaseStatus.CONFIRMED, UsedReleaseStatus.REJECTED)),
            // [L] CONFIRMED→RELEASED。USED 下 CONFIRMED→CANCELLED 被刻意剔除（RL-4）
            Map.entry(UsedReleaseStatus.CONFIRMED, Set.of(UsedReleaseStatus.RELEASED)),
            // [L] RELEASED→LIEN_DISCHARGED；USED 语义收窄为"登记抹消完成回执到位"[待定源 D-07]
            Map.entry(UsedReleaseStatus.RELEASED, Set.of(UsedReleaseStatus.LIEN_DISCHARGED)),
            // [U] EXPIRED→CANCELLED（人工作废，新指令另起新号，不复用旧号）
            Map.entry(UsedReleaseStatus.EXPIRED, Set.of(UsedReleaseStatus.CANCELLED)),
            // [U] REJECTED→CANCELLED
            Map.entry(UsedReleaseStatus.REJECTED, Set.of(UsedReleaseStatus.CANCELLED)),
            // 终态：无出边
            Map.entry(UsedReleaseStatus.LIEN_DISCHARGED, Set.of()),
            Map.entry(UsedReleaseStatus.CANCELLED, Set.of()));

    /** 白名单判定；{@code null} 一律 false，不猜。 */
    public static boolean canTransit(UsedReleaseStatus from, UsedReleaseStatus to) {
        if (from == null || to == null) {
            return false;
        }
        Set<UsedReleaseStatus> targets = ALLOWED.get(from);
        return targets != null && targets.contains(to);
    }

    /**
     * 迁移校验并返回目标态，失败即抛业务异常（禁止调用方"自己判一下就写库"）。
     *
     * <p>两层判定顺序是刻意的：<b>先查白名单，再查 RB-1 来源</b>。
     * 反过来会让"调用方自报 EXPIRED"拿到 VEHICLE_002（状态流转不合法），
     * 而它实际犯的是"越权声明终态"，冒烟里两种失败混在一起就没法分辨是表漏了一条边
     * 还是入口没守住（A1.5）。</p>
     *
     * @param from   当前态
     * @param to     目标态
     * @param origin 本次迁移的发起来源
     * @return 目标态，便于链式写法
     * @throws BusinessException 白名单不含该边（{@code VEHICLE_002}）或 RB-1 越权（{@code AUTH_003}）
     */
    public static UsedReleaseStatus requireTransition(UsedReleaseStatus from, UsedReleaseStatus to,
                                                     TransitionOrigin origin) {
        if (!canTransit(from, to)) {
            throw new BusinessException(ErrorCode.VEHICLE_002,
                    "放车指令状态流转不合法: " + from + " -> " + to);
        }
        if (to.requiresSystemOrigin() && origin == TransitionOrigin.CALLER) {
            // D06 §16 RB-1：EXPIRED/REJECTED 是外部事实，不是业务决定，不许由请求体写入
            throw new BusinessException(ErrorCode.AUTH_003,
                    "状态 " + to + " 只能由作业或回执链写入，不接受调用方指定（RB-1）");
        }
        return to;
    }

    /**
     * 撤销校验（RL-4 的专用入口）。
     *
     * <p>单独开一个方法而不是让调用方直接 {@code requireTransition(CANCELLED)}：
     * RL-4 要求的错误码是 {@code RELS_001}（指令当前状态不接受该操作），
     * 不是通用流转错。语义上"撤销被拒"和"回执照错方向"在冒烟里是两条断言，
     * 必须能靠错误码分开。</p>
     *
     * @param from 当前态
     * @throws BusinessException 当前态不可撤销时抛 {@code RELS_001}
     */
    public static void requireCancellable(UsedReleaseStatus from) {
        if (!canTransit(from, UsedReleaseStatus.CANCELLED)) {
            throw new BusinessException(ErrorCode.RELS_001,
                    "指令当前状态不接受撤销: " + from + "（RL-4：已下发/已确认不可单方撤销）");
        }
    }

    /** 某态的全部合法后继（供接口文档自检与冒烟遍历用，不用于放行判定）。 */
    public static Set<UsedReleaseStatus> nextStates(UsedReleaseStatus from) {
        Set<UsedReleaseStatus> targets = ALLOWED.get(from);
        return targets == null ? Set.of() : targets;
    }

    /**
     * LEGACY 六态在 USED 表下的出边子集，供兼容断言使用。
     *
     * <p>断言口径（见类头矛盾说明）：本方法返回的边集必须是 LEGACY
     * {@code ReleaseStateMachine} 白名单的<b>子集</b>——USED 只允许删边，不允许加边，
     * 否则"新语义泄漏到旧链路"就是从这条子集关系开始的。</p>
     */
    public static Map<UsedReleaseStatus, Set<UsedReleaseStatus>> legacyStateEdges() {
        java.util.EnumMap<UsedReleaseStatus, Set<UsedReleaseStatus>> view =
                new java.util.EnumMap<>(UsedReleaseStatus.class);
        for (UsedReleaseStatus s : UsedReleaseStatus.LEGACY_STATES) {
            view.put(s, ALLOWED.getOrDefault(s, Set.of()));
        }
        return Map.copyOf(view);
    }
}
