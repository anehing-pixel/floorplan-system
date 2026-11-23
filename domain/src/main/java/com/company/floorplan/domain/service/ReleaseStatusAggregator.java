package com.company.floorplan.domain.service;

import com.company.floorplan.domain.model.UsedReleaseItemStatus;
import com.company.floorplan.domain.model.UsedReleaseStatus;

import java.util.Collection;

/**
 * 指令级状态聚合（D09 §5.3 的四行伪码，原文要求「纯函数 ＋ 单测覆盖」）。
 *
 * <p>「取最不利」是刻意的：<b>部分交付永远不能被系统自动洗成全量交付</b>（A3.2）。
 * 因此本类只提供"从项状态推指令状态"这一个方向，
 * 反过来（用指令状态覆盖项状态）在任何路径里都不存在。</p>
 *
 * <p>D09 §5.3 原文的顺序是 ①任一 PARTIAL/REJECTED → 取最不利 ②全部 CONFIRMED → CONFIRMED
 * ③否则取「最靠前但未完成」（SENT &lt; ACKNOWLEDGED）④EXPIRED 只在所有项均无进展时由巡检置位。
 * 本实现把它落成可测的四步，其中第 ④ 步<b>不放在这里</b>：
 * EXPIRED 由作业路径经状态机写入（RB-1），聚合函数是纯函数，不许带时间参数。</p>
 */
public final class ReleaseStatusAggregator {

    private ReleaseStatusAggregator() {
    }

    /**
     * 聚合入口。
     *
     * @param itemStatuses 同一指令下所有项的当前状态（空集合按"无项"处理，非法输入）
     * @return 指令级状态
     * @throws IllegalArgumentException 项集合为空——空指令不该存在，聚合出任何状态都是猜
     */
    public static UsedReleaseStatus aggregate(Collection<UsedReleaseItemStatus> itemStatuses) {
        if (itemStatuses == null || itemStatuses.isEmpty()) {
            throw new IllegalArgumentException("放车指令至少要有释放项才能聚合主状态");
        }
        // ① 任一 REJECTED → 最不利（拒收比部分更坏，顺序不能反）
        if (itemStatuses.contains(UsedReleaseItemStatus.REFUSED)) {
            return UsedReleaseStatus.REJECTED;
        }
        // ①' 任一 NOT_RELEASED（回执里没覆盖到的项）也算部分，与 PARTIAL 同档
        //     注意 ACKNOWLEDGED 不算"未完成中的最不利"，它只是流程中间态
        if (itemStatuses.contains(UsedReleaseItemStatus.NOT_RELEASED)) {
            return UsedReleaseStatus.PARTIAL;
        }
        // ② 全部 RELEASED → CONFIRMED（项级的"事实"到指令级仍是"已确认"，
        //     RELEASED 这个指令态留给实车出库动作，两层不能同名混用）
        if (itemStatuses.stream().allMatch(s -> s == UsedReleaseItemStatus.RELEASED)) {
            return UsedReleaseStatus.CONFIRMED;
        }
        // ③ 最靠前但未完成：PENDING < INSTRUCTED < ACKNOWLEDGED
        if (itemStatuses.stream().allMatch(s -> s == UsedReleaseItemStatus.PENDING)) {
            return UsedReleaseStatus.PENDING_RELEASE;
        }
        if (itemStatuses.contains(UsedReleaseItemStatus.PENDING)
                || itemStatuses.stream().allMatch(s -> s == UsedReleaseItemStatus.INSTRUCTED)) {
            return UsedReleaseStatus.SENT;
        }
        return UsedReleaseStatus.ACKNOWLEDGED;
    }

    /**
     * 是否禁止把指令态从 PARTIAL 推进成 CONFIRMED（A3.2 的反向守卫）。
     *
     * <p>合法的唯一升档路径是"补齐剩余项回执"（D03 §3.3 的 {@code PARTIAL→CONFIRMED}），
     * 走这条路径时项集合已经不含 NOT_RELEASED，聚合结果自然就是 CONFIRMED。
     * 如果有人<b>不经过回执</b>直接把主状态改成 CONFIRMED，本方法给出拒绝依据。</p>
     */
    public static boolean isWashingAttempt(UsedReleaseStatus current, UsedReleaseStatus target,
                                          Collection<UsedReleaseItemStatus> itemStatuses) {
        if (current != UsedReleaseStatus.PARTIAL || target != UsedReleaseStatus.CONFIRMED) {
            return false;
        }
        return aggregate(itemStatuses) != UsedReleaseStatus.CONFIRMED;
    }
}
