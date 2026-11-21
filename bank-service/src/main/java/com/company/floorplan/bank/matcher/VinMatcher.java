package com.company.floorplan.bank.matcher;

import com.company.floorplan.bank.client.LoanSnapshot;
import com.company.floorplan.bank.model.MatchStatus;
import com.company.floorplan.domain.model.PaymentTransaction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VIN 匹配器 —— 纯函数，不访问数据库、不发 Feign、不抛外部异常。
 *
 * <p>输入是"流水 + 候选借据列表"，输出是一个决策；候选由调用方按
 * {@link #extractVins(String)} 提取的 VIN 查得（查借据是另一个端口的事）。
 * 这样单测可以给任意候选组合，覆盖精确/多借据/无法匹配的边界而不需要 Spring。</p>
 *
 * <h3>判定口径（设计稿附录 A）</h3>
 * <ul>
 *   <li>VIN 命中 = 95；金额精确 = 60；金额近似 = 45（仅辅助，不足以自动绑定）。</li>
 *   <li>自动匹配：最高分 ≥ 90 且与次高分差距 ≥ 20。</li>
 *   <li>多候选分数接近（差距 &lt; 20）→ 不自动绑定，挂起人工裁决——
 *       误匹配会同时造成漏管敞口与误催收，两种错误都严重。</li>
 * </ul>
 *
 * <p>同 VIN 若出现多笔候选（脏数据），按 V16 契约 §3 取 {@code id DESC} 最新一笔，
 * 并在候选摘要里留痕告警，不抛错。</p>
 */
public final class VinMatcher {

    /** MT940 附言里的 VIN token：6~17 位字母数字，须同时含字母与数字。 */
    private static final Pattern VIN_TOKEN = Pattern.compile("[A-Za-z0-9]{6,17}");

    private static final int AUTO_MATCH_MIN_SCORE = 90;
    private static final int MIN_SCORE_GAP = 20;
    private static final long AMOUNT_EXACT_TOLERANCE = 1L;
    private static final long AMOUNT_APPROX_FLOOR = 5_000L;

    private VinMatcher() {
    }

    /**
     * 从流水摘要提取候选 VIN token，保持原始大小写（V16 契约要求大小写敏感精确匹配）。
     *
     * <p>要求 token 同时含字母与数字，避免把纯数字金额/订单号误当 VIN。</p>
     */
    public static List<String> extractVins(String reference) {
        if (reference == null || reference.isBlank()) {
            return List.of();
        }
        List<String> vins = new ArrayList<>();
        Matcher m = VIN_TOKEN.matcher(reference);
        while (m.find()) {
            String token = m.group();
            boolean hasLetter = false;
            boolean hasDigit = false;
            for (int i = 0; i < token.length(); i++) {
                char c = token.charAt(i);
                hasLetter |= Character.isLetter(c);
                hasDigit |= Character.isDigit(c);
            }
            if (hasLetter && hasDigit && !vins.contains(token)) {
                vins.add(token);
            }
        }
        return vins;
    }

    /** 纯函数：按候选借据打分并给出是否自动匹配的决策。 */
    public static MatchDecision match(PaymentTransaction tx, List<LoanSnapshot> candidates) {
        if (tx == null || !tx.isMatchCandidate()) {
            return MatchDecision.unmatched("流水不是有效匹配候选（冲正/已处理/金额非正）", List.of());
        }
        if (candidates == null || candidates.isEmpty()) {
            return MatchDecision.unmatched("未找到该 VIN 对应的未结清借据（契约 404 = 无在贷债务）", List.of());
        }

        // 同 VIN 多笔未结清（脏数据）→ 取 id DESC 最新一笔，并留痕
        Map<String, LoanSnapshot> byVin = new LinkedHashMap<>();
        List<String> duplicates = new ArrayList<>();
        for (LoanSnapshot loan : candidates) {
            if (loan == null || !loan.isActive() || loan.vin() == null) {
                continue;
            }
            LoanSnapshot prev = byVin.get(loan.vin());
            if (prev == null) {
                byVin.put(loan.vin(), loan);
            } else {
                if (prev.id() == null || (loan.id() != null && loan.id() > prev.id())) {
                    byVin.put(loan.vin(), loan);
                }
                duplicates.add(loan.vin());
            }
        }
        if (byVin.isEmpty()) {
            return MatchDecision.unmatched("候选借据均非未结清状态，不予匹配", List.of());
        }

        List<Scored> scored = new ArrayList<>();
        for (LoanSnapshot loan : byVin.values()) {
            Score s = score(tx, loan);
            if (s.points > 0) {
                scored.add(new Scored(loan, s.points, s.rule));
            }
        }
        String summary = summarize(scored, duplicates);
        if (scored.isEmpty()) {
            return MatchDecision.unmatched("摘要未含 VIN 且金额无可靠命中，不予自动匹配" + detail(summary), List.of());
        }
        scored.sort(Comparator.comparingInt(Scored::points).reversed());
        Scored top = scored.get(0);
        int second = scored.size() > 1 ? scored.get(1).points() : 0;

        // 歧义：两个不同 VIN 同为最高分（或差距不足）→ 挂起人工，绝不猜一个
        if (top.points() >= AUTO_MATCH_MIN_SCORE && top.points() - second >= MIN_SCORE_GAP) {
            return new MatchDecision(MatchStatus.AUTO_MATCHED, top.loan().vin(), top.loan(),
                    top.rule(), BigDecimal.valueOf(top.points()),
                    "自动匹配：" + top.rule() + "，置信度 " + top.points()
                            + "，领先次高 " + (top.points() - second) + " 分" + detail(summary),
                    List.of(top.loan().vin()));
        }
        if (top.points() < AUTO_MATCH_MIN_SCORE) {
            return MatchDecision.unmatched("最高置信度 " + top.points() + " < " + AUTO_MATCH_MIN_SCORE
                    + "，仅作候选，挂起人工复核" + detail(summary), List.copyOf(byVin.keySet()));
        }
        return MatchDecision.unmatched("多候选分数接近（最高 " + top.points() + " / 次高 " + second
                + "，差距 < " + MIN_SCORE_GAP + "），数据矛盾，挂起人工裁决" + detail(summary),
                List.copyOf(byVin.keySet()));
    }

    /** 把候选得分摘要附到判定理由，事后可复现打分过程。 */
    private static String detail(String summary) {
        return summary == null || summary.isBlank() ? "" : "；候选得分：" + summary;
    }

    /** 单条候选打分：VIN 命中优先；金额只作辅助信号。 */
    private static Score score(PaymentTransaction tx, LoanSnapshot loan) {
        String ref = tx.getReference() == null ? "" : tx.getReference();
        if (loan.vin() != null && ref.contains(loan.vin())) {
            return new Score(95, "VIN_MATCH");
        }
        long expected = loan.expectedAmount();
        if (expected <= 0) {
            return new Score(0, null);
        }
        long diff = Math.abs(tx.getAmount() - expected);
        if (diff <= AMOUNT_EXACT_TOLERANCE) {
            return new Score(60, "AMOUNT_MATCH");
        }
        long tolerance = Math.max(AMOUNT_APPROX_FLOOR, (long) (expected * 0.01));
        if (diff <= tolerance) {
            return new Score(45, "AMOUNT_APPROX");
        }
        return new Score(0, null);
    }

    private static String summarize(List<Scored> scored, List<String> duplicates) {
        StringBuilder sb = new StringBuilder();
        for (Scored s : scored) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(s.loan().vin()).append(':').append(s.points()).append('(').append(s.rule()).append(')');
        }
        if (!duplicates.isEmpty()) {
            sb.append("; 同VIN多笔未结清(取id DESC最新): ").append(String.join(",", duplicates));
        }
        return sb.toString();
    }

    private record Score(int points, String rule) {
    }

    private record Scored(LoanSnapshot loan, int points, String rule) {
    }
}
