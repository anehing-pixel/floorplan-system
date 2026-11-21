package com.company.floorplan.bank.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 借据查询端口：payment → loan 的唯一下游接缝。
 *
 * <p>真实实现应通过 Feign 调 {@code GET /api/loans/by-vin/{vin}}（V16 契约冻结），
 * 当前仓库未接入，先用 {@link LoggingLoanLookupClient} 日志存根顶上，见 HANDOFF TODO。</p>
 *
 * <p>404 必须与"系统错误"区分：契约 §2.4 明确查不到返回 404 =
 * "该 VIN 无在贷债务"，应走人工/忽略分支，不能当异常重试。</p>
 */
public interface LoanLookupClient {

    /** 按 VIN 取当前未结清借据（契约保证最多一笔，按 id DESC）。 */
    Optional<LoanSnapshot> findActiveByVin(String vin);

    /** 批量查；默认逐个调用，便于存根实现不必重复这段循环。 */
    default List<LoanSnapshot> findActiveByVins(Collection<String> vins) {
        List<LoanSnapshot> result = new ArrayList<>();
        if (vins == null) {
            return result;
        }
        for (String vin : vins) {
            findActiveByVin(vin).ifPresent(result::add);
        }
        return result;
    }
}
