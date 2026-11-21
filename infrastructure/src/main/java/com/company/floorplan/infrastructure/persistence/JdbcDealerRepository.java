package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.Dealer;
import com.company.floorplan.domain.model.DealerStatus;
import com.company.floorplan.domain.repository.DealerRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 经销商仓储实现（基础设施层）。
 *
 * <p>使用 JdbcTemplate 直接映射领域对象：{@code MAPPER} 负责把一行记录
 * 转成 {@link Dealer}。经销商是简单实体，不涉及复杂账务，
 * 因此无需引入 ORM 的懒加载等机制。</p>
 *
 * <p><b>幂等/唯一性</b>：{@code dealer_code} 在数据库层有唯一约束，
 * 重复创建会由应用层先查后拦（抛 CONFLICT_001）。</p>
 *
 * <p><b>状态列的映射</b>：库里是 {@code VARCHAR}，领域侧是 {@link DealerStatus} 枚举。
 * 转换放在这一层完成——领域层不该知道数据库用字符串存枚举。</p>
 */
@Repository
public class JdbcDealerRepository implements DealerRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Dealer> MAPPER = (rs, i) -> {
        Dealer d = new Dealer();
        d.setId(rs.getLong("id"));
        d.setDealerCode(rs.getString("dealer_code"));
        d.setName(rs.getString("name"));
        String status = rs.getString("status");
        d.setStatus(status == null ? DealerStatus.ACTIVE : DealerStatus.valueOf(status));
        d.setRiskGrade(rs.getString("risk_grade"));
        Object years = rs.getObject("business_years");
        d.setBusinessYears(years == null ? null : ((Number) years).intValue());
        d.setContactPerson(rs.getString("contact_person"));
        d.setContactPhone(rs.getString("contact_phone"));
        d.setAddress(rs.getString("address"));
        d.setRemark(rs.getString("remark"));
        return d;
    };

    public JdbcDealerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Dealer> findById(Long id) {
        return jdbc.query("SELECT * FROM dealers WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<Dealer> findByCode(String dealerCode) {
        return jdbc.query("SELECT * FROM dealers WHERE dealer_code = ?", MAPPER, dealerCode).stream().findFirst();
    }

    @Override
    public List<Dealer> findAll() {
        return jdbc.query("SELECT * FROM dealers ORDER BY id", MAPPER);
    }

    @Override
    public Dealer save(Dealer dealer) {
        if (dealer.getId() == null) {
            Long id = jdbc.queryForObject(
                    "INSERT INTO dealers (dealer_code, name, status, risk_grade, business_years, "
                            + "contact_person, contact_phone, address, remark) VALUES (?,?,?,?,?,?,?,?,?) RETURNING id",
                    Long.class,
                    dealer.getDealerCode(), dealer.getName(),
                    dealer.getStatus() == null ? DealerStatus.ACTIVE.name() : dealer.getStatus().name(),
                    dealer.getRiskGrade(), dealer.getBusinessYears(),
                    dealer.getContactPerson(), dealer.getContactPhone(),
                    dealer.getAddress(), dealer.getRemark());
            dealer.setId(id);
        } else {
            jdbc.update("UPDATE dealers SET dealer_code=?, name=?, status=?, risk_grade=?, business_years=?, "
                            + "contact_person=?, contact_phone=?, address=?, remark=?, updated_at=now() WHERE id=?",
                    dealer.getDealerCode(), dealer.getName(),
                    dealer.getStatus() == null ? DealerStatus.ACTIVE.name() : dealer.getStatus().name(),
                    dealer.getRiskGrade(), dealer.getBusinessYears(),
                    dealer.getContactPerson(), dealer.getContactPhone(),
                    dealer.getAddress(), dealer.getRemark(), dealer.getId());
        }
        return dealer;
    }

    @Override
    public int deleteById(Long id) {
        return jdbc.update("DELETE FROM dealers WHERE id = ?", id);
    }
}
