package com.company.floorplan.domain.service;

import com.company.floorplan.domain.model.RepairHistoryFlag;

import java.util.List;

/**
 * 修復歴取严策略（R2.3；D06 §2.3 末行「按 D03 §2.3『MAJOR 优先，UNKNOWN 不优于已知』取严值」）。
 *
 * <p><b>纯函数、无配置</b>：取严是序关系，不含任何待定数值。</p>
 *
 * <p><b>序关系</b>（从严到宽）：{@code MAJOR > UNKNOWN > MINOR > NONE}。
 * {@code UNKNOWN} 排在 {@code MINOR} 之上是「不优于已知」的直接结果——
 * 判不清时不能把它降格成"轻微"，否则一次勾稽就把不确定性洗成了乐观值；
 * 但它也不等于 {@code MAJOR}（那是"确定的重大"），两者在 D08 取带时同档、
 * 在台账与工单上是两件事，所以序里保留独立的 {@code UNKNOWN}。</p>
 *
 * <p><b>调用约定</b>：本策略只回答"取严之后是什么"，不回答"要不要建工单"。
 * 勾稽不一致时的 {@code REPAIR_HISTORY_DIFF} 工单与审计由应用层负责（分层纪律 §4）。</p>
 */
public class RepairHistoryResolutionPolicy {

    /**
     * 取多个观测中的最严值。
     *
     * @param observations 各来源的修復歴观测（{@code null} 元素按"未观测"跳过，不参与取严）
     * @return 最严值；全部未观测时返回 {@code null}（表示无观测，不等于 {@code NONE}）
     */
    public RepairHistoryFlag strictest(List<RepairHistoryFlag> observations) {
        RepairHistoryFlag result = null;
        if (observations == null) {
            return null;
        }
        for (RepairHistoryFlag observation : observations) {
            if (observation == null) {
                // 「这一路没给出值」与「这一路给出 UNKNOWN」不同：前者不参与比较，
                // 后者参与（因为它是采集过的结论）。混在一起会让缺数据把已知坏值顶掉。
                continue;
            }
            if (result == null || severity(observation) > severity(result)) {
                result = observation;
            }
        }
        return result;
    }

    /**
     * 严重度排序值（越大越严）。
     *
     * <p>私有语义，数值只在序内有效，不对应任何业务分。</p>
     *
     * @param flag 观测值
     * @return 排序权重
     */
    private int severity(RepairHistoryFlag flag) {
        switch (flag) {
            case MAJOR:
                return 3;
            case UNKNOWN:
                return 2;
            case MINOR:
                return 1;
            case NONE:
            default:
                return 0;
        }
    }
}
