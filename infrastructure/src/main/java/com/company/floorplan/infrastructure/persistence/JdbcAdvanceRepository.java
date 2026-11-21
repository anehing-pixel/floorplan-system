package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.Advance;
import com.company.floorplan.domain.model.AdvanceStatus;
import com.company.floorplan.domain.repository.AdvanceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 垫款记录仓储实现。
 *
 * <p><b>幂等设计（双层）</b>：
 * <ul>
 *   <li>数据库层：{@code idempotency_key} 唯一索引——
 *       即便应用层判断失效，重复插入也会被数据库拒绝，杜绝重复垫款；</li>
 *   <li>应用层：{@link #findByIdempotencyKey(String)} 先查首次结果直接返回。</li>
 * </ul>
 *
 * <p><b>对账用途</b>：{@code payment_ref} 保存外部支付流水号，
 * 用于与银行汇出明细逐笔对账（跨系统一致性无法靠事务保证，只能靠对账）。</p>
 */
@Repository
public class JdbcAdvanceRepository implements AdvanceRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Advance> MAPPER = (rs, i) -> {
        Advance a = new Advance();
        a.setId(rs.getLong("id"));
        a.setAdvanceNo(rs.getString("advance_no"));
        a.setVin(rs.getString("vin"));
        a.setCreditLineId(rs.getLong("credit_line_id"));
        a.setAmount(rs.getLong("amount"));
        Timestamp paidAt = rs.getTimestamp("paid_at");
        a.setPaidAt(paidAt == null ? null : paidAt.toLocalDateTime());
        a.setStatus(rs.getString("status") == null ? AdvanceStatus.PENDING : AdvanceStatus.valueOf(rs.getString("status")));
        a.setPaymentRef(rs.getString("payment_ref"));
        a.setIdempotencyKey(rs.getString("idempotency_key"));
        // 三段责任链（V8 新增列）：申请人 / 复核人 / 支付人。
        // 迁移由 Flyway 保证先于应用启动执行，故列必然存在，直接读即可。
        a.setApplicant(rs.getString("applicant"));
        a.setReviewer(rs.getString("reviewer"));
        a.setDisburser(rs.getString("disburser"));
        Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
        a.setReviewedAt(reviewedAt == null ? null : reviewedAt.toLocalDateTime());
        // 终结痕迹（V14 新增列）：进入 VOIDED / REDEEMED / REVERSED 时回填。
        Timestamp closedAt = rs.getTimestamp("closed_at");
        a.setClosedAt(closedAt == null ? null : closedAt.toLocalDateTime());
        a.setClosedReason(rs.getString("closed_reason"));
        return a;
    };

    public JdbcAdvanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Advance> findByIdempotencyKey(String key) {
        return jdbc.query("SELECT * FROM advances WHERE idempotency_key = ?", MAPPER, key).stream().findFirst();
    }

    @Override
    public Optional<Advance> findByVin(String vin) {
        return jdbc.query("SELECT * FROM advances WHERE vin = ? ORDER BY id DESC LIMIT 1", MAPPER, vin).stream().findFirst();
    }

    @Override
    public Optional<Advance> findActiveByVin(String vin) {
        // 只取"钱在路上"的指令：FAILED（从未占用）/ VOIDED（已作废）/ REDEEMED（已清偿）
        // 都不占名额，否则一台车历史上有过失败或还清记录就永远申请不了垫款。
        return jdbc.query("SELECT * FROM advances WHERE vin = ? AND status IN ('PENDING','PROCESSING') "
                + "ORDER BY id DESC LIMIT 1", MAPPER, vin).stream().findFirst();
    }

    @Override
    public Optional<Advance> findOccupyingByVin(String vin) {
        // 与 AdvanceStatus.occupiesVehicle() 及部分唯一索引 uk_adv_vin_active 保持一致：
        // 只有 PENDING / PROCESSING / PAID 三者仍占该 VIN 的唯一垫款名额。
        return jdbc.query("SELECT * FROM advances WHERE vin = ? AND status IN ('PENDING','PROCESSING','PAID') "
                + "ORDER BY id DESC LIMIT 1", MAPPER, vin).stream().findFirst();
    }

    @Override
    public Optional<Advance> findById(Long id) {
        return jdbc.query("SELECT * FROM advances WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<Advance> findByAdvanceNo(String advanceNo) {
        return jdbc.query("SELECT * FROM advances WHERE advance_no = ?", MAPPER, advanceNo).stream().findFirst();
    }

    @Override
    public Advance save(Advance advance) {
        if (advance.getId() == null) {
            if (advance.getAdvanceNo() == null) {
                advance.setAdvanceNo("ADV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            }
            Long id = jdbc.queryForObject(
                    "INSERT INTO advances (advance_no, vin, credit_line_id, amount, paid_at, status, payment_ref, "
                            + "idempotency_key, purchase_order_id, payee_supplier_id, payee_supplier_type, "
                            + "payee_account_snapshot, applicant, reviewer, reviewed_at, disburser, "
                            + "closed_at, closed_reason) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                    Long.class,
                    advance.getAdvanceNo(), advance.getVin(), advance.getCreditLineId(),
                    advance.getAmount(),
                    advance.getPaidAt() == null ? null : Timestamp.valueOf(advance.getPaidAt()),
                    advance.getStatus().name(), advance.getPaymentRef(), advance.getIdempotencyKey(),
                    advance.getPurchaseOrderId(), advance.getPayeeSupplierId(),
                    advance.getPayeeSupplierType(), advance.getPayeeAccountSnapshot(),
                    advance.getApplicant(), advance.getReviewer(),
                    advance.getReviewedAt() == null ? null : Timestamp.valueOf(advance.getReviewedAt()),
                    advance.getDisburser(),
                    advance.getClosedAt() == null ? null : Timestamp.valueOf(advance.getClosedAt()),
                    advance.getClosedReason());
            advance.setId(id);
        } else {
            jdbc.update("UPDATE advances SET status=?, payment_ref=?, paid_at=?, applicant=?, reviewer=?, "
                            + "reviewed_at=?, disburser=?, closed_at=?, closed_reason=? WHERE id=?",
                    advance.getStatus().name(), advance.getPaymentRef(),
                    advance.getPaidAt() == null ? null : Timestamp.valueOf(advance.getPaidAt()),
                    advance.getApplicant(), advance.getReviewer(),
                    advance.getReviewedAt() == null ? null : Timestamp.valueOf(advance.getReviewedAt()),
                    advance.getDisburser(),
                    advance.getClosedAt() == null ? null : Timestamp.valueOf(advance.getClosedAt()),
                    advance.getClosedReason(),
                    advance.getId());
        }
        return advance;
    }

    /**
     * 仅更新复核痕迹（不触碰状态与其他字段）。
     *
     * <p>独立方法而非复用 {@code save}：复核是一个<b>独立环节</b>，
     * 与状态机推进（申请落 PENDING、支付推 PROCESSING）不是一回事。
     * 复用 save 会把"复核"和"状态变更"混成一次写，将来加审批流时会互相覆盖。</p>
     */
    @Override
    public void saveReview(Long id, String reviewer, LocalDateTime reviewedAt) {
        jdbc.update("UPDATE advances SET reviewer=?, reviewed_at=? WHERE id=?",
                reviewer, Timestamp.valueOf(reviewedAt), id);
    }

    /**
     * 仅更新支付人痕迹（不触碰状态）。
     *
     * <p>同上：支付动作本身由 {@code submitAdvance} 改状态，
     * 这里只补记"谁付的"，两件事分开写，审计链更清晰。</p>
     */
    @Override
    public void saveDisburser(Long id, String disburser) {
        jdbc.update("UPDATE advances SET disburser=? WHERE id=?", disburser, id);
    }
}
