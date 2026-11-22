package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.ConditionReportType;
import com.company.floorplan.domain.model.RepairHistoryFlag;
import com.company.floorplan.domain.model.VehicleConditionReport;
import com.company.floorplan.domain.repository.VehicleConditionReportRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 車況票读写实现（表 {@code vehicle_condition_reports}，V51 / D04 §3.8）。
 *
 * <p><b>只有 INSERT 与 SELECT，没有 UPDATE/DELETE</b>：接口没声明更新方法，
 * 本类也不提供——票面观察是历史事实，改票＝补录新票（{@code idx_vcr_vin_date}
 * 按 {@code inspected_on DESC} 取最新）。仓储留一个"能改"的口子，
 * R2.3 的取严判定就会读到被覆盖过的旧票，证据链（R6.4）随之失效。</p>
 *
 * <p><b>{@code vin} 不校验存在性</b>：V51 列注释写明建票可能先于建账到达
 * （検査票在落札当晚就推送），因此本表对 {@code vehicle_units} 只是逻辑引用。
 * 这里加一句 {@code EXISTS} 检查就等于把那张合法票据拒收，
 * 与 D04 §3.8 的定源结论相反——不建 FK 是有意的，不校验也是同一决定的另一半。</p>
 *
 * <p><b>本类是 append-only 台账，没有 CAS</b>：V51 无 {@code version} 列，
 * 也只写一次，乐观锁在这里没有适用对象。</p>
 */
@Repository
public class JdbcVehicleConditionReportRepository implements VehicleConditionReportRepository {

    private final JdbcTemplate jdbc;

    public JdbcVehicleConditionReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<VehicleConditionReport> MAPPER = (rs, i) -> {
        VehicleConditionReport r = new VehicleConditionReport();
        r.setId(rs.getLong("id"));
        r.setVin(rs.getString("vin"));
        r.setReportNo(rs.getString("report_no"));
        String type = rs.getString("report_type");
        r.setReportType(type == null ? null : ConditionReportType.valueOf(type));
        r.setInspectedOn(toLocalDate(rs.getDate("inspected_on")));
        r.setInspector(rs.getString("inspector"));
        r.setStructureCondition(rs.getString("structure_condition"));
        String repair = rs.getString("repair_history");
        r.setRepairHistory(repair == null ? null : RepairHistoryFlag.valueOf(repair));
        r.setAccidentHistory(booleanOrNull(rs, "accident_history"));
        r.setModificationFlag(booleanOrNull(rs, "modification_flag"));
        r.setInteriorCondition(rs.getString("interior_condition"));
        r.setExteriorCondition(rs.getString("exterior_condition"));
        r.setOdometerKm(intOrNull(rs, "odometer_km"));
        r.setInspectionExpiryDate(toLocalDate(rs.getDate("inspection_expiry_date")));
        r.setRecycleTicketFlag(booleanOrNull(rs, "recycle_ticket_flag"));
        r.setCompulsoryInsuranceExpiry(toLocalDate(rs.getDate("compulsory_insurance_expiry")));
        r.setAutomobileTaxStatus(rs.getString("automobile_tax_status"));
        r.setPhotoPaths(rs.getString("photo_paths"));
        r.setAttachmentPath(rs.getString("attachment_path"));
        r.setAttachmentDigest(rs.getString("attachment_digest"));
        r.setSourceMessageId(longOrNull(rs, "source_message_id"));
        r.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        r.setCreatedBy(rs.getString("created_by"));
        return r;
    };

