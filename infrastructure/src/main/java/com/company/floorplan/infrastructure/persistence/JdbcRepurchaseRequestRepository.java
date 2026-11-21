package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.RepurchaseRequest;
import com.company.floorplan.domain.repository.RepurchaseRequestRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 厂家回购请求仓储实现。
 */
@Repository
public class JdbcRepurchaseRequestRepository implements RepurchaseRequestRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<RepurchaseRequest> MAPPER = (rs, i) -> {
        RepurchaseRequest r = new RepurchaseRequest();
        r.setId(rs.getLong("id"));
        r.setRequestNo(rs.getString("request_no"));
        r.setVin(rs.getString("vin"));
        Object dealerId = rs.getObject("dealer_id");
        r.setDealerId(dealerId == null ? null : ((Number) dealerId).longValue());
        Object creditLineId = rs.getObject("credit_line_id");
        r.setCreditLineId(creditLineId == null ? null : ((Number) creditLineId).longValue());
        r.setReason(rs.getString("reason"));
        r.setStatus(rs.getString("status"));
        r.setRepurchaseAmount(rs.getLong("repurchase_amount"));
        r.setFulfilledAt(toLdt(rs.getTimestamp("fulfilled_at")));
        return r;
    };

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    public JdbcRepurchaseRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RepurchaseRequest save(RepurchaseRequest r) {
        jdbc.update("INSERT INTO repurchase_requests (request_no, vin, dealer_id, credit_line_id, reason, status, "
                        + "repurchase_amount, fulfilled_at) VALUES (?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT (request_no) DO UPDATE SET reason=EXCLUDED.reason, status=EXCLUDED.status, "
                        + "repurchase_amount=EXCLUDED.repurchase_amount, fulfilled_at=EXCLUDED.fulfilled_at",
                r.getRequestNo(), r.getVin(), r.getDealerId(), r.getCreditLineId(), r.getReason(),
                r.getStatus() == null ? "REQUESTED" : r.getStatus(), r.getRepurchaseAmount(), r.getFulfilledAt());
        return r;
    }

    @Override
    public Optional<RepurchaseRequest> findByRequestNo(String requestNo) {
        return jdbc.query("SELECT * FROM repurchase_requests WHERE request_no = ?", MAPPER, requestNo)
                .stream().findFirst();
    }

    @Override
    public Optional<RepurchaseRequest> findLatestByVin(String vin) {
        return jdbc.query("SELECT * FROM repurchase_requests WHERE vin = ? ORDER BY created_at DESC LIMIT 1",
                        MAPPER, vin).stream().findFirst();
    }
}
