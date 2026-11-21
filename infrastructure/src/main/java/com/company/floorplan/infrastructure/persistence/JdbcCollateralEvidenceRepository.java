package com.company.floorplan.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import com.company.floorplan.domain.model.CollateralEvidenceLink;
import com.company.floorplan.domain.repository.CollateralEvidenceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 担保物证据链仓储 JDBC 实现。
 */
@Repository
public class JdbcCollateralEvidenceRepository implements CollateralEvidenceRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<CollateralEvidenceLink> MAPPER = (rs, i) -> {
        CollateralEvidenceLink e = new CollateralEvidenceLink();
        e.setId(rs.getLong("id"));
        e.setLedgerId(rs.getLong("ledger_id"));
        e.setVin(rs.getString("vin"));
        e.setEvidenceType(rs.getString("evidence_type"));
        e.setRefType(rs.getString("ref_type"));
        e.setRefId(rs.getString("ref_id"));
        long attachmentId = rs.getLong("attachment_id");
        e.setAttachmentId(rs.getObject("attachment_id") == null ? null : attachmentId);
        Timestamp eventAt = rs.getTimestamp("event_at");
        e.setEventAt(eventAt == null ? null : eventAt.toLocalDateTime());
        e.setNote(rs.getString("note"));
        e.setRecordedBy(rs.getString("recorded_by"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        e.setCreatedAt(createdAt == null ? null : createdAt.toLocalDateTime());
        return e;
    };

    public JdbcCollateralEvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CollateralEvidenceLink> findByLedgerId(Long ledgerId) {
        return jdbc.query("SELECT * FROM collateral_evidence_links WHERE ledger_id = ? "
                + "ORDER BY event_at NULLS LAST, id", MAPPER, ledgerId);
    }

    @Override
    public List<CollateralEvidenceLink> findByVin(String vin) {
        return jdbc.query("SELECT * FROM collateral_evidence_links WHERE vin = ? "
                + "ORDER BY event_at NULLS LAST, id", MAPPER, vin);
    }

    @Override
    public CollateralEvidenceLink add(CollateralEvidenceLink e) {
        Long id = jdbc.queryForObject(
                "INSERT INTO collateral_evidence_links ("
                        + "ledger_id, vin, evidence_type, ref_type, ref_id, attachment_id, "
                        + "event_at, note, recorded_by) VALUES (?,?,?,?,?,?,?,?,?) RETURNING id",
                Long.class,
                e.getLedgerId(), e.getVin(), e.getEvidenceType(), e.getRefType(), e.getRefId(),
                e.getAttachmentId(), ts(e.getEventAt()), e.getNote(), e.getRecordedBy());
        e.setId(id);
        return e;
    }

    private static Timestamp ts(LocalDateTime t) {
        return t == null ? null : Timestamp.valueOf(t);
    }
}
