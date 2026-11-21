package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.ImportBatch;
import com.company.floorplan.domain.model.ImportRowError;
import com.company.floorplan.domain.model.IngestChannel;
import com.company.floorplan.domain.repository.ImportBatchRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 导入批次仓储实现（基础设施层）。
 */
@Repository
public class JdbcImportBatchRepository implements ImportBatchRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<ImportBatch> MAPPER = (rs, i) -> {
        ImportBatch b = new ImportBatch();
        b.setId(rs.getLong("id"));
        String channel = rs.getString("channel");
        b.setChannel(channel == null ? null : IngestChannel.valueOf(channel));
        b.setFilename(rs.getString("filename"));
        b.setFileHash(rs.getString("file_hash"));
        b.setTotalRows(rs.getInt("total_rows"));
        b.setAcceptedRows(rs.getInt("accepted_rows"));
        b.setRejectedRows(rs.getInt("rejected_rows"));
        b.setTotalAmount(rs.getLong("total_amount"));
        b.setStatus(rs.getString("status"));
        b.setOperator(rs.getString("operator"));
        b.setCreatedAt(rs.getTimestamp("created_at") == null ? null
                : rs.getTimestamp("created_at").toLocalDateTime());
        b.setCommittedAt(rs.getTimestamp("committed_at") == null ? null
                : rs.getTimestamp("committed_at").toLocalDateTime());
        return b;
    };

    private static final RowMapper<ImportRowError> ERROR_MAPPER = (rs, i) -> {
        ImportRowError e = new ImportRowError();
        e.setId(rs.getLong("id"));
        e.setBatchId(rs.getLong("batch_id"));
        e.setRowNo(rs.getInt("row_no"));
        e.setFieldName(rs.getString("field_name"));
        e.setReason(rs.getString("reason"));
        e.setRawValue(rs.getString("raw_value"));
        return e;
    };

    public JdbcImportBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Long create(ImportBatch b) {
        Long id = jdbc.queryForObject(
                "INSERT INTO import_batches (channel, filename, file_hash, total_rows, accepted_rows, "
                        + "rejected_rows, total_amount, status, operator) VALUES (?,?,?,?,?,?,?,?,?) RETURNING id",
                Long.class,
                b.getChannel() == null ? null : b.getChannel().name(),
                b.getFilename(), b.getFileHash(), b.getTotalRows(), b.getAcceptedRows(),
                b.getRejectedRows(), b.getTotalAmount(), b.getStatus(), b.getOperator());
        b.setId(id);
        return id;
    }

    @Override
    public void markStatus(Long batchId, String status, boolean committed) {
        if (committed) {
            jdbc.update("UPDATE import_batches SET status=?, committed_at=now() WHERE id=?", status, batchId);
        } else {
            jdbc.update("UPDATE import_batches SET status=? WHERE id=?", status, batchId);
        }
    }

    @Override
    public void saveRowErrors(Long batchId, List<ImportRowError> errors) {
        if (errors == null || errors.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO import_row_errors (batch_id, row_no, field_name, reason, raw_value) "
                        + "VALUES (?,?,?,?,?)",
                errors.stream().map(e -> new Object[]{batchId, e.getRowNo(), e.getFieldName(),
                        e.getReason(), e.getRawValue()}).toList());
    }

    @Override
    public List<ImportBatch> findAll() {
        return jdbc.query("SELECT * FROM import_batches ORDER BY id DESC", MAPPER);
    }

    @Override
    public List<ImportRowError> findRowErrors(Long batchId) {
        return jdbc.query("SELECT * FROM import_row_errors WHERE batch_id = ? ORDER BY row_no, id",
                ERROR_MAPPER, batchId);
    }
}
