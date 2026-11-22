package com.company.floorplan.collateral.repository;

import com.company.floorplan.collateral.model.Registration;
import com.company.floorplan.collateral.model.RegistrationEventType;
import com.company.floorplan.collateral.model.RegistrationItem;
import com.company.floorplan.collateral.model.RegistrationStatus;
import com.company.floorplan.collateral.model.SourceSnapshot;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 担保权属仓储：权威表 collateral_registration + 流水 + 三源快照，表结构见 V18。
 *
 * <p>当前权属用 upsert（ON CONFLICT 按 VIN 覆盖），流水与快照只追加不更新——
 * 这样"当前事实"与"历史留痕"分离，可回看每次变更依据。</p>
 *
 * <p><b>ADR-Q27</b>：本类读写的是 V18 投影/留痕表；放款门禁的权威查询走
 * {@link #findAuthoritativeByVin(String)} 直读 V10，不经过本表。</p>
 */
public class JdbcCollateralRegistrationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCollateralRegistrationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Registration> REGISTRATION_MAPPER = (rs, i) -> {
        Registration r = new Registration();
        r.setVin(rs.getString("vin"));
        r.setOwnerName(rs.getString("owner_name"));
        r.setRegNo(rs.getString("reg_no"));
        r.setStatus(RegistrationStatus.fromCode(rs.getString("status")));
        Object amount = rs.getObject("amount");
        r.setAmount(amount == null ? null : ((Number) amount).longValue());
        r.setRegisteredAt(toLocalDateTime(rs.getTimestamp("registered_at")));
        r.setDischargedAt(toLocalDateTime(rs.getTimestamp("discharged_at")));
        r.setSource(rs.getString("source"));
        r.setUpdatedAt(toLocalDateTime(rs.getTimestamp("updated_at")));
        return r;
    };

    private static final RowMapper<RegistrationItem> ITEM_MAPPER = (rs, i) -> {
        RegistrationItem item = new RegistrationItem();
        item.setId(rs.getLong("id"));
        item.setVin(rs.getString("vin"));
        item.setEventType(parseEvent(rs.getString("event_type")));
        item.setRegNo(rs.getString("reg_no"));
        item.setOwnerName(rs.getString("owner_name"));
        item.setStatus(RegistrationStatus.fromCode(rs.getString("status")));
        Object amount = rs.getObject("amount");
        item.setAmount(amount == null ? null : ((Number) amount).longValue());
        item.setSource(rs.getString("source"));
        item.setNote(rs.getString("note"));
        item.setOccurredAt(toLocalDateTime(rs.getTimestamp("occurred_at")));
        return item;
    };

    private static final RowMapper<SourceSnapshot> SNAPSHOT_MAPPER = (rs, i) -> {
        SourceSnapshot s = new SourceSnapshot();
        s.setId(rs.getLong("id"));
        s.setVin(rs.getString("vin"));
        s.setSource(rs.getString("source"));
        s.setStatus(RegistrationStatus.fromCode(rs.getString("status")));
        s.setRegNo(rs.getString("reg_no"));
        s.setOwnerName(rs.getString("owner_name"));
        Object amount = rs.getObject("amount");
        s.setAmount(amount == null ? null : ((Number) amount).longValue());
        s.setRawPayload(rs.getString("raw_payload"));
        s.setCapturedAt(toLocalDateTime(rs.getTimestamp("captured_at")));
        return s;
    };

    public Optional<Registration> findCurrentByVin(String vin) {
        List<Registration> rows = jdbc.query(
                "SELECT * FROM collateral_registration WHERE vin = :vin",
                new MapSqlParameterSource("vin", vin), REGISTRATION_MAPPER);
        return rows.stream().findFirst();
    }

    /**
     * <b>ADR-Q27</b>：放款门禁的权威查询——直读 V10 登记批次表（system of record）。
     *
     * <p>刻意<b>不读</b> V18 投影表：投影是派生数据，允许延迟或缺失，
     * 拿它做门禁判定会误放行。这里按 VIN 从 {@code items → batches} 取最新有效批次：
     * 登记编号非空且抹消未完成（{@code cancellation_status NOT IN ('DONE','CANCELLED')}）
     * 才是 {@code REGISTERED}；查不到就返回 empty，由 service 兜底成 {@code NONE}（fail-closed）。</p>
     *
     * <p>所有服务共用同一个 PostgreSQL 库，V10 三表由 web 的 Flyway 统一建立，
     * 因此本服务可只读 JOIN；将来拆库时把本方法换成 outbox 投影查询即可，接口不变。</p>
     */
    public Optional<Registration> findAuthoritativeByVin(String vin) {
        List<Registration> rows = jdbc.query(
                "SELECT i.vin, b.registration_no, b.owner_name, b.registered_at "
                        + "FROM collateral_registration_items i "
                        + "JOIN collateral_registration_batches b ON b.id = i.batch_id "
                        + "WHERE i.vin = :vin "
                        + "  AND b.registration_no IS NOT NULL "
                        + "  AND b.cancellation_status NOT IN ('DONE','CANCELLED') "
                        + "ORDER BY b.registered_at DESC NULLS LAST, b.id DESC "
                        + "LIMIT 1",
                new MapSqlParameterSource("vin", vin),
                (rs, i) -> Registration.registered(
                        rs.getString("vin"),
                        rs.getString("owner_name"),
                        rs.getString("registration_no"),
                        null,
                        toLocalDateTime(rs.getTimestamp("registered_at")),
                        "REGISTRATION_BATCH"));
        return rows.stream().findFirst();
    }

    /** 按 VIN 覆盖当前权属。updated_at 由数据库默认值维护。 */
    public int upsertCurrent(Registration r) {
        return jdbc.update(
                "INSERT INTO collateral_registration "
                        + "(vin, owner_name, reg_no, status, amount, registered_at, discharged_at, source) "
                        + "VALUES (:vin, :ownerName, :regNo, :status, :amount, :registeredAt, :dischargedAt, :source) "
                        + "ON CONFLICT (vin) DO UPDATE SET "
                        + " owner_name = EXCLUDED.owner_name, reg_no = EXCLUDED.reg_no, "
                        + " status = EXCLUDED.status, amount = EXCLUDED.amount, "
                        + " registered_at = EXCLUDED.registered_at, discharged_at = EXCLUDED.discharged_at, "
                        + " source = EXCLUDED.source, updated_at = now()",
                new MapSqlParameterSource()
                        .addValue("vin", r.getVin())
                        .addValue("ownerName", r.getOwnerName())
                        .addValue("regNo", r.getRegNo())
                        .addValue("status", r.getStatus() == null ? "NONE" : r.getStatus().name())
                        .addValue("amount", r.getAmount())
                        .addValue("registeredAt", r.getRegisteredAt())
                        .addValue("dischargedAt", r.getDischargedAt())
                        .addValue("source", r.getSource()));
    }

    /** 追加一条权属流水。 */
    public int appendItem(RegistrationItem item) {
        return jdbc.update(
                "INSERT INTO collateral_registration_history "
                        + "(vin, event_type, reg_no, owner_name, status, amount, source, note, occurred_at) "
                        + "VALUES (:vin, :eventType, :regNo, :ownerName, :status, :amount, :source, :note, :occurredAt)",
                new MapSqlParameterSource()
                        .addValue("vin", item.getVin())
                        .addValue("eventType", item.getEventType() == null ? "CHANGE" : item.getEventType().name())
                        .addValue("regNo", item.getRegNo())
                        .addValue("ownerName", item.getOwnerName())
                        .addValue("status", item.getStatus() == null ? "NONE" : item.getStatus().name())
                        .addValue("amount", item.getAmount())
                        .addValue("source", item.getSource())
                        .addValue("note", item.getNote())
                        .addValue("occurredAt", item.getOccurredAt() == null ? LocalDateTime.now() : item.getOccurredAt()));
    }

    public List<RegistrationItem> findHistoryByVin(String vin) {
        return jdbc.query(
                "SELECT * FROM collateral_registration_history WHERE vin = :vin ORDER BY occurred_at DESC, id DESC",
                new MapSqlParameterSource("vin", vin), ITEM_MAPPER);
    }

    /** 追加一条三源快照（raw_payload 为 JSONB，用 ::jsonb 转换）。 */
    public int saveSnapshot(SourceSnapshot s) {
        return jdbc.update(
                "INSERT INTO collateral_source_snapshot "
                        + "(vin, source, status, reg_no, owner_name, amount, raw_payload, captured_at) "
                        + "VALUES (:vin, :source, :status, :regNo, :ownerName, :amount, "
                        + " CAST(:rawPayload AS jsonb), :capturedAt)",
                new MapSqlParameterSource()
                        .addValue("vin", s.getVin())
                        .addValue("source", s.getSource())
                        .addValue("status", s.getStatus() == null ? "NONE" : s.getStatus().name())
                        .addValue("regNo", s.getRegNo())
                        .addValue("ownerName", s.getOwnerName())
                        .addValue("amount", s.getAmount())
                        .addValue("rawPayload", s.getRawPayload() == null || s.getRawPayload().isBlank()
                                ? "{}" : s.getRawPayload())
                        .addValue("capturedAt", s.getCapturedAt() == null ? LocalDateTime.now() : s.getCapturedAt()));
    }

    public List<SourceSnapshot> findSnapshotsByVin(String vin) {
        return jdbc.query(
                "SELECT * FROM collateral_source_snapshot WHERE vin = :vin ORDER BY captured_at DESC, id DESC",
                new MapSqlParameterSource("vin", vin), SNAPSHOT_MAPPER);
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static RegistrationEventType parseEvent(String code) {
        if (code == null) {
            return null;
        }
        try {
            return RegistrationEventType.valueOf(code);
        } catch (IllegalArgumentException ex) {
            return RegistrationEventType.CHANGE;
        }
    }
}
