package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.InsurancePolicy;
import com.company.floorplan.domain.repository.InsurancePolicyRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 车辆保险单仓储实现。
 */
@Repository
public class JdbcInsurancePolicyRepository implements InsurancePolicyRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<InsurancePolicy> MAPPER = (rs, i) -> {
        InsurancePolicy p = new InsurancePolicy();
        p.setId(rs.getLong("id"));
        p.setPolicyNo(rs.getString("policy_no"));
        p.setVin(rs.getString("vin"));
        p.setInsurer(rs.getString("insurer"));
        p.setInsuredAmount(rs.getLong("insured_amount"));
        p.setStartDate(toDate(rs.getDate("start_date")));
        p.setEndDate(toDate(rs.getDate("end_date")));
        p.setStatus(rs.getString("status"));
        p.setClaimedAmount(rs.getLong("claimed_amount"));
        return p;
    };

    private static LocalDate toDate(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    public JdbcInsurancePolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public InsurancePolicy save(InsurancePolicy p) {
        jdbc.update("INSERT INTO insurance_policies (policy_no, vin, insurer, insured_amount, start_date, end_date, "
                        + "status, claimed_amount) VALUES (?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT (policy_no) DO UPDATE SET insurer=EXCLUDED.insurer, "
                        + "insured_amount=EXCLUDED.insured_amount, start_date=EXCLUDED.start_date, "
                        + "end_date=EXCLUDED.end_date, status=EXCLUDED.status, claimed_amount=EXCLUDED.claimed_amount",
                p.getPolicyNo(), p.getVin(), p.getInsurer(), p.getInsuredAmount(),
                p.getStartDate(), p.getEndDate(), p.getStatus() == null ? "ACTIVE" : p.getStatus(),
                p.getClaimedAmount());
        return p;
    }

    @Override
    public Optional<InsurancePolicy> findByPolicyNo(String policyNo) {
        return jdbc.query("SELECT * FROM insurance_policies WHERE policy_no = ?", MAPPER, policyNo)
                .stream().findFirst();
    }

    @Override
    public Optional<InsurancePolicy> findActiveByVin(String vin) {
        return jdbc.query("SELECT * FROM insurance_policies WHERE vin = ? AND status = 'ACTIVE' ORDER BY id DESC LIMIT 1",
                        MAPPER, vin).stream().findFirst();
    }
}
