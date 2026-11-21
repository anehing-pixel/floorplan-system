package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.CreditLine;
import com.company.floorplan.domain.repository.CreditLineRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 额度仓储实现（基础设施层）。
 *
 * <p><b>核心职责</b>：提供<b>行锁</b>读取能力 {@link #findByIdForUpdate(Long)}，
 * 用于额度占用 / 释放 / 冻结。额度是本系统并发风险最高的地方：
 * 多个垫款请求同时到达时，若不加锁就会出现"额度超额"——
 * 金融系统绝不能接受，因此这里的实现方式是有意为之，不是可选项。</p>
 *
 * <p><b>与领域层的分工</b>：不变量（占用+冻结 ≤ 核定）由 {@link CreditLine} 守护，
 * 本类只负责"以正确的方式加载与保存"，不重复实现业务规则。</p>
 */
@Repository
public class JdbcCreditLineRepository implements CreditLineRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<CreditLine> MAPPER = (rs, i) -> {
        CreditLine c = new CreditLine();
        c.setId(rs.getLong("id"));
        c.setDealerId(rs.getLong("dealer_id"));
        c.setApprovedLimit(rs.getLong("approved_limit"));
        c.setUsedAmount(rs.getLong("used_amount"));
        c.setFrozenAmount(rs.getLong("frozen_amount"));
        c.setStatus(rs.getString("status"));
        c.setVersion(rs.getInt("version"));
        return c;
    };

    public JdbcCreditLineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CreditLine> findById(Long id) {
        return jdbc.query("SELECT * FROM credit_lines WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<CreditLine> findByIdForUpdate(Long id) {
        // 行锁：必须在事务内调用，禁止无锁的读-改-写
        return jdbc.query("SELECT * FROM credit_lines WHERE id = ? FOR UPDATE", MAPPER, id).stream().findFirst();
    }

    @Override
    public List<CreditLine> findByDealerId(Long dealerId) {
        return jdbc.query("SELECT * FROM credit_lines WHERE dealer_id = ? ORDER BY id", MAPPER, dealerId);
    }

    @Override
    public int countByDealerId(Long dealerId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM credit_lines WHERE dealer_id = ?", Integer.class, dealerId);
        return count == null ? 0 : count;
    }

    @Override
    public List<CreditLine> findActive() {
        return jdbc.query("SELECT * FROM credit_lines WHERE status = 'ACTIVE' ORDER BY id", MAPPER);
    }

    @Override
    public CreditLine save(CreditLine creditLine) {
        if (creditLine.getId() == null) {
            Long id = jdbc.queryForObject(
                    "INSERT INTO credit_lines (dealer_id, approved_limit, used_amount, frozen_amount, status) VALUES (?,?,?,?,?) RETURNING id",
                    Long.class,
                    creditLine.getDealerId(), creditLine.getApprovedLimit(),
                    creditLine.getUsedAmount(), creditLine.getFrozenAmount(),
                    creditLine.getStatus() == null ? "ACTIVE" : creditLine.getStatus());
            creditLine.setId(id);
        } else {
            jdbc.update("UPDATE credit_lines SET approved_limit=?, used_amount=?, frozen_amount=?, status=?, version=version+1, updated_at=now() WHERE id=?",
                    creditLine.getApprovedLimit(), creditLine.getUsedAmount(),
                    creditLine.getFrozenAmount(), creditLine.getStatus(), creditLine.getId());
        }
        return creditLine;
    }
}
