package com.company.floorplan.domain.model;

import java.util.List;

/**
 * 一次采集的完整结果。
 *
 * <p><b>为什么不能只返回 {@code List<BankStatementLine>}</b>：
 * 文件交换模式下，"同一份文件被重发"是最常见的重复来源。
 * 如果只返回流水行，上层看不到"这次拉的和上次是同一个文件"，
 * 只能在日志里看到一堆被唯一约束挡下的重复——
 * 事后没人能区分"银行重发了"和"我们拉了两遍"。</p>
 *
 * @param lines       银行返回的流水行（可能为空：窗口内确实没有新入账）
 * @param fingerprint 本次采集内容指纹（文件 SHA-256 前 32 位 / DEMO 标识）。
 *                    只用于观测与排查，<b>不作为幂等键</b>——
 *                    幂等键是每行自己的 {@code (account_ref, transaction_id)}。
 */
public record BankStatementResult(List<BankStatementLine> lines, String fingerprint) {

    public static BankStatementResult empty(String fingerprint) {
        return new BankStatementResult(List.of(), fingerprint);
    }

    public int size() {
        return lines == null ? 0 : lines.size();
    }
}
