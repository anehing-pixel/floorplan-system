package com.company.floorplan.bank.client;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * 借据投影（对齐 docs/接口契约-V16借据.md §2.3 的冻结字段）。
 *
 * <p>本类是 payment 侧自己定义的 DTO，不引用 loan-service 的类——只依赖冻结契约，
 *  loan 服务实现变动（加字段/改内部模型）不会穿透到本服务。</p>
 *
 * <p>契约明确：{@code advanceId} / {@code dueDate} / {@code currentStageCode} 可能为 null
 * （application.yml 设了 non_null，JSON 里键直接缺失），解析与判定时必须容忍。</p>
 */
public record LoanSnapshot(
        Long id,
        String loanNo,
        Long advanceId,
        String vin,
        Long dealerId,
        Long principal,
        LocalDate dueDate,
        String status,
        LocalDateTime createdAt,
        Long principalOutstanding,
        Long interestOutstanding,
        Long penaltyOutstanding,
        Long feeOutstanding
) {

    /** 未结清状态集合，与 V16 契约 §1.1 / idx_loans_vin_active 完全一致。 */
    private static final Set<String> ACTIVE_STATUSES = Set.of("DRAFT", "ACTIVE", "OVERDUE", "SETTLING");

    /** 该借据是否未结清（只有未结清债务才可作为回款匹配目标）。 */
    public boolean isActive() {
        return status != null && ACTIVE_STATUSES.contains(status);
    }

    /** 剩余应回本金：优先取四项余额里的剩余本金，缺失时回退到本金。 */
    public long expectedAmount() {
        if (principalOutstanding != null) {
            return principalOutstanding;
        }
        return principal == null ? 0L : principal;
    }
}
