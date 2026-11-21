package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.BankAccount;
import com.company.floorplan.domain.model.BankAccountStatus;
import com.company.floorplan.domain.repository.BankAccountRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.util.List;
import java.util.Optional;

/**
 * 收款账户仓储实现（基础设施层）。
 *
 * <p><b>两张表（上游账户 / 经销商结算账户）结构相同但归属不同</b>，
 * 这里用 {@link BankAccount#getOwnerType()} 分流读写。
 * 之所以不合并成一张表加一个可空外键：那样"哪个外键有值"就变成了
 * 数据正确性的隐式约定，而拆成两张表能把这个约定交给数据库外键强制。</p>
 */
@Repository
public class JdbcBankAccountRepository implements BankAccountRepository {

    private final JdbcTemplate jdbc;

    public JdbcBankAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static RowMapper<BankAccount> mapper(BankAccount.OwnerType ownerType) {
        return (rs, i) -> {
            BankAccount a = new BankAccount();
            a.setOwnerType(ownerType);
            a.setId(rs.getLong("id"));
            a.setSupplierId(ownerType == BankAccount.OwnerType.SUPPLIER ? rs.getLong("supplier_id") : null);
            a.setDealerId(ownerType == BankAccount.OwnerType.DEALER ? rs.getLong("dealer_id") : null);
            a.setBankName(rs.getString("bank_name"));
            a.setAccountHolder(rs.getString("account_holder"));
            a.setAccountNoCipher(rs.getString("account_no_cipher"));
            a.setAccountNoMask(rs.getString("account_no_mask"));
            a.setAccountRef(rs.getString("account_ref"));
            String status = rs.getString("status");
            a.setStatus(status == null ? BankAccountStatus.ACTIVE : BankAccountStatus.valueOf(status));
            a.setEffectiveFrom(rs.getDate("effective_from") == null ? null : rs.getDate("effective_from").toLocalDate());
            a.setEffectiveTo(rs.getDate("effective_to") == null ? null : rs.getDate("effective_to").toLocalDate());
            a.setReviewer(rs.getString("reviewer"));
            a.setCreatedAt(rs.getDate("created_at") == null ? null : rs.getDate("created_at").toLocalDate());
            return a;
        };
    }

    private static final RowMapper<BankAccount> SUPPLIER_MAPPER =
            mapper(BankAccount.OwnerType.SUPPLIER);
    private static final RowMapper<BankAccount> DEALER_MAPPER =
            mapper(BankAccount.OwnerType.DEALER);

    @Override
    public Optional<BankAccount> findByRef(String accountRef) {
        if (accountRef == null || accountRef.isBlank()) {
            return Optional.empty();
        }
        // 引用码在两张表各自 UNIQUE，但跨表不保证唯一，因此按供应商侧先查、
        // 再查经销商侧——命中即返回（导入时若两侧撞码，视为数据问题并报错）。
        List<BankAccount> fromSupplier = jdbc.query(
                "SELECT * FROM supplier_bank_accounts WHERE account_ref = ?", SUPPLIER_MAPPER, accountRef);
        if (!fromSupplier.isEmpty()) {
            return Optional.of(fromSupplier.get(0));
        }
        return jdbc.query("SELECT * FROM dealer_bank_accounts WHERE account_ref = ?", DEALER_MAPPER, accountRef)
                .stream().findFirst();
    }

    @Override
    public Optional<BankAccount> findById(Long id) {
        List<BankAccount> fromSupplier = jdbc.query(
                "SELECT * FROM supplier_bank_accounts WHERE id = ?", SUPPLIER_MAPPER, id);
        if (!fromSupplier.isEmpty()) {
            return Optional.of(fromSupplier.get(0));
        }
        return jdbc.query("SELECT * FROM dealer_bank_accounts WHERE id = ?", DEALER_MAPPER, id)
                .stream().findFirst();
    }

    @Override
    public List<BankAccount> findBySupplierId(Long supplierId) {
        return jdbc.query("SELECT * FROM supplier_bank_accounts WHERE supplier_id = ? ORDER BY id",
                SUPPLIER_MAPPER, supplierId);
    }

    @Override
    public List<BankAccount> findByDealerId(Long dealerId) {
        return jdbc.query("SELECT * FROM dealer_bank_accounts WHERE dealer_id = ? ORDER BY id",
                DEALER_MAPPER, dealerId);
    }

