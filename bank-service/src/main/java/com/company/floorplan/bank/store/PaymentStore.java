package com.company.floorplan.bank.store;

import com.company.floorplan.bank.model.CreditRestore;
import com.company.floorplan.bank.model.LienRelease;
import com.company.floorplan.bank.model.MatchStatus;
import com.company.floorplan.bank.model.PaymentMatch;

import java.util.List;
import java.util.Optional;

/**
 * 匹配/解押/回补三类结论的持久化端口（对应 V17 三张表）。
 *
 * <p>当前用内存实现便于无 PG 环境跑通与单测；真实 JDBC 实现按 V17 表落地。
 * 所有写方法按唯一键做"插入或忽略"，把幂等责任放在存储层，不靠调用方先查后写。</p>
 */
public interface PaymentStore {

    PaymentMatch saveMatchIfAbsent(PaymentMatch match);

    Optional<PaymentMatch> findMatch(String accountRef, String transactionId);

    /** 人工改判：覆盖既有结论（同一幂等键，不新增行）。 */
    PaymentMatch saveMatch(PaymentMatch match);

    List<PaymentMatch> findAllMatches();

    List<PaymentMatch> findMatchesByStatus(MatchStatus status);

    LienRelease saveReleaseIfAbsent(LienRelease release);

    LienRelease saveRelease(LienRelease release);

    Optional<LienRelease> findReleaseByMatchId(Long matchId);

    List<LienRelease> findAllReleases();

    CreditRestore saveRestoreIfAbsent(CreditRestore restore);

    CreditRestore saveRestore(CreditRestore restore);

    Optional<CreditRestore> findRestoreByMatchId(Long matchId);

    List<CreditRestore> findAllRestores();
}
