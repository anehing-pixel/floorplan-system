package com.company.floorplan.domain.service;

import com.company.floorplan.domain.model.MileageSample;
import com.company.floorplan.domain.model.MileageSource;

import java.time.LocalDate;
import java.util.List;

/**
 * 里程来源优先级策略（R2.3 / R2.6；D06 §2.4 的 {@code MileageResolutionPolicy}，纯函数）。
 *
 * <p><b>D06 §2.4 原文的优先级</b>：
 * {@code WMS_INBOUND / WMS_SNAPSHOT（实物观测） > AUCTION_TICKET（票面） > REGISTRY（登记，滞后）
 * > MANUAL > UNKNOWN}，同优先级取 {@code mileage_captured_on} 最新者。</p>
 *
 * <p><b>映射到 D00 §8.1 的冻结取值集</b>（D06 的名字有四个不在定源集合内，冲突已登记）：
 * <pre>
 *   rank 4  WMS_INBOUND      ← WMS_INBOUND + WMS_SNAPSHOT（实物观测在定源里就是一个值）
 *   rank 3  OWN_AUDIT        ← 自盘实测：D06 §2.4 的链里没有它，但 D00 §8.1 有，且 D04 §3.8 的
 *                             {@code OWN_AUDIT} 车况票确实产出 {@code odometer_km} 读数。
 *                             按 §2.4 的原理"实物观测 > 票面"排在 WMS 之下、票面之上。
 *                             ⚠ 该档位在 D06 §2.4 里没有明文依据，属本包为定源集合补的空位，
 *                             需业务确认（已列入实现说明的待签字清单）。
 *   rank 2  AUCTION_REPORT   ← AUCTION_TICKET（票面，拍卖场申报值）
 *   rank 1  DEALER_DECLARED  ← MANUAL（车商/人工申报）
 *   rank 0  UNKNOWN          ← UNKNOWN
 * </pre>
 * 档位数值只在 {@link #rankOf} 内部有序，跨版本不保证同一数值（不要落库、不要给前端当枚举用）。
 * 本表与代码曾出现"票面 3 / 自盘 2"的错位（注释说自盘在票面之上、代码相反），
 * 本包按原理取<b>自盘 &gt; 票面</b>：自盘是我方对实车的读数，票面是他方申报，
 * 让申报值压过实测值会让 R2.3「不一致时取更可信来源」失效。</p>
 *
 * <p>「REGISTRY（登记，滞后）」<b>不映射</b>：登録档案的里程属 R5 的
 * {@code RegistryCheckSource} 语义，不是 {@code MileageSource} 的取值，
 * 且 D04 §5.1 未给主数据里程"来自登记档案"的写法。缺这一档不会让任何行写不进去，
 * 但链里若确实要它，需要先在 D00 §8.1 加值。</p>
 *
 * <p><b>本策略只排序，不设阈值</b>：差异是否算"过大"（D06 §2.3 的 1,000km）
 * 由应用层拿参数表判定后决定是否建工单，数值不写进本类。</p>
 */
public class MileageResolutionPolicy {

    /**
     * 按 D06 §2.4 的优先级链选出里程样本。
     *
     * @param candidates 候选观测（来自各表的可信观测集合）
     * @return 胜出的样本；候选为空或全为 {@code null} 时返回 {@code null}
     *         （调用方据此写 {@code mileage_source = NULL}，即"从未采集"，
     *         这与选出 {@code UNKNOWN} 样本是两种事实，不能混）
     */
    public MileageSample resolve(List<MileageSample> candidates) {
        MileageSample winner = null;
        if (candidates == null) {
            return null;
        }
        for (MileageSample candidate : candidates) {
            if (candidate == null || candidate.getSource() == null) {
                // 无来源的读数不参与优先级判定：主数据里的裸数值本身就是待核对象，
                // 给它一个虚拟来源等于伪造"里程可追溯"（R2.3 的验收点）。
                continue;
            }
            if (winner == null) {
                winner = candidate;
                continue;
            }
            int mine = rankOf(candidate.getSource());
            int current = rankOf(winner.getSource());
            if (mine > current || (mine == current && isNewer(candidate.getCapturedOn(), winner.getCapturedOn()))) {
                winner = candidate;
            }
        }
        return winner;
    }

    /**
     * 来源档位（数值只在序内有效，见类注释的映射表）。
     *
     * <p><b>对外公开的理由</b>：R2.3 的勾稽除了"选哪个读数"，还要判"这次观测是不是
     * 比主数据更不可信"（D06 §2.3 的 {@code 来源降级} 也要建工单）。
     * 若应用层自己再写一份优先级表，同一规则就活在两层，改一处必漏另一处。</p>
     *
     * @param source 来源
     * @return 档位（越大越可信）；{@code null} 来源按最低档处理（裸数值不参与优先级竞争）
     */
    public int rankOf(MileageSource source) {
        if (source == null) {
            return -1;
        }
        switch (source) {
            case WMS_INBOUND:
                return 4;
            case OWN_AUDIT:
                return 3;
            case AUCTION_REPORT:
                return 2;
            case DEALER_DECLARED:
                return 1;
            case UNKNOWN:
            default:
                return 0;
        }
    }

    /**
     * 同档取最新（{@code null} 采集时点视为最旧）。
     *
     * @param candidate 候选时点
     * @param current   当前胜者时点
     * @return 候选更新则 true
     */
    private boolean isNewer(LocalDate candidate, LocalDate current) {
        if (candidate == null) {
            return false;
        }
        return current == null || candidate.isAfter(current);
    }
}