    @Override
    public List<BankAccount> findAll() {
        List<BankAccount> all = new java.util.ArrayList<>(
                jdbc.query("SELECT * FROM supplier_bank_accounts ORDER BY id", SUPPLIER_MAPPER));
        all.addAll(jdbc.query("SELECT * FROM dealer_bank_accounts ORDER BY id", DEALER_MAPPER));
        return all;
    }

    @Override
    public BankAccount save(BankAccount a) {
        if (a.getOwnerType() == BankAccount.OwnerType.DEALER) {
            if (a.getId() == null) {
                Long id = jdbc.queryForObject(
                        "INSERT INTO dealer_bank_accounts (dealer_id, bank_name, account_holder, "
                                + "account_no_cipher, account_no_mask, account_ref, status, "
                                + "effective_from, effective_to, reviewer) VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id",
                        Long.class,
                        a.getDealerId(), a.getBankName(), a.getAccountHolder(),
                        a.getAccountNoCipher(), a.getAccountNoMask(), a.getAccountRef(),
                        a.getStatus() == null ? BankAccountStatus.ACTIVE.name() : a.getStatus().name(),
                        toSqlDate(a.getEffectiveFrom()), toSqlDate(a.getEffectiveTo()), a.getReviewer());
                a.setId(id);
            } else {
                jdbc.update("UPDATE dealer_bank_accounts SET bank_name=?, account_holder=?, "
                                + "account_no_cipher=?, account_no_mask=?, account_ref=?, status=?, "
                                + "effective_from=?, effective_to=?, reviewer=?, updated_at=now() WHERE id=?",
                        a.getBankName(), a.getAccountHolder(), a.getAccountNoCipher(), a.getAccountNoMask(),
                        a.getAccountRef(),
                        a.getStatus() == null ? BankAccountStatus.ACTIVE.name() : a.getStatus().name(),
                        toSqlDate(a.getEffectiveFrom()), toSqlDate(a.getEffectiveTo()), a.getReviewer(), a.getId());
            }
            return a;
        }
        if (a.getId() == null) {
            Long id = jdbc.queryForObject(
                    "INSERT INTO supplier_bank_accounts (supplier_id, bank_name, account_holder, "
                            + "account_no_cipher, account_no_mask, account_ref, status, "
                            + "effective_from, effective_to, reviewer) VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id",
                    Long.class,
                    a.getSupplierId(), a.getBankName(), a.getAccountHolder(),
                    a.getAccountNoCipher(), a.getAccountNoMask(), a.getAccountRef(),
                    a.getStatus() == null ? BankAccountStatus.ACTIVE.name() : a.getStatus().name(),
                    toSqlDate(a.getEffectiveFrom()), toSqlDate(a.getEffectiveTo()), a.getReviewer());
            a.setId(id);
        } else {
            jdbc.update("UPDATE supplier_bank_accounts SET bank_name=?, account_holder=?, "
                            + "account_no_cipher=?, account_no_mask=?, account_ref=?, status=?, "
                            + "effective_from=?, effective_to=?, reviewer=?, updated_at=now() WHERE id=?",
                    a.getBankName(), a.getAccountHolder(), a.getAccountNoCipher(), a.getAccountNoMask(),
                    a.getAccountRef(),
                    a.getStatus() == null ? BankAccountStatus.ACTIVE.name() : a.getStatus().name(),
                    toSqlDate(a.getEffectiveFrom()), toSqlDate(a.getEffectiveTo()), a.getReviewer(), a.getId());
        }
        return a;
    }

    @Override
    public int updateStatus(Long id, String status) {
        // 先试供应商侧再试经销商侧：账户 ID 在两张表各自自增，跨表可能撞号，
        // 因此这里用"更新成功行数"判断归属，而不是先查后写（避免竞态）。
        int n = jdbc.update("UPDATE supplier_bank_accounts SET status=?, updated_at=now() WHERE id=?",
                status, id);
        if (n > 0) {
            return n;
        }
        return jdbc.update("UPDATE dealer_bank_accounts SET status=?, updated_at=now() WHERE id=?", status, id);
    }

    private static Date toSqlDate(java.time.LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }
}
