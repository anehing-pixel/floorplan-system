package com.company.floorplan.infrastructure.persistence;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动期 SQL 结构自检：核对关键 INSERT 语句的列名都真实存在于表中。
 *
 * <p><b>为什么需要它</b>：手写长 INSERT 时，最容易犯的错不是占位符个数
 * （那个还能被 JDBC 在运行时报"栏位索引超过许可范围"），
 * 而是<b>列名写错或列顺序与 VALUES 不对应</b>——
 * 前者会报"列不存在"，后者会安静地把值写进<b>错误的列</b>，
 * 数据错位却没有任何异常，直到对账时才发现。
 * 这类错误在开发期每分钟都在制造，修一次可能只是把一个值挪个位置，
 * 漏一次就是一批错账。</p>
 *
 * <p><b>做法</b>：应用启动完成后（Flyway 迁移已执行、表结构已就位），
 * 查出各表的实际列名，与代码里 SQL 声明的列名逐一比对，
 * 发现差异就<b>打印醒目的 ERROR 日志</b>（不阻止启动——
 * 阻止启动会让开发环境寸步难行），让问题在第一次跑业务之前就暴露。</p>
 *
 * <p><b>覆盖范围的边界（勿把"自检通过"当成"这张表的 SQL 都对"）</b>：
 * 一张表在本清单里只有一条登记，取的是<b>写路径的主 SQL 列集</b>（一般是 INSERT）。
 * 两处已知不在覆盖内：
 * (1) {@code vehicle_units} 登记的是 LEGACY {@code JdbcVehicleUnitRepository} 的 INSERT 列，
 * USED 属性那条 {@code UPDATE ... SET} 的列（V50 新增的 {@code mileage_source}、
 * {@code inspection_remaining_months} 等）不在其中；
 * (2) 同一条 UPDATE 语句里"列顺序 vs 参数顺序"的错位本类查不出来——
 * 它只比集合包含关系，不看顺序。第 (2) 点靠的是各仓储把
 * SET 片段与绑定参数在同一处成对累积（见 {@code JdbcUsedVehicleAttributesRepository#addSet}）。</p>
 */
@Component
@Order(100)
public class SqlStructureSelfCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SqlStructureSelfCheck.class);

    private final JdbcTemplate jdbc;

    /** 表名 → 代码中 INSERT 使用的列名（顺序即 VALUES 顺序）。 */
    private static final java.util.Map<String, List<String>> CHECKED = new java.util.LinkedHashMap<>();

    static {
        CHECKED.put("vehicle_units", Arrays.asList(
                "vin", "dealer_id", "credit_line_id", "advance_id", "brand", "model", "model_year",
                "mileage", "color", "vehicle_type", "supplier_name", "purchase_channel",
                "invoice_amount", "advance_amount", "advance_ratio", "advanced_at",
                "principal_balance", "paid_principal", "annual_rate", "rate_type",
                "accrued_interest", "last_interest_date", "stock_in_date", "sold_at",
                "settle_due_date", "settled_at", "aging_days", "turnover_days", "status",
                "security_type", "security_status", "overdue_days", "risk_level",
                "oot_suspected", "last_audit_date", "audit_mismatch_count", "version",
                "purchase_order_id"));
        CHECKED.put("collateral_ledger", Arrays.asList(
                "vin", "dealer_id", "financed", "financed_at", "advance_no", "sold", "sold_at",
                "sold_flagged_by", "repaid", "repaid_at", "repaid_amount", "released", "released_at",
                "release_reason", "security_type", "security_status", "required_account_ref",
                "account_checked", "account_checked_at", "account_checked_by", "last_audit_date",
                "audit_mismatch_count", "last_audit_by", "disposal_case_no", "disposed_at",
                "registration_due", "registration_note"));
        CHECKED.put("collateral_registration_batches", Arrays.asList(
                "batch_no", "registration_no", "registration_type", "perfection_method",
                "registry_office", "registered_at", "expires_at", "contract_attachment_id",
                "contract_no", "contract_signed_at", "certificate_attachment_id", "certificate_no",
                "certificate_uploaded_at", "cancellation_status", "cancellation_no",
                "cancellation_applied_at", "cancellation_completed_at",
                "cancellation_certificate_attachment_id", "cancellation_requested_by",
                "cancellation_note", "priority_rank", "co_signed_by", "vehicle_count", "created_by"));
        CHECKED.put("collateral_registration_items", Arrays.asList(
                "batch_id", "ledger_id", "vin"));
        CHECKED.put("collateral_evidence_links", Arrays.asList(
                "ledger_id", "vin", "evidence_type", "ref_type", "ref_id", "attachment_id",
                "event_at", "note", "recorded_by"));
        CHECKED.put("financing_terms", Arrays.asList(
                "vin", "change_type", "old_maturity_date", "new_maturity_date",
                "old_grace_days", "new_grace_days", "term_source", "source_ref",
                "reason", "operator"));
        CHECKED.put("domain_events", Arrays.asList(
                "event_type", "source", "dealer_id", "vin", "biz_key", "payload",
                "ref_type", "ref_id", "event_time", "received_at", "status",
                "error_detail", "processed_at"));
        CHECKED.put("risk_alerts", Arrays.asList(
                "alert_code", "severity", "dealer_id", "vin", "batch_id",
                "metric_value", "threshold_value", "detail", "evidence_refs",
                "first_seen_at", "last_seen_at", "occurrence_count", "status"));
        CHECKED.put("risk_rules", Arrays.asList(
                "rule_code", "metric", "threshold_type", "threshold_value", "unit",
                "severity", "window_days", "enabled", "applies_to", "updated_by"));
        CHECKED.put("work_orders", Arrays.asList(
                "order_no", "order_type", "source_alert_id", "dealer_id", "vin",
                "batch_id", "severity", "priority", "status", "assignee",
                "sla_due_at", "sla_breached", "resolution", "resolution_code",
                "created_by", "acknowledged_at", "resolved_at"));
        CHECKED.put("work_order_actions", Arrays.asList(
                "order_id", "action_type", "action_data", "attachment_id",
                "operator", "operated_at"));
        CHECKED.put("risk_concentration_snapshots", Arrays.asList(
                "biz_date", "dimension", "dimension_key", "exposure",
                "total_exposure", "ratio", "vehicle_count"));
        CHECKED.put("advances", Arrays.asList(
                "advance_no", "vin", "credit_line_id", "amount", "paid_at", "status", "payment_ref",
                "idempotency_key", "purchase_order_id", "payee_supplier_id", "payee_supplier_type",
                "payee_account_snapshot", "applicant", "reviewer", "reviewed_at", "disburser"));
        /* ---- USED 域（W-U1，V40–V54）----
         * 列名取自本包各仓储的 INSERT 清单；比对方向是"代码写了的列，表里必须有"，
         * 迁移漏执行或列名手抖都会在启动日志里直接报出来，而不是等第一次写业务数据时 500。 */
        CHECKED.put("dealer_licenses", Arrays.asList(
                "dealer_id", "license_type", "license_no", "competent_authority", "issued_on",
                "expires_on", "status", "evidence_path", "evidence_digest", "verified_by",
                "verified_at", "created_by", "updated_by"));
        CHECKED.put("dealer_auction_memberships", Arrays.asList(
                "dealer_id", "auction_house_code", "member_no", "member_grade", "joined_on",
                "expires_on", "status", "evidence_path", "created_by", "updated_by"));
        CHECKED.put("dealer_compliance_checks", Arrays.asList(
                "dealer_id", "check_type", "checked_on", "valid_until", "result", "source",
                "request_no", "evidence_path", "note", "checked_by", "created_by", "updated_by"));
        CHECKED.put("dealer_score_reviews", Arrays.asList(
                "dealer_id", "review_year", "operating_score", "stock_quality_score", "status",
                "scored_by", "confirmed_by", "evidence_path", "note", "idempotency_key",
                "request_fingerprint", "version"));
        CHECKED.put("inspection_certificates", Arrays.asList(
                "cert_key", "certificate_no", "vin", "registration_plate", "owner_name", "user_name",
                "inspection_expiry_date", "issue_date", "status", "predecessor_id", "evidence_path",
                "version"));
        CHECKED.put("certificate_custody_events", Arrays.asList(
                "certificate_id", "event_type", "from_status", "to_status", "encumbrance_type",
                "check_source", "actor_type", "actor", "occurred_at", "evidence_path", "reason",
                "trace_id"));
        CHECKED.put("vehicle_condition_reports", Arrays.asList(
                "vin", "report_no", "report_type", "inspected_on", "inspector", "structure_condition",
                "repair_history", "accident_history", "modification_flag", "interior_condition",
                "exterior_condition", "odometer_km", "inspection_expiry_date", "recycle_ticket_flag",
                "compulsory_insurance_expiry", "automobile_tax_status", "photo_paths",
                "attachment_path", "attachment_digest", "source_message_id", "created_by"));
        // 只读表：本包不 INSERT（种子行在 V41 迁移里），列名取自选库后的 RowMapper 取值列。
        // 登记进来仍然有意义——SELECT * 配按列名取值的 mapper，列名写错是运行期才炸的 SQLException。
        CHECKED.put("used_config_params", Arrays.asList(
                "id", "param_key", "param_value", "value_type", "unit", "status", "effective_on",
                "expired_on", "source_ref", "approved_by", "note", "created_at", "updated_at",
                "created_by", "updated_by", "version"));
    }

    public SqlStructureSelfCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        int problems = 0;
        for (java.util.Map.Entry<String, List<String>> entry : CHECKED.entrySet()) {
            String table = entry.getKey();
            List<String> expected = entry.getValue();
            Set<String> actual = actualColumns(table);
            if (actual.isEmpty()) {
                log.error("[SQL 自检] 表 {} 不存在或无法读取 —— 请确认 Flyway 迁移是否已执行", table);
                problems++;
                continue;
            }
            // 代码里用到但表里没有的列 → 必然运行时报错
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(actual);
            if (!missing.isEmpty()) {
                log.error("[SQL 自检] 表 {} 缺少代码 INSERT 使用的列：{} —— 相关接口会 500", table, missing);
                problems++;
            }
            // 表里有但代码没用的列 → 不影响正确性，仅提示（可能是漏传值）
            Set<String> unused = new LinkedHashSet<>(actual);
            unused.removeAll(expected);
            if (!unused.isEmpty()) {
                log.info("[SQL 自检] 表 {} 存在未被 INSERT 使用的列（多为库管字段，可忽略）：{}", table, unused);
            }
        }
        if (problems > 0) {
            log.error("[SQL 自检] 发现 {} 处 SQL 与表结构不一致，请修复后再跑业务——"
                    + "错误的列顺序会把值写进错误的字段，比直接报错更危险", problems);
        } else {
            log.info("[SQL 自检] 通过：{} 张表的 INSERT 列名与数据库结构一致", CHECKED.size());
        }
    }

    /** 查表实际列名。 */
    private Set<String> actualColumns(String table) {
        try {
            return new LinkedHashSet<>(jdbc.queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_name = ?",
                    String.class, table));
        } catch (Exception ex) {
            return Set.of();
        }
    }
}
