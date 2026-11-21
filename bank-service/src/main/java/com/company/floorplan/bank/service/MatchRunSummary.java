package com.company.floorplan.bank.service;

import java.util.List;

/** 一次批量匹配触发的汇总（对应 PAYMENT_MATCH 作业运行结果）。 */
public record MatchRunSummary(
        int scanned,
        int matched,
        int pending,
        int released,
        List<MatchRunResult> details
) {
    public MatchRunSummary {
        details = List.copyOf(details);
    }
}
