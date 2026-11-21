package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.BankFetchBatch;
import com.company.floorplan.domain.model.BankStatementLine;
import com.company.floorplan.domain.model.BankStatementResult;
import com.company.floorplan.domain.model.PaymentTransaction;
import com.company.floorplan.domain.repository.BankFetchBatchRepository;
import com.company.floorplan.domain.repository.PaymentTransactionRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.domain.service.BankStatementSource;
import com.company.floorplan.domain.service.DistributedLock;
import com.company.floorplan.domain.service.LockHandle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 银行回款流水采集（BANK_FETCH）。
 *
 * <h3>它解决的问题</h3>
 * <p>经销商卖车后把钱打进指定账户，系统必须知道"钱到了"，
 * 否则额度不释放、车停在 {@code SOLD_PENDING}、结算窗口一过就被误判为 OOT。
 * 而"钱到了"这件事，只能由银行的事实记录证明——不能靠我方核销记录自说自话。</p>
 *
 * <h3>为什么这个方法不加 {@code @Transactional}</h3>
 * <p>与 {@code disburseAdvance} 同一条理由：<b>外部调用不能包在数据库事务里</b>。</p>
 * <ul>
 *   <li>银行接口可能超时 30 秒，包在事务里意味着连接池被占 30 秒；</li>
 *   <li>事务回滚时外部调用不会回滚——文件已经拉下来了，日志已经写了；</li>
 *   <li>真正需要原子性的是"每行流水入库"，而这一步由单条
 *       {@code INSERT ... ON CONFLICT DO NOTHING} 自成一个原子单元，本来就不需要外层事务。</li>
 * </ul>
 * <p>换句话说：<b>这里的事务边界是"一行"，不是一个批次。</b>
 * 一批 100 条流水，第 37 条脏数据不该让前 36 条白拉。</p>
 *
 * <h3>锁的粒度是「账户」，不是「作业」</h3>
 * <p>锁键 {@code lock:bank:fetch:{accountRef}:{valueDateTo}}：</p>
 * <ul>
 *   <li><b>同一账户必须串行</b>：并发拉同一账户会同时触发同一批 Unique 冲突，
 *       浪费连接、污染批次统计，且若将来引入"拉取即匹配"会重复开工单；</li>
 *   <li><b>不同账户可以并行</b>：锁整个作业会让 5 个账户排队，
 *       而它们之间毫无冲突。粒度错配的代价是作业时长乘以账户数。</li>
 * </ul>
 * <p>注意锁键里带日期：不同业务日互不影响，昨天的补拉不挡今天的正常拉取。</p>
 *
 * <h3>为什么窗口要「重叠」</h3>
 * <p>拉取窗口是 {@code [bizDate - lookbackDays, bizDate]}，默认往前多看 3 天。
 * 因为银行入账有延迟、跨行转账可能 T+1、文件可能晚推。
 * 只拉当天会漏掉"昨天到账但今天才进文件"的钱——而漏一笔就是一笔敞口。</p>
 * <p>重叠必然带来重复，<b>但重复不是错误</b>：
 * 它由 {@code uk_payment_txn} 在行级挡掉，并计入 {@code duplicatedCount}。
 * 一个健康的采集作业，{@code duplicatedCount > 0} 是常态，不是异常。</p>
 */
@Service
public class BankFetchApplicationService {

    /** 单账户结果：成功。 */
    private static final String OK = "SUCCESS";
    /** 单账户结果：没抢到锁，主动退出（不是失败）。 */
    private static final String LOCKED = "SKIPPED_LOCKED";
    /** 单账户结果：采集失败。 */
    private static final String FAILED = "FAILED";

    private final PaymentTransactionRepository transactionRepository;
    private final BankFetchBatchRepository batchRepository;
    private final BankStatementSource statementSource;
    private final DistributedLock distributedLock;
    private final SupportRepository supportRepository;

    private final int lookbackDays;
    private final Duration lockTtl;
    private final List<String> defaultAccounts;

