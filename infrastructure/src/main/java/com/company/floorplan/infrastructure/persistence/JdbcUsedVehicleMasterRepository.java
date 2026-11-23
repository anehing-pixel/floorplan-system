package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.CertificateCustodyEvent;
import com.company.floorplan.domain.model.CertificateCustodyEventType;
import com.company.floorplan.domain.model.CertificateSecurityType;
import com.company.floorplan.domain.model.CertificateStatus;
import com.company.floorplan.domain.model.ConditionReportType;
import com.company.floorplan.domain.model.EncumbranceType;
import com.company.floorplan.domain.model.RepairHistory;
import com.company.floorplan.domain.model.UsedVehicleAttributes;
import com.company.floorplan.domain.model.UsedVehicleAttrsView;
import com.company.floorplan.domain.model.VehicleCertificate;
import com.company.floorplan.domain.model.VehicleConditionReport;
import com.company.floorplan.domain.model.WmsDiffSeverity;
import com.company.floorplan.domain.model.WmsDiffType;
import com.company.floorplan.domain.repository.UsedVehicleMasterRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * USED 车辆主数据 / 車検証 / 车况票 / 账实差异的 JDBC 实现（USED R2）。
 *
 * <p>沿用本仓库既有做法：原生 {@code JdbcTemplate} + 手写 SQL，插入走
 * {@code INSERT ... RETURNING id}，日期显式转 {@link Date}/{@link Timestamp}
 * 而不是把 {@code java.time} 直接交给驱动（与 {@code JdbcDealerQualificationRepository} 同因）。
 * 本类引用的<b>每一个列名</b>都同步登记在 {@code SqlStructureSelfCheck} 白名单里——
 * R1 那次「UPDATE dealers 里不存在的 updated_by」是运行期 500 才发现的，
 * 自检加白名单就是为了把这类错误挪到启动期。</p>
 *
 * <p><b>{@code vehicle_units} 没有 {@code created_at}/{@code updated_at} 列</b>（实查 2026-11-23），
 * 所以 {@link #updateUsedAttributes} 的 SET 清单里不写更新时间戳。
 * 想给主数据加审计时间属结构变更，得走新迁移，不能在这里假设列存在。</p>
 */
@Repository
public class JdbcUsedVehicleMasterRepository implements UsedVehicleMasterRepository {

    private final JdbcTemplate jdbc;

    public JdbcUsedVehicleMasterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code vehicle_units} 的 USED 属性列（V50）；不含里程三列，那走 {@link #applyMileageMaster}。 */
    private static final String UPDATE_USED_ATTRS =
            "UPDATE vehicle_units SET registration_plate = ?, first_registered_on = ?, "
                    + "inspection_expiry_date = ?, inspection_remaining_months = ?, repair_history = ?, "
                    + "repair_history_desc = ?, accident_history_flag = ?, auction_grade = ?, sale_channel = ?, "
                    + "warehouse_custodian_code = ?, bin_location = ?, auction_lot_id = ?, "
                    + "updated_at = now() WHERE vin = ?";

    /** 里程回写：{@code mileage} 与 {@code mileage_source} 必须成对出现（V50 ck_vu_mileage_unknown）。 */
    private static final String UPDATE_MILEAGE =
            "UPDATE vehicle_units SET mileage = ?, mileage_source = ?, mileage_captured_on = ?, "
                    + "mileage_unit = 'KM', updated_at = now() WHERE vin = ?";

    /** USED 侧读视图列（与 {@link #UPDATE_USED_ATTRS} 同表，但多里程三列与証書指针）。 */
    private static final String SELECT_USED_ATTRS =
            "SELECT registration_plate, first_registered_on, inspection_expiry_date, "
                    + "inspection_remaining_months, repair_history, repair_history_desc, accident_history_flag, "
                    + "auction_grade, sale_channel, warehouse_custodian_code, bin_location, auction_lot_id, "
                    + "inspection_certificate_id, mileage_source, mileage, mileage_captured_on "
                    + "FROM vehicle_units WHERE vin = ?";

    private static final String INSERT_CERTIFICATE =
            "INSERT INTO inspection_certificates (cert_key, certificate_no, vin, registration_plate, "
                    + "owner_name, owner_address, user_name, user_address, first_registered_on, "
                    + "inspection_expiry_date, issue_date, security_type, mortgage_flag, encumbrance_status, "
                    + "holder_custodian_code, holder_type, custody_location, status, predecessor_id, "
                    + "pledged_at, released_at, lost_at, release_order_no, evidence_path, evidence_digest) "
                    // 25 列 ⟹ 必须正好 25 个 ?。这里多写一个 ? 时 PG 报的是
                    // 「未设定参数值 26」——SqlStructureSelfCheck 查不出来（它比的是列名，不是占位符数），
                    // 只有真正走一次 INSERT 才暴露，所以本行注释把对应关系钉在这里。
                    // updated_at / version 不在此列：库内默认值（now() / 0）负责，见 V52。
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id";

    /** 带 version 乐观锁的整行更新（V52 有 version 列，AGENTS.md §6.3 要求并发更新走它）。 */
    private static final String UPDATE_CERTIFICATE =
            "UPDATE inspection_certificates SET certificate_no = ?, registration_plate = ?, owner_name = ?, "
                    + "owner_address = ?, user_name = ?, user_address = ?, first_registered_on = ?, "
                    + "inspection_expiry_date = ?, issue_date = ?, security_type = ?, mortgage_flag = ?, "
                    + "encumbrance_status = ?, holder_custodian_code = ?, holder_type = ?, custody_location = ?, "
                    + "status = ?, pledged_at = ?, released_at = ?, lost_at = ?, release_order_no = ?, "
                    + "evidence_path = ?, evidence_digest = ?, updated_at = now(), version = version + 1 "
                    + "WHERE id = ? AND version = ?";

    private static final String SELECT_CERTIFICATE =
            "SELECT id, cert_key, certificate_no, vin, registration_plate, owner_name, owner_address, "
                    + "user_name, user_address, first_registered_on, inspection_expiry_date, issue_date, "
                    + "security_type, mortgage_flag, encumbrance_status, holder_custodian_code, holder_type, "
                    + "custody_location, status, predecessor_id, pledged_at, released_at, lost_at, "
                    + "release_order_no, evidence_path, evidence_digest, created_at, updated_at, version "
                    + "FROM inspection_certificates WHERE ";

    /**
     * 「在场」谓词，与 V53 的 {@code uk_cert_active_vin ... WHERE status NOT IN ('REISSUED','LOST')}
     * 逐字同义。改这里必须同时意识到索引没改——所以本常量只在本类出现一次。
     */
    private static final String ACTIVE_CERT_PREDICATE = "status NOT IN ('REISSUED','LOST')";

    private static final String INSERT_CUSTODY_EVENT =
            "INSERT INTO certificate_custody_events (certificate_id, event_type, from_status, to_status, "
                    + "encumbrance_type, check_source, actor_type, actor, occurred_at, evidence_path, reason, "
                    + "trace_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";

    private static final String SELECT_CUSTODY_EVENTS =
            "SELECT id, certificate_id, event_type, from_status, to_status, encumbrance_type, check_source, "
                    + "actor_type, actor, occurred_at, evidence_path, reason, trace_id "
                    + "FROM certificate_custody_events WHERE certificate_id = ? ORDER BY occurred_at DESC, id DESC";

    /**
     * 车况票插入。{@code ON CONFLICT (report_no)} 依赖 V51 的<b>部分</b>唯一索引
     * {@code uk_vcr_report_no ... WHERE report_no IS NOT NULL}——部分索引用作冲突目标时
     * 必须把谓词一起写上，否则 PG 报「there is no unique constraint matching given keys」。
     * 自盘票 {@code report_no} 为 NULL 时根本不会进冲突分支（NULL 不参与该索引）。
     */
    private static final String INSERT_CONDITION_REPORT =
            "INSERT INTO vehicle_condition_reports (vin, report_no, report_type, inspected_on, inspector, "
                    + "structure_condition, repair_history, accident_history, modification_flag, "
                    + "interior_condition, exterior_condition, odometer_km, inspection_expiry_date, "
                    + "recycle_ticket_flag, compulsory_insurance_expiry, automobile_tax_status, photo_paths, "
                    + "attachment_path, attachment_digest, created_by) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                    + "ON CONFLICT (report_no) WHERE report_no IS NOT NULL DO NOTHING RETURNING id";

    private static final String SELECT_CONDITION_REPORTS =
            "SELECT id, vin, report_no, report_type, inspected_on, inspector, structure_condition, "
                    + "repair_history, accident_history, modification_flag, interior_condition, exterior_condition, "
                    + "odometer_km, inspection_expiry_date, recycle_ticket_flag, compulsory_insurance_expiry, "
                    + "automobile_tax_status, photo_paths, attachment_path, attachment_digest, source_message_id, "
                    + "created_at, created_by FROM vehicle_condition_reports WHERE vin = ? "
                    + "ORDER BY inspected_on DESC NULLS LAST, id DESC";

    /** 同日同仓同 VIN 同类型只留一条未关闭结论（V66 两条部分唯一索引的等价写法）。 */
    private static final String INSERT_RECONCILE_DIFF =
            "INSERT INTO wms_reconcile_diffs (biz_date, warehouse_custodian_code, vin, diff_type, severity, "
                    + "our_snapshot, wms_snapshot, status, actor) VALUES (?,?,?,?,?,?,?,?,?) "
                    + "ON CONFLICT DO NOTHING";

    // ── vehicle_units ────────────────────────────────────────────────────

    @Override
    public int updateUsedAttributes(String vin, UsedVehicleAttributes a) {
        return jdbc.update(UPDATE_USED_ATTRS,
                a.registrationPlate(),
                date(a.firstRegisteredOn()),
                date(a.inspectionExpiryDate()),
                a.inspectionRemainingMonths(),
                a.repairHistory() == null ? null : a.repairHistory().name(),
                a.repairHistoryDesc(),
                a.accidentHistoryFlag(),
                a.auctionGrade(),
                a.saleChannel(),
                a.warehouseCustodianCode(),
                a.binLocation(),
                a.auctionLotId(),
                vin);
    }

    @Override
    public int applyMileageMaster(String vin, String mileageSource, Integer mileageKm, LocalDate capturedOn) {
        return jdbc.update(UPDATE_MILEAGE, mileageKm, mileageSource, date(capturedOn), vin);
    }

    @Override
    public Optional<UsedVehicleAttrsView> findUsedAttributes(String vin) {
        return jdbc.query(SELECT_USED_ATTRS, ATTRS_MAPPER, vin).stream().findFirst();
    }

    @Override
    public int bindCertificate(String vin, Long certificateId) {
        // 显式 setNull 而不是直接传 null：inspection_certificate_id 有 V53 物理 FK，
        // 驱动对无类型 null 走 Types.OTHER，多数版本能推断，但解绑（传 null）这一支
        // 在部分驱动上会报 "can't infer the SQL type to use for an instance of null"。
        return jdbc.update("UPDATE vehicle_units SET inspection_certificate_id = ?, updated_at = now() "
                        + "WHERE vin = ?",
                (java.sql.PreparedStatement ps) -> {
                    if (certificateId == null) {
                        ps.setNull(1, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(1, certificateId);
                    }
                    ps.setString(2, vin);
                });
    }

    // ── inspection_certificates ──────────────────────────────────────────

    @Override
    public VehicleCertificate insertCertificate(VehicleCertificate c) {
        Long id = jdbc.queryForObject(INSERT_CERTIFICATE, Long.class,
                c.getCertKey(), c.getCertificateNo(), c.getVin(), c.getRegistrationPlate(),
                c.getOwnerName(), c.getOwnerAddress(), c.getUserName(), c.getUserAddress(),
                date(c.getFirstRegisteredOn()), date(c.getInspectionExpiryDate()), date(c.getIssueDate()),
                c.getSecurityType() == null ? null : c.getSecurityType().name(),
                c.getMortgageFlag(),
                c.getEncumbranceStatus() == null ? null : c.getEncumbranceStatus().name(),
                c.getHolderCustodianCode(), c.getHolderType(), c.getCustodyLocation(),
                c.getStatus().name(), c.getPredecessorId(),
                timestamp(c.getPledgedAt()), timestamp(c.getReleasedAt()), timestamp(c.getLostAt()),
                c.getReleaseOrderNo(), c.getEvidencePath(), c.getEvidenceDigest());
        c.setId(id);
        return c;
    }

    @Override
    public int updateCertificate(VehicleCertificate c) {
        int rows = jdbc.update(UPDATE_CERTIFICATE,
                c.getCertificateNo(), c.getRegistrationPlate(), c.getOwnerName(), c.getOwnerAddress(),
                c.getUserName(), c.getUserAddress(), date(c.getFirstRegisteredOn()),
                date(c.getInspectionExpiryDate()), date(c.getIssueDate()),
                c.getSecurityType() == null ? null : c.getSecurityType().name(),
                c.getMortgageFlag(),
                c.getEncumbranceStatus() == null ? null : c.getEncumbranceStatus().name(),
                c.getHolderCustodianCode(), c.getHolderType(), c.getCustodyLocation(),
                c.getStatus().name(),
                timestamp(c.getPledgedAt()), timestamp(c.getReleasedAt()), timestamp(c.getLostAt()),
                c.getReleaseOrderNo(), c.getEvidencePath(), c.getEvidenceDigest(),
                c.getId(), c.getVersion());
        if (rows > 0) {
            // 库内版本已推进，内存对象跟着走，否则同一请求里二次 update 必然撞乐观锁
            c.setVersion(c.getVersion() + 1);
        }
        return rows;
    }

    @Override
    public Optional<VehicleCertificate> findActiveCertificate(String vin) {
        return jdbc.query(SELECT_CERTIFICATE + "vin = ? AND " + ACTIVE_CERT_PREDICATE,
                CERT_MAPPER, vin).stream().findFirst();
    }

    @Override
    public Optional<VehicleCertificate> findCertificateByNo(String certificateNo) {
        return jdbc.query(SELECT_CERTIFICATE + "certificate_no = ? ORDER BY id DESC",
                CERT_MAPPER, certificateNo).stream().findFirst();
    }

    @Override
    public List<VehicleCertificate> findCertificatesByVin(String vin) {
        return jdbc.query(SELECT_CERTIFICATE + "vin = ? ORDER BY created_at DESC, id DESC", CERT_MAPPER, vin);
    }

    // ── certificate_custody_events ───────────────────────────────────────

    @Override
    public void appendCustodyEvent(CertificateCustodyEvent e) {
        jdbc.update(INSERT_CUSTODY_EVENT,
                e.certificateId(),
                e.eventType().name(),
                e.fromStatus() == null ? null : e.fromStatus().name(),
                e.toStatus() == null ? null : e.toStatus().name(),
                e.encumbranceType() == null ? null : e.encumbranceType().name(),
                e.checkSource(), e.actorType(), e.actor(),
                timestamp(e.occurredAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : e.occurredAt()),
                e.evidencePath(), e.reason(), e.traceId());
    }

    @Override
    public List<CertificateCustodyEvent> findCustodyEvents(Long certificateId) {
        return jdbc.query(SELECT_CUSTODY_EVENTS, CUSTODY_MAPPER, certificateId);
    }

    // ── vehicle_condition_reports ────────────────────────────────────────

    @Override
    public ReportIngestion insertConditionReport(VehicleConditionReport r) {
        // INSERT ... ON CONFLICT DO NOTHING RETURNING id：真插进去就有行，撞上 uk_vcr_report_no 就是空列表。
        // 一次往返同时拿到「落库行 id」和「本次是不是重推」，不必先查后插（并发下那是 TOCTOU）。
        List<Long> ids = jdbc.queryForList(INSERT_CONDITION_REPORT, Long.class, reportArgs(r));
        if (!ids.isEmpty()) {
            return new ReportIngestion(ids.get(0), true);
        }
        // 票号为 null 时不可能走到这个分支：部分唯一索引带 WHERE report_no IS NOT NULL，
        // 无票号的票永远插得进去。所以这里按票号回读既有行 id，回不到也只是 id 缺失，不影响 created=false。
        return new ReportIngestion(findConditionReportIdByNo(r.reportNo()).orElse(null), false);
    }

    /** {@link #INSERT_CONDITION_REPORT} 的绑定值，顺序即列顺序（改列必同步改这里）。 */
    private static Object[] reportArgs(VehicleConditionReport r) {
        return new Object[]{
                r.vin(), r.reportNo(), r.reportType().name(),
                date(r.inspectedOn()), r.inspector(), r.structureCondition(),
                r.repairHistory() == null ? null : r.repairHistory().name(),
                r.accidentHistoryFlag(), r.modificationFlag(), r.interiorCondition(), r.exteriorCondition(),
                r.odometerKm(), date(r.inspectionExpiryDate()), r.recycleTicketFlag(),
                date(r.compulsoryInsuranceExpired()), r.automobileTaxStatus(), r.photoPaths(),
                r.attachmentPath(), r.attachmentDigest(), r.createdBy()};
    }

    /** 按票号回读行 id（幂等分支专用；对外查询请走 VIN 维度，票号是外部系统的键）。 */
    private Optional<Long> findConditionReportIdByNo(String reportNo) {
        if (reportNo == null || reportNo.isBlank()) {
            return Optional.empty();
        }
        return jdbc.queryForList(
                "SELECT id FROM vehicle_condition_reports WHERE report_no = ?", Long.class, reportNo)
                .stream().findFirst();
    }

    @Override
    public List<VehicleConditionReport> findConditionReports(String vin) {
        return jdbc.query(SELECT_CONDITION_REPORTS, CONDITION_MAPPER, vin);
    }

    // ── wms_reconcile_diffs ──────────────────────────────────────────────

    @Override
    public int openReconcileDiff(LocalDate bizDate, String warehouseCustodianCode, String vin,
                                 WmsDiffType diffType, WmsDiffSeverity severity,
                                 String ourSnapshot, String observedSnapshot, String actor) {
        return jdbc.update(INSERT_RECONCILE_DIFF,
                date(bizDate), warehouseCustodianCode, vin, diffType.name(), severity.name(),
                ourSnapshot, observedSnapshot, "OPEN", actor);
    }

    @Override
    public int countOpenDiffs(String vin) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM wms_reconcile_diffs WHERE vin = ? AND status IN ('OPEN','ACKNOWLEDGED')",
                Integer.class, vin);
        return n == null ? 0 : n;
    }

    // ── RowMapper ────────────────────────────────────────────────────────

    /**
     * USED 属性读视图映射。
     *
     * <p>{@code mileage} / {@code auction_lot_id} 等可空数值列走 {@code nullableInt}/{@code nullableLong}：
     * {@code rs.getInt} 会把 NULL 读成 0，而 0 公里在 R3 估值里是一个<b>有效</b>取值，
     * 静默把「未采集」变成「零里程」等于凭空造出一个能过闸门的数。</p>
     */
    private static final RowMapper<UsedVehicleAttrsView> ATTRS_MAPPER = (rs, i) -> new UsedVehicleAttrsView(
            rs.getString("registration_plate"),
            localDate(rs, "first_registered_on"),
            localDate(rs, "inspection_expiry_date"),
            nullableInt(rs, "inspection_remaining_months"),
            RepairHistory.from(rs.getString("repair_history")),
            rs.getString("repair_history_desc"),
            bool(rs, "accident_history_flag"),
            rs.getBigDecimal("auction_grade"),
            rs.getString("sale_channel"),
            rs.getString("warehouse_custodian_code"),
            rs.getString("bin_location"),
            nullableLong(rs, "auction_lot_id"),
            nullableLong(rs, "inspection_certificate_id"),
            rs.getString("mileage_source"),
            nullableInt(rs, "mileage"),
            localDate(rs, "mileage_captured_on"));

    /** 证表映射。枚举列一律走 {@code from(...)}，让库内脏值在读取时就炸出来，而不是漂到判定层。 */
    private static final RowMapper<VehicleCertificate> CERT_MAPPER = (rs, i) -> {
        VehicleCertificate c = new VehicleCertificate();
        c.setId(rs.getLong("id"));
        c.setCertKey(rs.getString("cert_key"));
        c.setCertificateNo(rs.getString("certificate_no"));
        c.setVin(rs.getString("vin"));
        c.setRegistrationPlate(rs.getString("registration_plate"));
        c.setOwnerName(rs.getString("owner_name"));
        c.setOwnerAddress(rs.getString("owner_address"));
        c.setUserName(rs.getString("user_name"));
        c.setUserAddress(rs.getString("user_address"));
        c.setFirstRegisteredOn(localDate(rs, "first_registered_on"));
        c.setInspectionExpiryDate(localDate(rs, "inspection_expiry_date"));
        c.setIssueDate(localDate(rs, "issue_date"));
        c.setSecurityType(CertificateSecurityType.from(rs.getString("security_type")));
        c.setMortgageFlag(bool(rs, "mortgage_flag"));
        c.setEncumbranceStatus(EncumbranceType.from(rs.getString("encumbrance_status")));
        c.setHolderCustodianCode(rs.getString("holder_custodian_code"));
        c.setHolderType(rs.getString("holder_type"));
        c.setCustodyLocation(rs.getString("custody_location"));
        c.setStatus(CertificateStatus.from(rs.getString("status")));
        c.setPredecessorId(nullableLong(rs, "predecessor_id"));
        c.setPledgedAt(offset(rs, "pledged_at"));
        c.setReleasedAt(offset(rs, "released_at"));
        c.setLostAt(offset(rs, "lost_at"));
        c.setReleaseOrderNo(rs.getString("release_order_no"));
        c.setEvidencePath(rs.getString("evidence_path"));
        c.setEvidenceDigest(rs.getString("evidence_digest"));
        c.setCreatedAt(offset(rs, "created_at"));
        c.setUpdatedAt(offset(rs, "updated_at"));
        c.setVersion(rs.getInt("version"));
        return c;
    };

    private static final RowMapper<CertificateCustodyEvent> CUSTODY_MAPPER = (rs, i) ->
            new CertificateCustodyEvent(
                    rs.getLong("id"),
                    rs.getLong("certificate_id"),
                    CertificateCustodyEventType.from(rs.getString("event_type")),
                    CertificateStatus.from(rs.getString("from_status")),
                    CertificateStatus.from(rs.getString("to_status")),
                    EncumbranceType.from(rs.getString("encumbrance_type")),
                    rs.getString("check_source"),
                    rs.getString("actor_type"),
                    rs.getString("actor"),
                    offset(rs, "occurred_at"),
                    rs.getString("evidence_path"),
                    rs.getString("reason"),
                    rs.getString("trace_id"));

    private static final RowMapper<VehicleConditionReport> CONDITION_MAPPER = (rs, i) ->
            new VehicleConditionReport(
                    rs.getLong("id"),
                    rs.getString("vin"),
                    rs.getString("report_no"),
                    ConditionReportType.from(rs.getString("report_type")),
                    localDate(rs, "inspected_on"),
                    rs.getString("inspector"),
                    rs.getString("structure_condition"),
                    RepairHistory.from(rs.getString("repair_history")),
                    bool(rs, "accident_history"),
                    bool(rs, "modification_flag"),
                    rs.getString("interior_condition"),
                    rs.getString("exterior_condition"),
                    nullableInt(rs, "odometer_km"),
                    localDate(rs, "inspection_expiry_date"),
                    bool(rs, "recycle_ticket_flag"),
                    localDate(rs, "compulsory_insurance_expiry"),
                    rs.getString("automobile_tax_status"),
                    rs.getString("photo_paths"),
                    rs.getString("attachment_path"),
                    rs.getString("attachment_digest"),
                    nullableLong(rs, "source_message_id"),
                    offset(rs, "created_at"),
                    rs.getString("created_by"));

    // ── 类型转换小工具（NULL 必须显式处理：rs.getInt 把 NULL 读成 0，那是数据错误不是缺值）──

    private static Date date(LocalDate v) {
        return v == null ? null : Date.valueOf(v);
    }

    private static Timestamp timestamp(OffsetDateTime v) {
        return v == null ? null : Timestamp.from(v.toInstant());
    }

    private static LocalDate localDate(ResultSet rs, String col) throws SQLException {
        Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    private static OffsetDateTime offset(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static Boolean bool(ResultSet rs, String col) throws SQLException {
        boolean v = rs.getBoolean(col);
        return rs.wasNull() ? null : v;
    }

    private static Integer nullableInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }
}
