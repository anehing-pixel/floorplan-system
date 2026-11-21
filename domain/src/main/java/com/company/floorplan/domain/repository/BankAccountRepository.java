package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.BankAccount;

import java.util.List;
import java.util.Optional;

/**
 * 收款账户仓储（上游账户 + 经销商结算账户）。
 *
 * <p>两类归属共用一张实现，靠 {@link BankAccount#getOwnerType()} 决定读写哪张表。</p>
 */
public interface BankAccountRepository {

    /** 按业务引用码查询——Excel/CSV 导入只传引用码，靠这个定位账户。 */
    Optional<BankAccount> findByRef(String accountRef);

    Optional<BankAccount> findById(Long id);

    /** 列出某上游的全部账户。 */
    List<BankAccount> findBySupplierId(Long supplierId);

    /** 列出某经销商的全部结算账户。 */
    List<BankAccount> findByDealerId(Long dealerId);

    List<BankAccount> findAll();

    BankAccount save(BankAccount account);

    /** 更新账户状态（ACTIVE / PENDING_REVIEW / FROZEN / CLOSED）。 */
    int updateStatus(Long id, String status);
}
