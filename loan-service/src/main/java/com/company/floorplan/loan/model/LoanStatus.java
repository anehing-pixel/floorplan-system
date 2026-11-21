package com.company.floorplan.loan.model;

import java.util.Set;

/**
 * 借据状态机（ADR-008 §2.4）。
 *
 * <p>DRAFT → ACTIVE → OVERDUE → SETTLING → SETTLED；ACTIVE/OVERDUE 也可转 DEFAULTED。
 * OVERDUE 只能由还款计划未足额触发，人工不可直接置入——逾期是客观事实，
 * 人可以确认、处置，但不能制造。</p>
 */
public enum LoanStatus {

    DRAFT, ACTIVE, OVERDUE, SETTLING, SETTLED, DEFAULTED, WRITTEN_OFF;

    /** 未结清状态：by-vin 查询、额度占用判定都据此过滤（与 idx_loans_vin_active 一致）。 */
    private static final Set<LoanStatus> OPEN = Set.of(DRAFT, ACTIVE, OVERDUE, SETTLING);

    public static boolean isOpen(LoanStatus status) {
        return status != null && OPEN.contains(status);
    }

    /** 字符串版本供 JDBC 直接读列的 status 字段使用。 */
    public static boolean isOpen(String code) {
        if (code == null || code.isBlank()) return false;
        try {
            return isOpen(valueOf(code));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
