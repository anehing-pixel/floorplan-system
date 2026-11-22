package com.company.floorplan.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.company.floorplan.domain.model.CollateralRegistrationBatch;
import com.company.floorplan.domain.repository.CollateralRegistrationBatchRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 担保登记批次仓储 JDBC 实现。
 */
@Repository
public class JdbcCollateralRegistrationBatchRepository implements CollateralRegistrationBatchRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<CollateralRegistrationBatch> MAPPER = (rs, i) -> {
        CollateralRegistrationBatch b = new CollateralRegistrationBatch();
        b.setId(rs.getLong("id"));
        b.setBatchNo(rs.getString("batch_no"));
        b.setRegistrationNo(rs.getString("registration_no"));
        b.setRegistrationType(rs.getString("registration_type"));
        b.setPerfectionMethod(rs.getString("perfection_method"));
        b.setRegistryOffice(rs.getString("registry_office"));
        Timestamp registeredAt = rs.getTimestamp("registered_at");
        b.setRegisteredAt(registeredAt == null ? null : registeredAt.toLocalDateTime());
        Timestamp expiresAt = rs.getTimestamp("expires_at");
        b.setExpiresAt(expiresAt == null ? null : expiresAt.toLocalDateTime());
        long contractId = rs.getLong("contract_attachment_id");
        b.setContractAttachmentId(rs.getObject("contract_attachment_id") == null ? null : contractId);
        b.setContractNo(rs.getString("contract_no"));
        Timestamp signedAt = rs.getTimestamp("contract_signed_at");
        b.setContractSignedAt(signedAt == null ? null : signedAt.toLocalDateTime());
        long certId = rs.getLong("certificate_attachment_id");
        b.setCertificateAttachmentId(rs.getObject("certificate_attachment_id") == null ? null : certId);
        b.setCertificateNo(rs.getString("certificate_no"));
        Timestamp certUploaded = rs.getTimestamp("certificate_uploaded_at");
        b.setCertificateUploadedAt(certUploaded == null ? null : certUploaded.toLocalDateTime());
        b.setCancellationStatus(rs.getString("cancellation_status"));
        b.setCancellationNo(rs.getString("cancellation_no"));
        Timestamp appliedAt = rs.getTimestamp("cancellation_applied_at");
        b.setCancellationAppliedAt(appliedAt == null ? null : appliedAt.toLocalDateTime());
        Timestamp completedAt = rs.getTimestamp("cancellation_completed_at");
        b.setCancellationCompletedAt(completedAt == null ? null : completedAt.toLocalDateTime());
        long cancelCertId = rs.getLong("cancellation_certificate_attachment_id");
        b.setCancellationCertificateAttachmentId(
                rs.getObject("cancellation_certificate_attachment_id") == null ? null : cancelCertId);
        b.setCancellationRequestedBy(rs.getString("cancellation_requested_by"));
        b.setCancellationNote(rs.getString("cancellation_note"));
        int rank = rs.getInt("priority_rank");
        b.setPriorityRank(rs.getObject("priority_rank") == null ? null : rank);
        b.setCoSignedBy(rs.getString("co_signed_by"));
        b.setOwnerName(rs.getString("owner_name"));
        b.setVehicleCount(rs.getInt("vehicle_count"));
        b.setCreatedBy(rs.getString("created_by"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        b.setCreatedAt(createdAt == null ? null : createdAt.toLocalDateTime());
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        b.setUpdatedAt(updatedAt == null ? null : updatedAt.toLocalDateTime());
        return b;
    };

    public JdbcCollateralRegistrationBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CollateralRegistrationBatch> findById(Long id) {
        return jdbc.query("SELECT * FROM collateral_registration_batches WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    @Override
    public Optional<CollateralRegistrationBatch> findByBatchNo(String batchNo) {
        return jdbc.query("SELECT * FROM collateral_registration_batches WHERE batch_no = ?", MAPPER, batchNo)
                .stream().findFirst();
    }

    @Override
    public Optional<CollateralRegistrationBatch> findByRegistrationNo(String registrationNo) {
        return jdbc.query("SELECT * FROM collateral_registration_batches WHERE registration_no = ?",
                        MAPPER, registrationNo).stream().findFirst();
    }

    @Override
    public List<CollateralRegistrationBatch> findAll() {
        return jdbc.query("SELECT * FROM collateral_registration_batches ORDER BY id DESC", MAPPER);
    }

    @Override
    public List<String> findVinsByBatchId(Long batchId) {
        return jdbc.query("SELECT vin FROM collateral_registration_items WHERE batch_id = ? ORDER BY vin",
                (rs, i) -> rs.getString("vin"), batchId);
    }

    @Override
    public List<CollateralRegistrationBatch> findByVin(String vin) {
        return jdbc.query("SELECT b.* FROM collateral_registration_batches b "
                        + "JOIN collateral_registration_items i ON i.batch_id = b.id "
                        + "WHERE i.vin = ? ORDER BY b.id DESC",
                MAPPER, vin);
    }

    @Override
    public List<CollateralRegistrationBatch> findExpiring(LocalDateTime deadline) {
        return jdbc.query("SELECT * FROM collateral_registration_batches WHERE expires_at IS NOT NULL "
                        + "AND expires_at <= ? AND cancellation_status NOT IN ('DONE','CANCELLED') "
                        + "ORDER BY expires_at",
                MAPPER, Timestamp.valueOf(deadline));
    }

    @Override
    public List<CollateralRegistrationBatch> findCancellationPending() {
        return jdbc.query("SELECT * FROM collateral_registration_batches "
                        + "WHERE cancellation_status IN ('REQUESTED','APPLIED') ORDER BY id",
                MAPPER);
    }

    @Override
    public CollateralRegistrationBatch save(CollateralRegistrationBatch b) {
        if (b.getId() == null) {
            Long id = jdbc.queryForObject(
                    "INSERT INTO collateral_registration_batches ("
                            + "batch_no, registration_no, registration_type, perfection_method, "
                            + "registry_office, registered_at, expires_at, contract_attachment_id, "
                            + "contract_no, contract_signed_at, certificate_attachment_id, certificate_no, "
                            + "certificate_uploaded_at, cancellation_status, cancellation_no, "
                            + "cancellation_applied_at, cancellation_completed_at, "
                            + "cancellation_certificate_attachment_id, cancellation_requested_by, "
                            + "cancellation_note, priority_rank, co_signed_by, owner_name, vehicle_count, created_by) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                    Long.class,
                    b.getBatchNo(), b.getRegistrationNo(), b.getRegistrationType(), b.getPerfectionMethod(),
                    b.getRegistryOffice(), ts(b.getRegisteredAt()), ts(b.getExpiresAt()),
                    b.getContractAttachmentId(), b.getContractNo(), ts(b.getContractSignedAt()),
                    b.getCertificateAttachmentId(), b.getCertificateNo(), ts(b.getCertificateUploadedAt()),
                    b.getCancellationStatus(), b.getCancellationNo(), ts(b.getCancellationAppliedAt()),
                    ts(b.getCancellationCompletedAt()), b.getCancellationCertificateAttachmentId(),
                    b.getCancellationRequestedBy(), b.getCancellationNote(), b.getPriorityRank(),
                    b.getCoSignedBy(), b.getOwnerName(), b.getVehicleCount(), b.getCreatedBy());
            b.setId(id);
        } else {
            jdbc.update("UPDATE collateral_registration_batches SET "
                            + "registration_no=?, registration_type=?, perfection_method=?, registry_office=?, "
                            + "registered_at=?, expires_at=?, contract_attachment_id=?, contract_no=?, "
                            + "contract_signed_at=?, certificate_attachment_id=?, certificate_no=?, "
                            + "certificate_uploaded_at=?, cancellation_status=?, cancellation_no=?, "
                            + "cancellation_applied_at=?, cancellation_completed_at=?, "
                            + "cancellation_certificate_attachment_id=?, cancellation_requested_by=?, "
                            + "cancellation_note=?, priority_rank=?, co_signed_by=?, owner_name=?, vehicle_count=?, "
                            + "updated_at=now() WHERE id=?",
                    b.getRegistrationNo(), b.getRegistrationType(), b.getPerfectionMethod(),
                    b.getRegistryOffice(), ts(b.getRegisteredAt()), ts(b.getExpiresAt()),
                    b.getContractAttachmentId(), b.getContractNo(), ts(b.getContractSignedAt()),
                    b.getCertificateAttachmentId(), b.getCertificateNo(), ts(b.getCertificateUploadedAt()),
                    b.getCancellationStatus(), b.getCancellationNo(), ts(b.getCancellationAppliedAt()),
                    ts(b.getCancellationCompletedAt()), b.getCancellationCertificateAttachmentId(),
                    b.getCancellationRequestedBy(), b.getCancellationNote(), b.getPriorityRank(),
                    b.getCoSignedBy(), b.getOwnerName(), b.getVehicleCount(), b.getId());
        }
        return b;
    }

    @Override
    public void addItem(Long batchId, Long ledgerId, String vin) {
        // ON CONFLICT DO NOTHING：同一批次重复挂同一台车是幂等的（重放登记请求不报错）
        jdbc.update("INSERT INTO collateral_registration_items (batch_id, ledger_id, vin) "
                        + "VALUES (?,?,?) ON CONFLICT (batch_id, vin) DO NOTHING",
                batchId, ledgerId, vin);
        // 冗余计数随明细变化
        jdbc.update("UPDATE collateral_registration_batches SET vehicle_count = "
                + "(SELECT COUNT(*) FROM collateral_registration_items WHERE batch_id = ?), "
                + "updated_at = now() WHERE id = ?", batchId, batchId);
        syncProjection(vin);
    }

    @Override
    public void removeItem(Long batchId, String vin) {
        jdbc.update("DELETE FROM collateral_registration_items WHERE batch_id = ? AND vin = ?",
                batchId, vin);
        jdbc.update("UPDATE collateral_registration_batches SET vehicle_count = "
                + "(SELECT COUNT(*) FROM collateral_registration_items WHERE batch_id = ?), "
                + "updated_at = now() WHERE id = ?", batchId, batchId);
        syncProjection(vin);
    }

    /**
     * <b>ADR-Q27</b>：V10 是唯一权威源，V18 {@code collateral_registration} 是只读投影。
     *
     * <p>挂车/摘车后用<b>同一个 JdbcTemplate、同一个事务</b>按 V10 重新推导该 VIN 的
     * 权威状态并 upsert 投影，顺便追加一条流水。这样两张表永远同源，
     * 不需要 MQ / outbox / 分布式事务（所有服务共用一个 PostgreSQL 库）。</p>
     *
     * <p><b>best-effort</b>：投影是派生数据，放款门禁不读它（直接 JOIN V10），
     * 因此投影同步失败<b>绝不阻断</b> V10 权威写入，只记日志。
     * 这是刻意的设计——权威源的正确性不依赖派生数据的可用性。</p>
     */
    private void syncProjection(String vin) {
        try {
            List<ProjectionRow> rows = jdbc.query(
                    "SELECT b.registration_no, b.owner_name, b.registered_at "
                            + "FROM collateral_registration_items i "
                            + "JOIN collateral_registration_batches b ON b.id = i.batch_id "
                            + "WHERE i.vin = ? AND b.registration_no IS NOT NULL "
                            + "  AND b.cancellation_status NOT IN ('DONE','CANCELLED') "
                            + "ORDER BY b.registered_at DESC NULLS LAST, b.id DESC LIMIT 1",
                    (rs, i) -> new ProjectionRow(
                            rs.getString("registration_no"),
                            rs.getString("owner_name"),
                            rs.getTimestamp("registered_at")),
                    vin);
            ProjectionRow row = rows.isEmpty() ? null : rows.get(0);
            jdbc.update("INSERT INTO collateral_registration "
                            + "(vin, owner_name, reg_no, status, registered_at, discharged_at, source, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'REGISTRATION_BATCH', now()) "
                            + "ON CONFLICT (vin) DO UPDATE SET "
                            + " owner_name = EXCLUDED.owner_name, reg_no = EXCLUDED.reg_no, "
                            + " status = EXCLUDED.status, registered_at = EXCLUDED.registered_at, "
                            + " discharged_at = EXCLUDED.discharged_at, source = EXCLUDED.source, "
                            + " updated_at = now()",
                    vin,
                    row == null ? null : row.ownerName(),
                    row == null ? null : row.regNo(),
                    row == null ? "NONE" : "REGISTERED",
                    row == null ? null : row.registeredAt(),
                    row == null ? new Timestamp(System.currentTimeMillis()) : null);
            jdbc.update("INSERT INTO collateral_registration_history "
                            + "(vin, event_type, reg_no, owner_name, status, source, note, occurred_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'REGISTRATION_BATCH', ?, now())",
                    vin,
                    row == null ? "DISCHARGE" : "REGISTER",
                    row == null ? null : row.regNo(),
                    row == null ? null : row.ownerName(),
                    row == null ? "NONE" : "REGISTERED",
                    "V10 批次挂接/摘除后按 ADR-Q27 同步投影");
        } catch (RuntimeException ex) {
            // 投影同步失败不影响 V10 权威写入；门禁判定只读 V10，不受派生数据影响。
            // 用 stderr 而非吞异常，便于运维发现投影滞后。
            System.err.println("[ADR-Q27] V18 投影同步失败（不影响权威源与门禁）: vin=" + vin
                    + ", cause=" + ex.getMessage());
        }
    }

    /** 投影同步用的临时行：从 V10 有效登记批次读出。 */
    private record ProjectionRow(String regNo, String ownerName, Timestamp registeredAt) {
    }

    private static Timestamp ts(LocalDateTime t) {
        return t == null ? null : Timestamp.valueOf(t);
    }
}
