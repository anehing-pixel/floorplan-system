package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.FinancingLienCheck;
import com.company.floorplan.domain.repository.FinancingLienCheckRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 权属 / 抵押 / 查封核对记录仓储实现。
 *
 * <p><b>作用</b>：建账前重复融资拦截（P0-③）查此表，判断该车是否在他处存在权属负担。
 * 无接口时由人工/厂家门户核对后录入（{@code check_source=MANUAL/OEM_PORTAL}）。</p>
 */
@Repository
public class JdbcFinancingLienCheckRepository implements FinancingLienCheckRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<FinancingLienCheck> MAPPER = (rs, i) -> {
        FinancingLienCheck c = new FinancingLienCheck();
        c.setId(rs.getLong("id"));
        c.setVin(rs.getString("vin"));
        c.setCheckSource(rs.getString("check_source"));
        c.setEncumbered(rs.getBoolean("encumbered"));
        c.setCheckedAt(toLdt(rs.getTimestamp("checked_at")));
        c.setNote(rs.getString("note"));
        c.setOperator(rs.getString("operator"));
        return c;
    };

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    public JdbcFinancingLienCheckRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public FinancingLienCheck save(FinancingLienCheck c) {
        jdbc.update("INSERT INTO financing_lien_checks (vin, check_source, encumbered, checked_at, note, operator) "
                        + "VALUES (?,?,?,?,?,?)",
                c.getVin(), c.getCheckSource(), c.isEncumbered(),
                c.getCheckedAt() == null ? LocalDateTime.now() : c.getCheckedAt(),
                c.getNote(), c.getOperator());
        return c;
    }

    @Override
    public Optional<FinancingLienCheck> findLatestByVin(String vin) {
        return jdbc.query("SELECT * FROM financing_lien_checks WHERE vin = ? ORDER BY checked_at DESC LIMIT 1",
                        MAPPER, vin).stream().findFirst();
    }
}
