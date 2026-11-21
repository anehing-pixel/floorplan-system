package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.ExtensionRequest;
import com.company.floorplan.domain.repository.ExtensionRequestRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 展期续贷请求仓储实现。
 */
@Repository
public class JdbcExtensionRequestRepository implements ExtensionRequestRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<ExtensionRequest> MAPPER = (rs, i) -> {
        ExtensionRequest e = new ExtensionRequest();
        e.setId(rs.getLong("id"));
        e.setRequestNo(rs.getString("request_no"));
        e.setVin(rs.getString("vin"));
        Object extDays = rs.getObject("ext_days");
        e.setExtDays(extDays == null ? 0 : ((Number) extDays).intValue());
        e.setReason(rs.getString("reason"));
        e.setStatus(rs.getString("status"));
        e.setOriginalDue(toDate(rs.getDate("original_due")));
        e.setNewDue(toDate(rs.getDate("new_due")));
        return e;
    };

    private static LocalDate toDate(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    public JdbcExtensionRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ExtensionRequest save(ExtensionRequest e) {
        jdbc.update("INSERT INTO extension_requests (request_no, vin, ext_days, reason, status, original_due, new_due) "
                        + "VALUES (?,?,?,?,?,?,?) "
                        + "ON CONFLICT (request_no) DO UPDATE SET reason=EXCLUDED.reason, status=EXCLUDED.status, "
                        + "new_due=EXCLUDED.new_due",
                e.getRequestNo(), e.getVin(), e.getExtDays(), e.getReason(),
                e.getStatus() == null ? "APPROVED" : e.getStatus(), e.getOriginalDue(), e.getNewDue());
        return e;
    }

    @Override
    public Optional<ExtensionRequest> findLatestByVin(String vin) {
        return jdbc.query("SELECT * FROM extension_requests WHERE vin = ? ORDER BY created_at DESC LIMIT 1",
                        MAPPER, vin).stream().findFirst();
    }
}
