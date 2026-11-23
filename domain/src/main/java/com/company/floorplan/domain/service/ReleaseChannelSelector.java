package com.company.floorplan.domain.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;

/**
 * 下发通道选择（D09 §5.5「delivery_channel 参与路由」，修的是断裂 2）。
 *
 * <p>被修的现状：LEGACY 的 {@code ManualReleaseChannel} 被无条件调用，
 * 于是"等人执行"被当成"已执行"。D09 §5.5 要求把 {@code MANUAL} 的语义改成
 * 「生成工单并返回<b>未下发</b>结论」，但本包的 §5 禁区同时写明
 * <b>{@code ManualReleaseChannel} 的 LEGACY 行为不许动</b>（要动得连带改 LEGACY 冒烟，
 * 属 D11 §4 回归清单的跨包变更）。本轮的处理方式是<b>不改旧类、只立新接缝</b>：
 * USED 线的 dispatch 一律走本类，LEGACY 线保持原样，两条线由
 * {@code ReleaseProductLine} 分流。该冲突已登记为实现说明的第 12 条。</p>
 *
 * <p>另一处刻意保留的不对称：本类只输出"该做什么"（决策），不输出"做了什么"。
 * {@code MANUAL} 分支返回 {@code MANUAL_WORK_ORDER} 后，调用方必须让主状态留在
 * {@code PENDING_RELEASE}——D09 §5.5 伪码最后那一行
 * （{@code order.status = 至少一条任务已发出 ? SENT : PENDING_RELEASE}）
 * 就是这条断言，写成决策而不是直接改状态，是为了让"没发出去"能被单测钉住。</p>
 */
public final class ReleaseChannelSelector {

    private ReleaseChannelSelector() {
    }

    /** 通道决策三值（对应 D09 §5.5 伪码的三个分支）。 */
    public enum Decision {
        /** API / PORTAL：排出站任务，由 W-U3 的 outbound_tasks 承载（本轮不可写，见接缝说明）。 */
        ENQUEUE_OUTBOUND,
        /** MANUAL：生成人工工单并标记待执行，绝不伪装已下发。 */
        MANUAL_WORK_ORDER,
        /** 未知通道：拒（VALID_001）。 */
        REJECT_UNKNOWN
    }

    /**
     * 按通道文本选决策。
     *
     * @param deliveryChannel {@code release_orders.delivery_channel}
     *                        或 {@code custodians.channel_capability} 展开后的单项（API/PORTAL/MQ/MANUAL）
     */
    public static Decision select(String deliveryChannel) {
        if (deliveryChannel == null || deliveryChannel.isBlank()) {
            // 空通道不是"没填"，而是主数据没配——按未知处理拒发，比默认走人工更安全
            throw new BusinessException(ErrorCode.VALID_001, "下发通道未指定，拒发（D09 §5.5 else 分支）");
        }
        String c = deliveryChannel.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (c) {
            case "API", "PORTAL" -> Decision.ENQUEUE_OUTBOUND;
            case "MANUAL" -> Decision.MANUAL_WORK_ORDER;
            // MQ 在 V65 的 channel_capability 正则里合法，但 D09 §5.5 的伪码没给它分支，
            // 所以这里不猜语义：落到未知 → VALID_001，由收口方补规则或从能力域去掉 MQ。
            default -> throw new BusinessException(ErrorCode.VALID_001,
                    "下发通道不在 D09 §5.5 的分支表内: " + c);
        };
    }

    /** 是否算"任务已发出"（只有 ENQUEUE_OUTBOUND 算，MANUAL 永远不算——这正是断裂 2 的定义）。 */
    public static boolean countsAsDispatched(Decision decision) {
        return decision == Decision.ENQUEUE_OUTBOUND;
    }
}
