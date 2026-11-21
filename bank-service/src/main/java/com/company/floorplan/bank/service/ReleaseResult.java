package com.company.floorplan.bank.service;

import com.company.floorplan.bank.model.CreditRestore;
import com.company.floorplan.bank.model.LienRelease;
import com.company.floorplan.bank.model.PaymentMatch;

import java.util.List;

/**
 * 释放编排结果：包含解押/回补记录与执行步骤序列。
 *
 * <p>{@code steps} 不是调试日志，而是设计稿 §4.2/§4.3 要求的可审计链路——
 * 记录"先解押、后回补、何时查询授信、是否幂等跳过"，事后可复现。</p>
 */
public record ReleaseResult(
        PaymentMatch match,
        LienRelease release,
        CreditRestore restore,
        List<String> steps,
        boolean skipped
) {

    public ReleaseResult {
        steps = List.copyOf(steps);
    }
}
