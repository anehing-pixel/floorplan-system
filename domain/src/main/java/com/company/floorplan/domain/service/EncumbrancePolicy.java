package com.company.floorplan.domain.service;

import com.company.floorplan.domain.model.EncumbranceType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 权利瑕疵判定（USED R2.1 准入项 I2 / R5.3，P-7 红线的判定核心）。
 *
 * <p><b>只回答一个问题：这辆车在当前已登记的证据下，瑕疵结论是什么、能不能放行设质。</b>
 * 「有没有权威来源批次」是另一件事（ADR-Q27，属 R5 放款闸门的 G-U3），
 * 两件事合成一个谓词后就无法告诉操作员该补哪一样，所以本类刻意不收批次参数。</p>
 *
 * <p><b>fail-closed：查不到证据不等于干净。</b>三条方向都朝保守侧：
 * <ul>
 *   <li>没有任何瑕疵证据（{@code facts} 为空）→ 结论 {@link EncumbranceType#UNKNOWN}、阻断——
 *       「没查过」和「查了但说不清」在阻断放款这件事上必须同权（R1 的合规 UNKNOWN 不折叠成通过是同一条纪律）；</li>
 *   <li>多条证据里出现非 NONE 的任一项 → 以最严的那一项为结论（取严，不是取最新）；</li>
 *   <li>{@code null}（列从未回填）不参与取严比较，但会让结论停留在 UNKNOWN。</li>
 * </ul>
 * </p>
 *
 * <p><b>没有白名单入参</b>（AGENTS.md §8 的 Q21 教训）：要在这上面加「某车商可豁免」，
 * 必须先是显式业务裁决，而不是一个默认 {@code false} 的配置键。</p>
 */
public final class EncumbrancePolicy {

    private EncumbrancePolicy() {
    }

    /**
     * 判定结论。
     *
     * @param conclusion 聚合后的瑕疵类型（永不为 {@code null}：无证据即 UNKNOWN）
     * @param clear      是否放行（仅 {@link EncumbranceType#NONE} 为真）
     * @param blockers   逐条阻断原因（含是哪一类瑕疵、来自几条证据），供审计与前端展示
     */
    public record Verdict(EncumbranceType conclusion, boolean clear, List<String> blockers) {
    }

    /**
     * @param facts 该 VIN 已登记的全部瑕疵证据（证表聚合列、custody 明细、批次结论都算一路证据）；
     *              {@code null} 元素表示「该行从未回填」，按未采集处理
     */
    public static Verdict evaluate(Collection<EncumbranceType> facts) {
        List<String> blockers = new ArrayList<>();
        EncumbranceType worst = null;
        int known = 0;
        int noneCount = 0;
        if (facts != null) {
            for (EncumbranceType f : facts) {
                if (f == null) {
                    // 回填缺失本身要留话，否则一份「三条证据都是 NONE」的结论里混着一条从未查过的记录，
                    // 事后从 conclusion 上看不出来。
                    blockers.add("存在未回填瑕疵结论的证据行（encumbrance_status IS NULL）");
                    continue;
                }
                known++;
                if (f == EncumbranceType.NONE) {
                    noneCount++;
                    continue;
                }
                worst = stricter(worst, f);
            }
        }

        if (known == 0) {
            blockers.add(0, "无任何权利瑕疵核查证据 → 按 UNKNOWN 阻断（P-7 fail-closed：没查过不等于没问题）");
            return new Verdict(EncumbranceType.UNKNOWN, false, blockers);
        }
        if (worst != null) {
            blockers.add(0, "存在瑕疵 " + worst + "（除 NONE 外一律硬阻断设质与放款，D03 §2.5 I3）");
            return new Verdict(worst, false, blockers);
        }
        // 全部已知且都是 NONE：这才算干净。若其中混着 UNKNOWN，上面的 worst 已经把它捞出来了。
        return new Verdict(EncumbranceType.NONE, true,
                List.of("已核查 " + noneCount + " 条证据，结论均为 NONE"));
    }

    /**
     * 取严顺序：查封/盗抢 &gt; 抵当/所有权留保 &gt; UNKNOWN &gt; NONE。
     *
     * <p>UNKNOWN 排在实体瑕疵之后、NONE 之前——它要能盖过「另一条证据说干净」，
     * 但不能把已经确认的查封说成「只是不清楚」。</p>
     */
    private static EncumbranceType stricter(EncumbranceType current, EncumbranceType candidate) {
        return rank(candidate) > rank(current) ? candidate : current;
    }

    private static int rank(EncumbranceType type) {
        if (type == null) {
            return -1;
        }
        return switch (type) {
            case NONE -> 0;
            case UNKNOWN -> 1;
            case MORTGAGE, OWNERSHIP_RESERVATION -> 2;
            case ATTACHMENT, THEFT_REPORT -> 3;
        };
    }
}
