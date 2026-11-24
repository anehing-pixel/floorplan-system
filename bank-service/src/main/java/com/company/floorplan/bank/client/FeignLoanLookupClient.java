package com.company.floorplan.bank.client;

import com.company.floorplan.common.contract.LoanQueryClient;
import com.company.floorplan.common.contract.LoanSnapshotDto;
import com.company.floorplan.common.dto.ApiResponse;
import feign.FeignException;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link LoanLookupClient} 的真实实现：走冻结契约 {@link LoanQueryClient}。
 *
 * <p><b>为什么不是 JDBC</b>：{@code AGENTS §3.1} 禁止新代码直接读别的服务的表。
 * 共库让直查 {@code loans} 现在能跑，物理拆库那天会立刻失效；契约端点
 * {@code GET /api/loans/by-vin/{vin}} 已由 loan-service 实现并有冒烟覆盖。</p>
 *
 * <p><b>404 与故障必须分开</b>（契约 §6.3 调用方语义）：
 * <ul>
 *   <li>404 → {@link Optional#empty()}：这台车没有未结清借据，是合法业务事实，流水挂起待人工；</li>
 *   <li>连接失败 / 5xx / 非 OK 响应 → 抛异常：让本次匹配失败并可重试。
 *       把"服务不可达"当成"没借据"会让一笔真实回款永久挂起，甚至被人工误判。</li>
 * </ul>
 *
 * <p><b>认证</b>：loan-service 的 {@code by-vin} 端点要求合法 JWT。调用发生在回款匹配的
 * HTTP 请求线程里，{@code BankFeignConfig} 会把当前请求的 Authorization/X-Operator-Id
 * 透传给下游；没有请求上下文的批处理线程不伪造身份，会在这里显式失败。</p>
 */
public class FeignLoanLookupClient implements LoanLookupClient {

    private final LoanQueryClient client;

    public FeignLoanLookupClient(LoanQueryClient client) {
        this.client = Objects.requireNonNull(client, "LoanQueryClient");
    }

    @Override
    public Optional<LoanSnapshot> findActiveByVin(String vin) {
        if (vin == null || vin.isBlank()) {
            return Optional.empty();
        }
        final ApiResponse<LoanSnapshotDto> resp;
        try {
            resp = client.getByVin(vin);
        } catch (FeignException.NotFound e) {
            // 契约 §6.3：404 = 该 VIN 没有在贷债务，不是系统错误
            return Optional.empty();
        }
        if (resp == null) {
            throw new IllegalStateException("loan-service by-vin 返回空响应：vin=" + vin);
        }
        if (!"OK".equals(resp.getCode())) {
            // 200 + 非 OK 是契约被改坏的信号；绝不静默当成"没借据"
            throw new IllegalStateException("loan-service by-vin 非 OK 响应：vin=" + vin
                    + " code=" + resp.getCode() + " message=" + resp.getMessage());
        }
        LoanSnapshotDto d = resp.getData();
        if (d == null) {
            return Optional.empty();
        }
        return Optional.of(new LoanSnapshot(
                d.getId(),
                null,                       // by-vin 投影不含借据号；bank 侧不依赖它
                d.getAdvanceId(),
                d.getVin(),
                d.getDealerId(),
                d.getCreditLineId(),
                d.getPrincipal(),
                parseDueDate(d.getDueDate(), vin),
                d.getStatus(),
                null,                       // createdAt 不在 8 字段投影里
                null,                       // principalOutstanding 不在投影里：expectedAmount 回退到 principal
                null, null, null));
    }

    private static LocalDate parseDueDate(String raw, String vin) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (RuntimeException e) {
            // 到期日格式坏了说明契约实现方出错，不能当作"没有到期日"继续匹配
            throw new IllegalStateException("loan-service by-vin 的 dueDate 不是 ISO 日期：vin=" + vin
                    + " dueDate=" + raw, e);
        }
    }
}
