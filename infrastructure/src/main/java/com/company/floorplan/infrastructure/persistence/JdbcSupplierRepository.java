package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.GuaranteeAbility;
import com.company.floorplan.domain.model.Supplier;
import com.company.floorplan.domain.model.SupplierType;
import com.company.floorplan.domain.repository.SupplierRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 上游主数据仓储实现（基础设施层）。
 *
 * <p>状态列的枚举转换放在这一层完成——领域层不该知道数据库用字符串存枚举。</p>
 */
@Repository
public class JdbcSupplierRepository implements SupplierRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Supplier> MAPPER = (rs, i) -> {
        Supplier s = new Supplier();
        s.setId(rs.getLong("id"));
        s.setSupplierCode(rs.getString("supplier_code"));
        s.setName(rs.getString("name"));
        String type = rs.getString("supplier_type");
        s.setSupplierType(type == null ? null : SupplierType.valueOf(type));
        String status = rs.getString("status");
        s.setStatus(status == null ? Supplier.Status.ACTIVE : Supplier.Status.valueOf(status));
        s.setRiskGrade(rs.getString("risk_grade"));
        String guarantee = rs.getString("guarantee_ability");
        s.setGuaranteeAbility(guarantee == null ? null : GuaranteeAbility.valueOf(guarantee));
        s.setContactPerson(rs.getString("contact_person"));
        s.setContactPhone(rs.getString("contact_phone"));
        s.setRemark(rs.getString("remark"));
        s.setCreatedAt(rs.getTimestamp("created_at") == null ? null
                : rs.getTimestamp("created_at").toLocalDateTime());
        s.setUpdatedAt(rs.getTimestamp("updated_at") == null ? null
                : rs.getTimestamp("updated_at").toLocalDateTime());
        return s;
    };

    public JdbcSupplierRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Supplier> findById(Long id) {
        return jdbc.query("SELECT * FROM suppliers WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<Supplier> findByCode(String supplierCode) {
        return jdbc.query("SELECT * FROM suppliers WHERE supplier_code = ?", MAPPER, supplierCode)
                .stream().findFirst();
    }

    @Override
    public List<Supplier> findAll() {
        return jdbc.query("SELECT * FROM suppliers ORDER BY id", MAPPER);
    }

    @Override
    public List<Supplier> findByType(String supplierType) {
        return jdbc.query("SELECT * FROM suppliers WHERE supplier_type = ? ORDER BY id", MAPPER, supplierType);
    }

    @Override
    public Supplier save(Supplier s) {
        if (s.getId() == null) {
            Long id = jdbc.queryForObject(
                    "INSERT INTO suppliers (supplier_code, name, supplier_type, status, risk_grade, "
                            + "guarantee_ability, contact_person, contact_phone, remark) "
                            + "VALUES (?,?,?,?,?,?,?,?,?) RETURNING id",
                    Long.class,
                    s.getSupplierCode(), s.getName(),
                    s.getSupplierType() == null ? null : s.getSupplierType().name(),
                    s.getStatus() == null ? Supplier.Status.ACTIVE.name() : s.getStatus().name(),
                    s.getRiskGrade(),
                    s.getGuaranteeAbility() == null ? null : s.getGuaranteeAbility().name(),
                    s.getContactPerson(), s.getContactPhone(), s.getRemark());
            s.setId(id);
        } else {
            jdbc.update("UPDATE suppliers SET supplier_code=?, name=?, supplier_type=?, status=?, risk_grade=?, "
                            + "guarantee_ability=?, contact_person=?, contact_phone=?, remark=?, updated_at=now() "
                            + "WHERE id=?",
                    s.getSupplierCode(), s.getName(),
                    s.getSupplierType() == null ? null : s.getSupplierType().name(),
                    s.getStatus() == null ? Supplier.Status.ACTIVE.name() : s.getStatus().name(),
                    s.getRiskGrade(),
                    s.getGuaranteeAbility() == null ? null : s.getGuaranteeAbility().name(),
                    s.getContactPerson(), s.getContactPhone(), s.getRemark(), s.getId());
        }
        return s;
    }

    @Override
    public int updateStatus(Long id, String status) {
        return jdbc.update("UPDATE suppliers SET status=?, updated_at=now() WHERE id=?", status, id);
    }
}