    public BankFetchApplicationService(PaymentTransactionRepository transactionRepository,
                                       BankFetchBatchRepository batchRepository,
                                       BankStatementSource statementSource,
                                       DistributedLock distributedLock,
                                       SupportRepository supportRepository,
                                       @Value("${floorplan.bank-fetch.lookback-days:3}") int lookbackDays,
                                       @Value("${floorplan.bank-fetch.lock-ttl-seconds:300}") long lockTtlSeconds,
                                       @Value("${floorplan.bank-fetch.accounts:}") String accountsCsv) {
        this.transactionRepository = transactionRepository;
        this.batchRepository = batchRepository;
        this.statementSource = statementSource;
        this.distributedLock = distributedLock;
        this.supportRepository = supportRepository;
        this.lookbackDays = lookbackDays;
        this.lockTtl = Duration.ofSeconds(lockTtlSeconds);
        this.defaultAccounts = accountsCsv == null || accountsCsv.isBlank()
                ? List.of()
                : Arrays.stream(accountsCsv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * 执行采集。
     *
     * @param bizDate          业务日（窗口右端）
     * @param requestedAccounts 指定账户；为空则用配置 {@code floorplan.bank-fetch.accounts}
     * @param actor            操作人（作业触达为 system）
     */
    public Map<String, Object> fetch(LocalDate bizDate, List<String> requestedAccounts, String actor) {
        List<String> accounts = resolveAccounts(requestedAccounts);
        if (accounts.isEmpty()) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "未配置任何银行账户：请在 floorplan.bank-fetch.accounts 配置，或在请求中传 accountRefs");
        }

        LocalDate from = bizDate.minusDays(lookbackDays);
        LocalDate to = bizDate;

        List<Map<String, Object>> details = new ArrayList<>();
        int success = 0;
        int locked = 0;
        int failed = 0;
        int fetchedTotal = 0;
        int insertedTotal = 0;
        int duplicatedTotal = 0;

        for (String accountRef : accounts) {
            Map<String, Object> one = fetchOne(accountRef, from, to, actor);
            details.add(one);
            String status = String.valueOf(one.get("status"));
            switch (status) {
                case OK -> {
                    success++;
                    fetchedTotal += intOf(one.get("fetched"));
                    insertedTotal += intOf(one.get("inserted"));
                    duplicatedTotal += intOf(one.get("duplicated"));
                }
                case LOCKED -> locked++;
                default -> failed++;
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobName", "BANK_FETCH");
        data.put("bizDate", bizDate.toString());
        data.put("window", Map.of("from", from.toString(), "to", to.toString()));
        data.put("lockProvider", distributedLock.provider());
        data.put("source", statementSource.source());
        data.put("accountCount", accounts.size());
        data.put("successCount", success);
        data.put("lockedCount", locked);
        data.put("failedCount", failed);
        data.put("fetchedCount", fetchedTotal);
        data.put("insertedCount", insertedTotal);
        data.put("duplicatedCount", duplicatedTotal);
        data.put("details", details);

        // 全部账户都因为外部原因失败 → 这才是作业失败。
        // 若全被锁占用（locked），说明别的实例正在跑，属于正常互斥，不该报错。
        if (success == 0 && failed > 0) {
            throw new BusinessException(ErrorCode.BANK_001,
                    "全部账户采集失败（" + failed + " 个），详见批次记录 bank_fetch_batches");
        }
        return data;
    }

    /**
     * 单账户采集：抢锁 → 落批次 → 外部拉取 → 逐行幂等入库 → 收尾。
     *
     * <p>锁用 try-with-resources 释放，保证异常路径也会解锁。
     * {@link LockHandle#release()} 本身幂等，重复调用无副作用。</p>
     */
    private Map<String, Object> fetchOne(String accountRef, LocalDate from, LocalDate to, String actor) {
        String lockKey = "lock:bank:fetch:" + accountRef + ":" + to;
        Optional<LockHandle> handle = distributedLock.tryLock(lockKey, lockTtl);
        if (handle.isEmpty()) {
            // 不抛异常：另一个实例正在跑，这是设计内的互斥结果，不是错误。
            // 抛异常会让"多点了一次"看起来像系统故障，值班被无谓唤醒。
            Map<String, Object> skip = new LinkedHashMap<>();
            skip.put("accountRef", accountRef);
            skip.put("status", LOCKED);
            skip.put("reason", "另一执行者正在采集该账户（锁键 " + lockKey + "），本次跳过");
            return skip;
        }
        try (LockHandle acquired = handle.get()) {
            return doFetch(accountRef, from, to, actor);
        } catch (RuntimeException e) {
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("accountRef", accountRef);
            fail.put("status", FAILED);
            fail.put("error", e.getMessage());
            return fail;
        }
    }

    /** 真正干活的部分：批次先落 RUNNING，再拉取，再逐行写。 */
    private Map<String, Object> doFetch(String accountRef, LocalDate from, LocalDate to, String actor) {
        BankFetchBatch batch = BankFetchBatch.start(accountRef, from, to, statementSource.source());
        // 先落 RUNNING：如果进程在拉取途中被杀，库里会留下一条 RUNNING，
        // 事后能回答"这次到底是没跑，还是跑到一半死了"。
        batchRepository.save(batch);

        try {
            BankStatementResult result = statementSource.fetch(accountRef, from, to);
            batch.setFileFingerprint(result.fingerprint());

            int inserted = 0;
            int duplicated = 0;
            int unusable = 0;
            for (BankStatementLine line : result.lines()) {
                if (!line.isUsable() || line.amount() == 0) {
                    unusable++;
                    continue;
                }
                PaymentTransaction tx = PaymentTransaction.collected(
                        line, statementSource.source(), batch.getBatchNo());
                if (transactionRepository.insertIfAbsent(tx)) {
                    inserted++;
                } else {
                    duplicated++;
                }
            }

            batch.succeed(result.size(), inserted, duplicated);
            batchRepository.save(batch);

            supportRepository.writeAudit(actor, "BANK_FETCH", "BANK_ACCOUNT", accountRef,
                    null,
                    "batch=" + batch.getBatchNo()
                            + ",fetched=" + result.size()
                            + ",inserted=" + inserted
                            + ",duplicated=" + duplicated
                            + ",unusable=" + unusable
                            + ",fingerprint=" + result.fingerprint(),
                    null);

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("accountRef", accountRef);
            detail.put("status", OK);
            detail.put("batchNo", batch.getBatchNo());
            detail.put("fetched", result.size());
            detail.put("inserted", inserted);
            detail.put("duplicated", duplicated);
            detail.put("unusable", unusable);
            detail.put("fingerprint", result.fingerprint());
            return detail;
        } catch (RuntimeException e) {
            // 失败也要落库：连续失败的批次是"银行通道坏了"的唯一证据。
            // 这里再包一层 try，避免"记录失败"本身抛异常，把原始错误吞掉。
            batch.fail(e.getMessage());
            try {
                batchRepository.save(batch);
            } catch (RuntimeException ignored) {
                // 数据库也不可用时，原始异常更重要，保留它往上抛
            }
            throw e;
        }
    }

    private List<String> resolveAccounts(List<String> requested) {
        if (requested != null && !requested.isEmpty()) {
            return requested.stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        }
        return defaultAccounts;
    }

    private static int intOf(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }
}
