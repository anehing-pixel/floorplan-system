package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.Alert;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.domain.service.BusinessCalendar;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 支撑能力仓储实现：审计事件、营业日历、批处理执行记录、风险预警。
 *
 * <p>这四个能力都属于"横切关注点"，业务模块各自实现会造成重复与口径不一，
 * 因此合并为一个仓储接口（见 {@link com.company.floorplan.domain.repository.SupportRepository}）。</p>
 *
 * <p><b>关键设计点</b>：
 * <ul>
 *   <li>审计：{@code audit_events} 为 append-only，仅 INSERT，
 *       保证审计链不可篡改（金融合规要求）；</li>
 *   <li>批处理：{@code (job_name, biz_date)} 唯一约束，
 *       使作业"可重跑、有记录、能定位"；</li>
 *   <li>营业日历：实现委托给 {@link JdbcBusinessCalendar}，
 *       保证计息与结算窗口使用同一套营业日口径。</li>
 * </ul>
 */
@Repository
public class JdbcSupportRepository implements SupportRepository {

    private final JdbcTemplate jdbc;
    private final BusinessCalendar calendar;

    public JdbcSupportRepository(JdbcTemplate jdbc, BusinessCalendar calendar) {
        this.jdbc = jdbc;
        this.calendar = calendar;
    }

    @Override
    public void writeAudit(String actor, String action, String targetType, String targetId,
                           String beforeSnapshot, String afterSnapshot, String traceId) {
        writeAudit(actor, null, action, targetType, targetId, beforeSnapshot, afterSnapshot, traceId);
    }

    /**
     * 写审计事件（带操作人角色）。
     *
     * <p>{@code actorRole} 为 null 时按 {@code actor} 反查 {@code users.role} 补齐——
     * 审计要能回答"操作人当时是什么角色"（内控铁律：发起与执行分离要靠角色差异来验证）。
     * 查不到对应用户（如系统作业的 actor='system'）则留空。</p>
     */
    @Override
    public void writeAudit(String actor, String actorRole, String action, String targetType, String targetId,
                           String beforeSnapshot, String afterSnapshot, String traceId) {
        String role = actorRole;
        if ((role == null || role.isBlank()) && actor != null && !actor.isBlank()) {
            role = findRoleByUsername(actor);
        }
        jdbc.update("INSERT INTO audit_events (actor, actor_role, action, target_type, target_id, "
                        + "before_snapshot, after_snapshot, trace_id) VALUES (?,?,?,?,?,?,?,?)",
                actor, role, action, targetType, targetId, beforeSnapshot, afterSnapshot, traceId);
    }

    private String findRoleByUsername(String username) {
        List<String> roles = jdbc.queryForList(
                "SELECT role FROM users WHERE username = ?", String.class, username.trim());
        return roles.isEmpty() ? null : roles.get(0);
    }

    @Override
    public Long findUserIdByUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM users WHERE username = ?", Long.class, username.trim());
        return ids.isEmpty() ? null : ids.get(0);
    }

    @Override
    public boolean isBusinessDay(LocalDate date) {
        return calendar.isBusinessDay(date);
    }

    @Override
    public void upsertHoliday(LocalDate date, boolean businessDay, String note) {
        jdbc.update("INSERT INTO holidays (biz_date, is_business_day, note) VALUES (?,?,?) "
                + "ON CONFLICT (biz_date) DO UPDATE SET is_business_day=EXCLUDED.is_business_day, note=EXCLUDED.note",
                date, businessDay, note);
    }

    @Override
    public boolean batchSucceeded(String jobName, LocalDate bizDate) {
        String status = jdbc.query(
                "SELECT status FROM batch_runs WHERE job_name = ? AND biz_date = ?",
                rs -> rs.next() ? rs.getString(1) : null, jobName, bizDate);
        return "SUCCESS".equals(status);
    }

    @Override
    public void markBatchRunning(String jobName, LocalDate bizDate) {
        jdbc.update("INSERT INTO batch_runs (job_name, biz_date, status, started_at) VALUES (?,?,'RUNNING',now()) "
                + "ON CONFLICT (job_name, biz_date) DO UPDATE SET status='RUNNING', started_at=now(), "
                + "finished_at=NULL, error_detail=NULL", jobName, bizDate);
    }

    @Override
    public void markBatchFinished(String jobName, LocalDate bizDate, boolean success, String errorDetail) {
        jdbc.update("UPDATE batch_runs SET status=?, finished_at=now(), error_detail=? WHERE job_name=? AND biz_date=?",
                success ? "SUCCESS" : "FAILED", errorDetail, jobName, bizDate);
    }

    @Override
    public void createAlert(Long dealerId, String vin, String alertType, String severity, String detail) {
        jdbc.update("INSERT INTO alerts (dealer_id, vin, alert_type, severity, status, detail) VALUES (?,?,?,?,'OPEN',?)",
                dealerId, vin, alertType, severity, detail);
    }

    @Override
    public List<Alert> findAlerts(String alertType, String status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM alerts WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (alertType != null && !alertType.isBlank()) {
            sql.append(" AND alert_type = ?");
            args.add(alertType);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(limit);
        RowMapper<Alert> mapper = (rs, i) -> {
            Alert a = new Alert();
            a.setId(rs.getLong("id"));
            Object dealerId = rs.getObject("dealer_id");
            a.setDealerId(dealerId == null ? null : ((Number) dealerId).longValue());
            a.setVin(rs.getString("vin"));
            a.setAlertType(rs.getString("alert_type"));
            a.setSeverity(rs.getString("severity"));
            a.setStatus(rs.getString("status"));
            a.setDetail(rs.getString("detail"));
            java.sql.Timestamp ts = rs.getTimestamp("created_at");
            a.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
            return a;
        };
        return jdbc.query(sql.toString(), mapper, args.toArray());
    }

    /**
     * 处置告警：推进状态 + 记处置人/时间/说明。
     *
     * <p>只允许推进到 HANDLED / CLOSED——OPEN 是初始态不是处置目标。
     * 用 SQL 层的 {@code status IN ('HANDLED','CLOSED')} 兜住非法值，
     * 避免拼错的状态把告警改回 OPEN（那等于"假装没处理过"）。</p>
     */
    @Override
    public int handleAlert(Long alertId, String status, String handler, String note) {
        return jdbc.update(
                "UPDATE alerts SET status=?, handler=?, handled_at=now(), handle_note=? "
                        + "WHERE id=? AND status IN ('HANDLED','CLOSED')",
                status, handler, note, alertId);
    }

    /** 供测试/运维查看最近审计事件。 */
    public List<String> recentAuditEvents(int limit) {
        return jdbc.queryForList("SELECT action || ' | ' || COALESCE(target_id,'') FROM audit_events ORDER BY id DESC LIMIT ?",
                String.class, limit);
    }

    @SuppressWarnings("unused")
    private LocalDateTime now() {
        return LocalDateTime.now();
    }
}
