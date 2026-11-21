package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.Alert;

import java.time.LocalDate;
import java.util.List;

/**
 * 支撑能力仓储：审计事件、营业日历、批处理执行记录、风险预警。
 * 合并为一个接口以减少模块间依赖数量。
 */
public interface SupportRepository {

    // ---- 审计（append-only）----
    void writeAudit(String actor, String action, String targetType, String targetId,
                    String beforeSnapshot, String afterSnapshot, String traceId);

    /**
     * 写审计事件（显式带操作人角色）。
     *
     * <p>{@code actorRole} 传 null 时，实现方应按 {@code actor} 反查用户角色补齐，
     * 使审计能回答"操作人当时是什么角色"。</p>
     */
    void writeAudit(String actor, String actorRole, String action, String targetType, String targetId,
                    String beforeSnapshot, String afterSnapshot, String traceId);

    /**
     * 按登录名查用户 ID，用于把"操作人"落成可关联到 {@code users} 表的数字外键。
     *
     * <p>{@code repayments.operator_id} 这类字段是 bigint，存的是用户 ID 而非用户名。
     * 审计链要能从业务流水反查到自然人，就必须能由登录名反查 ID。</p>
     *
     * @param username 登录名
     * @return 用户 ID；查不到返回 null（不抛异常——系统类操作人本就没有对应用户）
     */
    Long findUserIdByUsername(String username);

    // ---- 营业日历 ----
    boolean isBusinessDay(LocalDate date);

    void upsertHoliday(LocalDate date, boolean businessDay, String note);

    // ---- 批处理执行记录 ----
    boolean batchSucceeded(String jobName, LocalDate bizDate);

    void markBatchRunning(String jobName, LocalDate bizDate);

    void markBatchFinished(String jobName, LocalDate bizDate, boolean success, String errorDetail);

    // ---- 风险预警 ----
    void createAlert(Long dealerId, String vin, String alertType, String severity, String detail);

    List<Alert> findAlerts(String alertType, String status, int limit);

    /**
     * 处置告警：把告警推进到指定处理状态。
     *
     * <p>告警本身不是"事实"而是"待复核的信号"——系统扫出异常后，
     * 必须由<b>人</b>确认并处置（降级、升级、关闭），否则告警池只会越积越满、
     * 最终无人再看。处置动作属贷后管理岗（RISK_HANDLE 权限点）。</p>
     *
     * @param alertId 告警 ID
     * @param status  目标状态（HANDLED / CLOSED）
     * @param handler 处理人（自然人标识，留痕）
     * @param note    处理说明
     * @return 受影响行数
     */
    int handleAlert(Long alertId, String status, String handler, String note);
}
