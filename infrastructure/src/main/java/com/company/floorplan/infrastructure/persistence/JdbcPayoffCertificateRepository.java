package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.PayoffCertificate;
import com.company.floorplan.domain.repository.PayoffCertificateRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 结清证明仓储实现。
 */
@Repository
public class JdbcPayoffCertificateRepository implements PayoffCertificateRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<PayoffCertificate> MAPPER = (rs, i) -> {
        PayoffCertificate c = new PayoffCertificate();
        c.setId(rs.getLong("id"));
        c.setCertNo(rs.getString("cert_no"));
        c.setVin(rs.getString("vin"));
        c.setPrincipal(rs.getLong("principal"));
        c.setInterest(rs.getLong("interest"));
        c.setTotalAmount(rs.getLong("total_amount"));
        c.setIssuedAt(toLdt(rs.getTimestamp("issued_at")));
        c.setOperator(rs.getString("operator"));
        return c;
    };

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    public JdbcPayoffCertificateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PayoffCertificate save(PayoffCertificate c) {
        jdbc.update("INSERT INTO payoff_certificates (cert_no, vin, principal, interest, total_amount, issued_at, operator) "
                        + "VALUES (?,?,?,?,?,?,?)",
                c.getCertNo(), c.getVin(), c.getPrincipal(), c.getInterest(), c.getTotalAmount(),
                c.getIssuedAt() == null ? LocalDateTime.now() : c.getIssuedAt(), c.getOperator());
        return c;
    }

    @Override
    public Optional<PayoffCertificate> findByVin(String vin) {
        return jdbc.query("SELECT * FROM payoff_certificates WHERE vin = ? ORDER BY issued_at DESC LIMIT 1",
                        MAPPER, vin).stream().findFirst();
    }
}
