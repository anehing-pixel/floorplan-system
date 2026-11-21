package com.company.floorplan.infrastructure.integration;

import com.company.floorplan.domain.service.PaymentGateway;
import com.company.floorplan.domain.service.PaymentInstruction;
import com.company.floorplan.domain.service.PaymentResult;
import com.company.floorplan.domain.service.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 支付网关的模拟实现（真实通道未对接）。
 *
 * <p>按外部集成文档的设计：当客户确认通道后，只需替换本实现（api / file / manual 三种形态），
 * 应用层与领域层无需改动——这就是"隔离外部变更"的接缝。</p>
 *
 * <p><b>一个合格的支付 Mock 必须复刻真实渠道的三件事</b>（缺一条，对应的危险分支就测不到）：</p>
 * <ol>
 *   <li><b>结果未知</b>（mode=UNKNOWN）：真实渠道会超时、会只回受理号不回结果。
 *       如果 Mock 只会返回成功/失败，应用层"保留待查单、不释放额度"的分支
 *       永远不会被执行——而那恰恰是资金事故最高发的路径；</li>
 *   <li><b>指令号幂等</b>：同一 {@code orderNo} 重复 pay 不重复出款，返回首次受理结果。
 *       超时重试与补偿作业的正确性全靠这条；</li>
 *   <li><b>可查单</b>：{@link #query(String)} 凭指令号回查，未落终态的指令在第
 *       {@code unknown-resolve-queries} 次查询后"确认已出款"——
 *       模拟渠道慢处理最终成功的真实时序。</li>
 * </ol>
 *
 * <p><b>不需要复刻的</b>：报文签名、加密、真实网络栈——那是渠道自己的事，
 * 复刻它们只会让测试脆弱，不增加资金安全覆盖。</p>
 *
 * <p><b>配置</b>（application.yml → floorplan.payment）：</p>
 * <ul>
 *   <li>{@code mode: SUCCESS | FAIL | UNKNOWN} —— 首次付款的返回形态，预设 SUCCESS；</li>
 *   <li>{@code simulate-failure}（旧键，兼容保留）—— true 等价 mode=FAIL；</li>
 *   <li>{@code simulate-latency-ms} —— 模拟渠道响应延迟，验证"外部 IO 拖长事务持锁"；</li>
 *   <li>{@code unknown-resolve-queries} —— UNKNOWN 指令被第几次查单确认已出款，预设 1。</li>
 * </ul>
 *
 * <p>状态保存在内存（进程重启即清空），仅供开发/测试，生产必须替换为真实实现。</p>
 */
@Component
public class MockPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);

    /** 首次付款的模拟行为。 */
    public enum Mode {
        /** 当场返回出款成功（原型默认）。 */
        SUCCESS,
        /** 当场明确拒绝，未出款。 */
        FAIL,
        /** 当场不给出终态（超时/仅受理），需靠查单收敛。 */
        UNKNOWN
    }

    private final Mode configuredMode;
    private final boolean simulateFailure;
    private final long latencyMs;
    private final int unknownResolveQueries;

    /** 已受理的模拟付款：orderNo → 指令。并发下用 compute 保证幂等判断原子。 */
    private final ConcurrentMap<String, MockPayment> payments = new ConcurrentHashMap<>();
    private final AtomicLong refSeq = new AtomicLong(1);

    public MockPaymentGateway(
            @Value("${floorplan.payment.mode:SUCCESS}") String mode,
            @Value("${floorplan.payment.simulate-failure:false}") boolean simulateFailure,
            @Value("${floorplan.payment.simulate-latency-ms:0}") long latencyMs,
            @Value("${floorplan.payment.unknown-resolve-queries:1}") int unknownResolveQueries) {
        this.configuredMode = parseMode(mode);
        this.simulateFailure = simulateFailure;
        this.latencyMs = Math.max(0, latencyMs);
        this.unknownResolveQueries = Math.max(1, unknownResolveQueries);
    }

    /** 单笔模拟付款的内部状态。result 会从 UNKNOWN 迁移到终态，故可变。 */
    private static final class MockPayment {
        final long amount;
        PaymentResult result;
        Instant paidAt;
        final AtomicInteger queryCount = new AtomicInteger();

        MockPayment(long amount, PaymentResult result) {
            this.amount = amount;
            this.result = result;
        }
    }

    @Override
    public PaymentResult pay(PaymentInstruction instruction) {
        sleepQuietly();
        String vin = instruction.getVin();
        long amount = instruction.getAmount();
        String orderNo = instruction.getOrderNo();
        boolean[] newlyAccepted = {false};
        MockPayment payment = payments.compute(orderNo, (key, existing) -> {
            if (existing != null) {
                return existing;          // 已有同指令号记录 → 不重复出款
            }
            newlyAccepted[0] = true;
            return new MockPayment(amount, decideFirstResult());
        });
        if (!newlyAccepted[0]) {
            // 幂等重放：返回首次结果，ref 与首答完全一致（应用层靠它安全重试）
            log.info("模拟付款·幂等重放：orderNo={} 首次结果={} 金额不重复出款", orderNo, payment.result);
            return payment.result;
        }
        log.info("模拟付款·受理：orderNo={} vin={} amount={} payeeType={} payeeAccountRef={} result={}",
                orderNo, vin, amount, instruction.getPayeeType(), instruction.getPayeeAccountRef(),
                payment.result);
        return payment.result;
    }

    @Override
    public QueryResult query(String orderNo) {
        sleepQuietly();
        MockPayment payment = payments.get(orderNo);
        if (payment == null) {
            // 渠道从未见过这个指令号 → 可安全判定未出款
            return QueryResult.notFound();
        }
        synchronized (payment) {
            if (payment.result.isSuccess()) {
                return QueryResult.succeeded(payment.paidAt);
            }
            if (payment.result.isFailed()) {
                return new QueryResult(QueryResult.State.NOT_FOUND_OR_FAILED, null, payment.result.getMessage());
            }
            // UNKNOWN：模拟渠道慢处理——第 N 次查询后"确认已出款"（资金其实划走了）
            int n = payment.queryCount.incrementAndGet();
            if (n >= unknownResolveQueries) {
                String channelRef = "PAY-" + String.format("%08d", refSeq.getAndIncrement());
                payment.result = PaymentResult.ok(channelRef);
                payment.paidAt = Instant.now();
                log.info("模拟查单·确认出款：orderNo={} 第{}次查询 渠道流水={} 金额={}",
                        orderNo, n, channelRef, payment.amount);
                return QueryResult.succeeded(payment.paidAt);
            }
            log.info("模拟查单·仍在处理：orderNo={} 第{}次查询（第{}次确认后收敛）",
                    orderNo, n, unknownResolveQueries);
            return QueryResult.stillProcessing();
        }
    }

    /** 供测试断言：当前已受理的指令数（幂等重放不应增加它）。 */
    public int acceptedCount() {
        return payments.size();
    }

    /** 供测试隔离：清空内存状态，逐用例独立。 */
    public void reset() {
        payments.clear();
    }

    private PaymentResult decideFirstResult() {
        Mode m = effectiveMode();
        return switch (m) {
            case FAIL -> PaymentResult.fail("模拟支付通道拒绝出款");
            case UNKNOWN -> PaymentResult.unknown(
                    "ACK-" + String.format("%08d", refSeq.getAndIncrement()),
                    "模拟渠道超时：受理号已返回，出款终态未知");
            case SUCCESS -> PaymentResult.ok(
                    "PAY-" + String.format("%08d", refSeq.getAndIncrement()));
        };
    }

    /** 旧键 simulate-failure=true 优先于 mode，保证冒烟测试等既有配置不改也能跑。 */
    private Mode effectiveMode() {
        return simulateFailure ? Mode.FAIL : configuredMode;
    }

    private static Mode parseMode(String raw) {
        try {
            return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            log.warn("floorplan.payment.mode 配置值非法：{}，按 SUCCESS 处理", raw);
            return Mode.SUCCESS;
        }
    }

    private void sleepQuietly() {
        if (latencyMs <= 0) {
            return;
        }
        try {
            Thread.sleep(latencyMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
