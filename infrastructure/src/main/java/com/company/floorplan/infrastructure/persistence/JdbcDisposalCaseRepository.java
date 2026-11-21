package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.DisposalCase;
import com.company.floorplan.domain.model.DisposalStatus;
import com.company.floorplan.domain.repository.DisposalCaseRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 违约处置工单仓储实现（JdbcTemplate 手写 SQL）。
 *
 * <p><b>保存幂等</b>：按 {@code case_no} 唯一约束，INSERT 冲突则 UPDATE，
 * 保证重复上报（网络重试）不会产生重复工单。</p>
 */
@Repository
public class JdbcDisposalCaseRepository implements DisposalCaseRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<DisposalCase> MAPPER = (rs, i) -> {
        DisposalCase c = new DisposalCase();
        c.setId(rs.getLong("id"));
        c.setCaseNo(rs.getString("case_no"));
        c.setVin(rs.getString("vin"));
        Object dealerId = rs.getObject("dealer_id");
        c.setDealerId(dealerId == null ? null : ((Number) dealerId).longValue());
        Object creditLineId = rs.getObject("credit_line_id");
        c.setCreditLineId(creditLineId == null ? null : ((Number) creditLineId).longValue());
        c.setReason(rs.getString("reason"));
        c.setStatus(DisposalStatus.valueOf(rs.getString("status")));
        c.setRepossessedAt(toLdt(rs.getTimestamp("repossessed_at")));
        c.setProceeds(rs.getLong("proceeds"));
        c.setLossAmount(rs.getLong("loss_amount"));
        c.setOpenedAt(toLdt(rs.getTimestamp("opened_at")));
        c.setClosedAt(toLdt(rs.getTimestamp("closed_at")));
        return c;
    };

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    public JdbcDisposalCaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DisposalCase save(DisposalCase c) {
        jdbc.update("INSERT INTO disposal_cases (case_no, vin, dealer_id, credit_line_id, reason, status, "
                        + "repossessed_at, proceeds, loss_amount, opened_at, closed_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT (case_no) DO UPDATE SET dealer_id=EXCLUDED.dealer_id, "
                        + "credit_line_id=EXCLUDED.credit_line_id, reason=EXCLUDED.reason, status=EXCLUDED.status, "
                        + "repossessed_at=EXCLUDED.repossessed_at, proceeds=EXCLUDED.proceeds, "
                        + "loss_amount=EXCLUDED.loss_amount, closed_at=EXCLUDED.closed_at",
                c.getCaseNo(), c.getVin(), c.getDealerId(), c.getCreditLineId(), c.getReason(),
                c.getStatus() == null ? "OPEN" : c.getStatus().name(),
                c.getRepossessedAt(), c.getProceeds(), c.getLossAmount(),
                c.getOpenedAt(), c.getClosedAt());
        return c;
    }

    @Override
    public Optional<DisposalCase> findByCaseNo(String caseNo) {
        return jdbc.query("SELECT * FROM disposal_cases WHERE case_no = ?", MAPPER, caseNo).stream().findFirst();
    }

    @Override
    public Optional<DisposalCase> findLatestByVin(String vin) {
        return jdbc.query("SELECT * FROM disposal_cases WHERE vin = ? ORDER BY opened_at DESC LIMIT 1", MAPPER, vin)
                .stream().findFirst();
    }

    @Override
    public List<DisposalCase> findByStatus(DisposalStatus status) {
        return jdbc.query("SELECT * FROM disposal_cases WHERE status = ? ORDER BY case_no", MAPPER, status.name());
    }
}
