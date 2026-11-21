package com.company.floorplan.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.company.floorplan.domain.model.CollateralLedger;
import com.company.floorplan.domain.repository.CollateralLedgerRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 担保物台账仓储 JDBC 实现。
 *
 * <p><b>为什么台账按 VIN 做 upsert 而不是先查后判</b>：
 * 台账在多处被写入（放款确认建账、售出更新、还款更新、结清更新、盘点更新），
 * 若每个写入点都"先 findByVin 再决定 insert 还是 update"，
 * 逻辑会散落在五个地方且都有并发窗口。
 * 这里用数据库的唯一索引（{@code collateral_ledger.vin} UNIQUE）做仲裁：
 * {@code INSERT ... ON CONFLICT (vin) DO UPDATE}，
 * 让"同一台车并发被两个流程写到"变成数据库层面的确定行为。</p>
 */
@Repository
public class JdbcCollateralLedgerRepository implements CollateralLedgerRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<CollateralLedger> MAPPER = (rs, i) -> {
        CollateralLedger l = new CollateralLedger();
        l.setId(rs.getLong("id"));
        l.setVin(rs.getString("vin"));
        long dealerId = rs.getLong("dealer_id");
        l.setDealerId(rs.getObject("dealer_id") == null ? null : dealerId);
        l.setFinanced(rs.getBoolean("financed"));
        Timestamp financedAt = rs.getTimestamp("financed_at");
        l.setFinancedAt(financedAt == null ? null : financedAt.toLocalDateTime());
        l.setAdvanceNo(rs.getString("advance_no"));
        l.setSold(rs.getBoolean("sold"));
        Timestamp soldAt = rs.getTimestamp("sold_at");
        l.setSoldAt(soldAt == null ? null : soldAt.toLocalDateTime());
        l.setSoldFlaggedBy(rs.getString("sold_flagged_by"));
        l.setRepaid(rs.getBoolean("repaid"));
        Timestamp repaidAt = rs.getTimestamp("repaid_at");
        l.setRepaidAt(repaidAt == null ? null : repaidAt.toLocalDateTime());
        long repaidAmount = rs.getLong("repaid_amount");
        l.setRepaidAmount(rs.getObject("repaid_amount") == null ? null : repaidAmount);
        l.setReleased(rs.getBoolean("released"));
        Timestamp releasedAt = rs.getTimestamp("released_at");
        l.setReleasedAt(releasedAt == null ? null : releasedAt.toLocalDateTime());
        l.setReleaseReason(rs.getString("release_reason"));
        l.setSecurityType(rs.getString("security_type"));
        l.setSecurityStatus(rs.getString("security_status"));
        l.setRequiredAccountRef(rs.getString("required_account_ref"));
        l.setAccountChecked(rs.getBoolean("account_checked"));
        Timestamp accountCheckedAt = rs.getTimestamp("account_checked_at");
        l.setAccountCheckedAt(accountCheckedAt == null ? null : accountCheckedAt.toLocalDateTime());
        l.setAccountCheckedBy(rs.getString("account_checked_by"));
        java.sql.Date lastAudit = rs.getDate("last_audit_date");
        l.setLastAuditDate(lastAudit == null ? null : lastAudit.toLocalDate());
        l.setAuditMismatchCount(rs.getInt("audit_mismatch_count"));
        l.setLastAuditBy(rs.getString("last_audit_by"));
        l.setDisposalCaseNo(rs.getString("disposal_case_no"));
        Timestamp disposedAt = rs.getTimestamp("disposed_at");
        l.setDisposedAt(disposedAt == null ? null : disposedAt.toLocalDateTime());
        l.setRegistrationDue(rs.getBoolean("registration_due"));
        l.setRegistrationNote(rs.getString("registration_note"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        l.setCreatedAt(createdAt == null ? null : createdAt.toLocalDateTime());
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        l.setUpdatedAt(updatedAt == null ? null : updatedAt.toLocalDateTime());
        return l;
    };

    /** INSERT 的列清单（顺序与 VALUES 占位符严格对应）。 */
    private static final String INSERT_COLUMNS =
            "vin, dealer_id, financed, financed_at, advance_no, sold, sold_at, sold_flagged_by, "
            + "repaid, repaid_at, repaid_amount, released, released_at, release_reason, "
            + "security_type, security_status, required_account_ref, account_checked, "
            + "account_checked_at, account_checked_by, last_audit_date, audit_mismatch_count, "
            + "last_audit_by, disposal_case_no, disposed_at, registration_due, registration_note";

    /** INSERT 的 VALUES 占位符（个数须与 {@link #INSERT_COLUMNS} 一致）。 */
    private static final String INSERT_VALUES =
            "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?";

    /**
     * INSERT 列数必须与占位符数一致——不一致时每条建档都会失败，
     * 且报错信息（"未设定参数值 N"）完全不指向真正的原因。
     * 这里在类加载时自检，不一致就<b>直接抛异常让应用启动失败</b>，
     * 把问题暴露在部署那一刻，而不是等到第一条业务数据进来才炸。
     */
    static {
        int columnCount = INSERT_COLUMNS.split(",").length;
        int placeholderCount = (int) INSERT_VALUES.chars().filter(c -> c == '?').count();
        if (columnCount != placeholderCount) {
            throw new IllegalStateException(String.format(
                    "collateral_ledger INSERT 列数与占位符数不一致：列=%d 占位符=%d。"
                            + "请同步修改 INSERT_COLUMNS 与 INSERT_VALUES。",
                    columnCount, placeholderCount));
        }
    }

    public JdbcCollateralLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CollateralLedger> findByVin(String vin) {
        return jdbc.query("SELECT * FROM collateral_ledger WHERE vin = ?", MAPPER, vin).stream().findFirst();
    }

    @Override
    public List<CollateralLedger> findAll(Long dealerId) {
        if (dealerId == null) {
            return jdbc.query("SELECT * FROM collateral_ledger ORDER BY vin", MAPPER);
        }
        return jdbc.query("SELECT * FROM collateral_ledger WHERE dealer_id = ? ORDER BY vin", MAPPER, dealerId);
    }

    @Override
    public List<CollateralLedger> findSoldUnpaid() {
        return jdbc.query("SELECT * FROM collateral_ledger WHERE sold = TRUE AND repaid = FALSE ORDER BY sold_at", MAPPER);
    }

    @Override
    public List<CollateralLedger> findWithDueItems() {
        return jdbc.query("SELECT * FROM collateral_ledger WHERE registration_due = TRUE ORDER BY vin", MAPPER);
    }

    @Override
    public CollateralLedger save(CollateralLedger ledger) {
        // upsert：按 VIN 仲裁，并发写同一台车时后写覆盖先写（状态字段是"置真"语义，幂等）
        List<Object> args = new ArrayList<>();
        args.add(ledger.getVin());
        args.add(ledger.getDealerId());
        args.add(ledger.isFinanced());
        args.add(ts(ledger.getFinancedAt()));
        args.add(ledger.getAdvanceNo());
        args.add(ledger.isSold());
        args.add(ts(ledger.getSoldAt()));
        args.add(ledger.getSoldFlaggedBy());
        args.add(ledger.isRepaid());
        args.add(ts(ledger.getRepaidAt()));
        args.add(ledger.getRepaidAmount());
        args.add(ledger.isReleased());
        args.add(ts(ledger.getReleasedAt()));
        args.add(ledger.getReleaseReason());
        args.add(ledger.getSecurityType());
        args.add(ledger.getSecurityStatus());
        args.add(ledger.getRequiredAccountRef());
        args.add(ledger.isAccountChecked());
        args.add(ts(ledger.getAccountCheckedAt()));
        args.add(ledger.getAccountCheckedBy());
        args.add(ledger.getLastAuditDate() == null ? null
                : java.sql.Date.valueOf(ledger.getLastAuditDate()));
        args.add(ledger.getAuditMismatchCount());
        args.add(ledger.getLastAuditBy());
        args.add(ledger.getDisposalCaseNo());
        args.add(ts(ledger.getDisposedAt()));
        args.add(ledger.isRegistrationDue());
        args.add(ledger.getRegistrationNote());

        String sql = "INSERT INTO collateral_ledger (" + INSERT_COLUMNS + ") "
                + "VALUES (" + INSERT_VALUES + ") "
                + "ON CONFLICT (vin) DO UPDATE SET "
                + "dealer_id = EXCLUDED.dealer_id, "
                + "financed = EXCLUDED.financed, financed_at = EXCLUDED.financed_at, "
                + "advance_no = EXCLUDED.advance_no, "
                + "sold = EXCLUDED.sold, sold_at = EXCLUDED.sold_at, sold_flagged_by = EXCLUDED.sold_flagged_by, "
                + "repaid = EXCLUDED.repaid, repaid_at = EXCLUDED.repaid_at, repaid_amount = EXCLUDED.repaid_amount, "
                + "released = EXCLUDED.released, released_at = EXCLUDED.released_at, release_reason = EXCLUDED.release_reason, "
                + "security_type = EXCLUDED.security_type, security_status = EXCLUDED.security_status, "
                + "required_account_ref = EXCLUDED.required_account_ref, account_checked = EXCLUDED.account_checked, "
                + "account_checked_at = EXCLUDED.account_checked_at, account_checked_by = EXCLUDED.account_checked_by, "
                + "last_audit_date = EXCLUDED.last_audit_date, audit_mismatch_count = EXCLUDED.audit_mismatch_count, "
                + "last_audit_by = EXCLUDED.last_audit_by, "
                + "disposal_case_no = EXCLUDED.disposal_case_no, disposed_at = EXCLUDED.disposed_at, "
                + "registration_due = EXCLUDED.registration_due, registration_note = EXCLUDED.registration_note, "
                + "updated_at = now() "
                + "RETURNING id, created_at, updated_at";

        jdbc.queryForObject(sql, (rs, i) -> {
            ledger.setId(rs.getLong("id"));
            Timestamp created = rs.getTimestamp("created_at");
            ledger.setCreatedAt(created == null ? null : created.toLocalDateTime());
            Timestamp updated = rs.getTimestamp("updated_at");
            ledger.setUpdatedAt(updated == null ? null : updated.toLocalDateTime());
            return ledger;
        }, args.toArray());
        return ledger;
    }

    private static Timestamp ts(LocalDateTime t) {
        return t == null ? null : Timestamp.valueOf(t);
    }
}