    @Override
    public VehicleConditionReport insert(VehicleConditionReport report) {
        if (report == null || report.getVin() == null || report.getVin().isBlank()) {
            throw new BusinessException(ErrorCode.VALID_001, "车况票缺少 vin");
        }
        if (report.getReportType() == null) {
            throw new BusinessException(ErrorCode.VALID_001, "车况票缺少票种 report_type");
        }
        try {
            // RETURNING id 取回主键：本仓库所有 INSERT 都用这个写法（JdbcAdvanceRepository、
            // JdbcSupplierRepository 同款），不再另立 KeyHolder 风格。
            // created_at 交给库默认值 now()，created_by 由应用层从审计主体传入（R13.2 纪律：
            // 主体不接受请求体自报，这里只落已经校验过的值）。
            Long id = jdbc.queryForObject(
                    "INSERT INTO vehicle_condition_reports ("
                            + "vin, report_no, report_type, inspected_on, inspector, "
                            + "structure_condition, repair_history, accident_history, modification_flag, "
                            + "interior_condition, exterior_condition, odometer_km, inspection_expiry_date, "
                            + "recycle_ticket_flag, compulsory_insurance_expiry, automobile_tax_status, "
                            + "photo_paths, attachment_path, attachment_digest, source_message_id, created_by) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                    Long.class,
                    report.getVin().trim(), trim(report.getReportNo()), report.getReportType().name(),
                    toSqlDate(report.getInspectedOn()), trim(report.getInspector()),
                    trim(report.getStructureCondition()), name(report.getRepairHistory()),
                    report.getAccidentHistory(), report.getModificationFlag(),
                    trim(report.getInteriorCondition()), trim(report.getExteriorCondition()),
                    report.getOdometerKm(), toSqlDate(report.getInspectionExpiryDate()),
                    report.getRecycleTicketFlag(), toSqlDate(report.getCompulsoryInsuranceExpiry()),
                    trim(report.getAutomobileTaxStatus()),
                    // photo_paths 是 TEXT 列里的 JSON 数组文本（U-R12：一期本地路径，不接对象存储）。
                    // 本仓储不解析、不重排 JSON：票面给什么就存什么，改写内容等于篡改证据。
                    report.getPhotoPaths(), trim(report.getAttachmentPath()),
                    trim(report.getAttachmentDigest()), report.getSourceMessageId(),
                    trim(report.getCreatedBy()));
            report.setId(id);
            // created_at 由库默认值填充，实体侧回填一次，让"插入即回读"的返回视图与库内行一致
            //（同事务内自己的写入可见，不需要等提交）。
            if (report.getCreatedAt() == null) {
                Timestamp createdAt = jdbc.queryForObject(
                        "SELECT created_at FROM vehicle_condition_reports WHERE id = ?",
                        Timestamp.class, id);
                report.setCreatedAt(toInstant(createdAt));
            }
            return report;
        } catch (DuplicateKeyException ex) {
            // 撞 uk_vcr_report_no（同票号并发/重放）。口径与其余仓储一致：
            // 翻成 CONFLICT_001 抛出，不在同一事务里回查——PG 撞约束后事务已中止，
            // 回查只会得到 "current transaction is aborted"，409 会被 500 覆盖。
            throw new BusinessException(ErrorCode.CONFLICT_001, Map.of(
                    "idempotencyKey", report.getReportNo() == null ? "" : report.getReportNo(),
                    "reason", "DUPLICATE_REPORT_NO",
                    "detail", "同票号车况票已存在（uk_vcr_report_no）"));
        }
    }

    @Override
    public Optional<VehicleConditionReport> findByReportNo(String reportNo) {
        if (reportNo == null || reportNo.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("SELECT * FROM vehicle_condition_reports WHERE report_no = ?",
                MAPPER, reportNo.trim()).stream().findFirst();
    }

    @Override
    public List<VehicleConditionReport> findLatestByVin(String vin, int limit) {
        if (vin == null || vin.isBlank()) {
            return List.of();
        }
        int capped = limit <= 0 ? 1 : Math.min(limit, 200);
        return jdbc.query("SELECT * FROM vehicle_condition_reports WHERE vin = ? "
                        + "ORDER BY inspected_on DESC NULLS LAST, id DESC LIMIT " + capped,
                MAPPER, vin.trim());
    }

    @Override
    public Optional<VehicleConditionReport> findLatestByType(String vin, String reportType, LocalDate from) {
        if (vin == null || vin.isBlank() || reportType == null || reportType.isBlank()) {
            return Optional.empty();
        }
        // from 为空＝不设起始日（R2.3 勾稽要"该型票的最新一张"时就用这条路）。
        // 分支写死两条 SQL，不用 (?::date IS NULL OR inspected_on >= ?::date) 这种把同一参数传两遍的写法：
        // 后者会让优化器拿不到 inspected_on 的范围条件，idx_vcr_vin_date 用不上。
        String sql = "SELECT * FROM vehicle_condition_reports WHERE vin = ? AND report_type = ?"
                + (from == null ? "" : " AND inspected_on >= ?")
                + " ORDER BY inspected_on DESC NULLS LAST, id DESC LIMIT 1";
        // 排序补 id DESC：同一检查日可能有多张同型票（同日补录），
        // 只按 inspected_on 排会让 LIMIT 1 落到哪一张不确定，勾稽结果随之抖动。
        Object[] args = from == null
                ? new Object[] {vin.trim(), reportType.trim()}
                : new Object[] {vin.trim(), reportType.trim(), toSqlDate(from)};
        return jdbc.query(sql, MAPPER, args).stream().findFirst();
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Boolean booleanOrNull(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDate toLocalDate(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Date toSqlDate(LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }
}
