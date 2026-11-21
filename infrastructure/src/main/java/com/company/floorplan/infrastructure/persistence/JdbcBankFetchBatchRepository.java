package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.BankFetchBatch;
import com.company.floorplan.domain.repository.BankFetchBatchRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 采集批次仓储实现。
 *
 * <p>用 {@code ON CONFLICT (batch_no) DO UPDATE} 做 upsert —— 这里与流水表的
 * {@code DO NOTHING} 恰好相反，原因很直接：</p>
 * <ul>
 *   <li>流水是<b>事实</b>：重复就该忽略，改事实等于篡改证据；</li>
 *   <li>批次是<b>过程记录</b>：同一批次先 RUNNING、后 SUCCESS，本来就要更新。</li>
 * </ul>
 * <p>同一个 {@code ON CONFLICT}，两种语义，取决于对象是"事实"还是"过程"。
 * 想清楚这一点，就不会在流水表上留一个 update 方法。</p>
 */
@Repository
public class JdbcBankFetchBatchRepository implements BankFetchBatchRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<BankFetchBatch> MAPPER = (rs, i) -> {
        BankFetchBatch b = new BankFetchBatch();
        b.setId(rs.getLong("id"));
        b.setBatchNo(rs.getString("batch_no"));
        b.setAccountRef(rs.getString("account_ref"));
        b.setValueDateFrom(toLocalDate(rs.getDate("value_date_from")));
        b.setValueDateTo(toLocalDate(rs.getDate("value_date_to")));
        b.setSource(rs.getString("source"));
        b.setStatus(rs.getString("status"));
        b.setFetchedCount(rs.getInt("fetched_count"));
        b.setInsertedCount(rs.getInt("inserted_count"));
        b.setDuplicatedCount(rs.getInt("duplicated_count"));
        b.setFileFingerprint(rs.getString("file_fingerprint"));
        b.setErrorDetail(rs.getString("error_detail"));
        b.setStartedAt(toLocalDateTime(rs.getTimestamp("started_at")));
        b.setFinishedAt(toLocalDateTime(rs.getTimestamp("finished_at")));
        return b;
    };

    private static LocalDate toLocalDate(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    public JdbcBankFetchBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BankFetchBatch save(BankFetchBatch b) {
        jdbc.update("INSERT INTO bank_fetch_batches "
                        + "(batch_no, account_ref, value_date_from, value_date_to, source, status, "
                        + " fetched_count, inserted_count, duplicated_count, file_fingerprint, "
                        + " error_detail, started_at, finished_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT (batch_no) DO UPDATE SET status = EXCLUDED.status, "
                        + " fetched_count = EXCLUDED.fetched_count, "
                        + " inserted_count = EXCLUDED.inserted_count, "
                        + " duplicated_count = EXCLUDED.duplicated_count, "
                        + " file_fingerprint = COALESCE(EXCLUDED.file_fingerprint, bank_fetch_batches.file_fingerprint), "
                        + " error_detail = EXCLUDED.error_detail, finished_at = EXCLUDED.finished_at",
                b.getBatchNo(), b.getAccountRef(), b.getValueDateFrom(), b.getValueDateTo(),
                b.getSource(), b.getStatus(), b.getFetchedCount(), b.getInsertedCount(),
                b.getDuplicatedCount(), b.getFileFingerprint(), b.getErrorDetail(),
                b.getStartedAt(), b.getFinishedAt());
        return b;
    }

    @Override
    public Optional<BankFetchBatch> findByBatchNo(String batchNo) {
        return jdbc.query("SELECT * FROM bank_fetch_batches WHERE batch_no = ?", MAPPER, batchNo)
                .stream().findFirst();
    }

    @Override
    public List<BankFetchBatch> findRecent(String accountRef, int limit) {
        return jdbc.query("SELECT * FROM bank_fetch_batches WHERE account_ref = ? "
                + "ORDER BY started_at DESC LIMIT ?", MAPPER, accountRef, limit);
    }

    @Override
    public boolean hasRunning(String accountRef) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM bank_fetch_batches "
                + "WHERE account_ref = ? AND status = 'RUNNING'", Long.class, accountRef);
        return n != null && n > 0;
    }
}
