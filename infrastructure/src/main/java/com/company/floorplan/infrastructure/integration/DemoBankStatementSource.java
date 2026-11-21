package com.company.floorplan.infrastructure.integration;

import com.company.floorplan.domain.model.BankStatementLine;
import com.company.floorplan.domain.model.BankStatementResult;
import com.company.floorplan.domain.service.BankStatementSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 演示用采集适配器 —— 不依赖任何银行、任何文件，直接返回固定样本。
 *
 * <p><b>它的存在不是为了"少写代码"，而是为了让幂等可以被亲眼验证</b>：
 * 每笔流水的 {@code transactionId} 由「账户 + 日期 + 序号」哈希而成，<b>同一天多次调用返回完全相同的 id</b>。
 * 于是连续点两次采集作业，第二次的 {@code insertedCount=0, duplicatedCount=3}——
 * 这正是生产里"银行文件重发"的形态，但不需要准备文件、不需要模拟银行。</p>
 *
 * <p>启用方式：{@code floorplan.bank-fetch.source=demo}。</p>
 */
@Component
@ConditionalOnProperty(name = "floorplan.bank-fetch.source", havingValue = "demo")
public class DemoBankStatementSource implements BankStatementSource {

    private static final DateTimeFormatter COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 金额（日元） / 付款人 / 备注里带的 VIN —— 模拟真实摘要"振込 DEMO-VIN-0001"。 */
    private static final Object[][] SAMPLES = {
            {1_250_000L, "株式会社サンプル自動車", "DEMO-VIN-0001"},
            {980_000L, "サンプルモータース", "DEMO-VIN-0002"},
            {450_000L, "株式会社サンプル自動車", "DEMO-VIN-0003"},
    };

    @Override
    public String source() {
        return "DEMO";
    }

    @Override
    public BankStatementResult fetch(String accountRef, LocalDate from, LocalDate to) {
        List<BankStatementLine> lines = new ArrayList<>();
        String day = to.format(COMPACT);
        for (int i = 0; i < SAMPLES.length; i++) {
            long amount = (Long) SAMPLES[i][0];
            String payer = (String) SAMPLES[i][1];
            String vin = (String) SAMPLES[i][2];

            // 幂等锚点稳定：同账户同日期同序号 → 永远同一个流水号。
            // 若这里用 UUID，第二次拉取就会插成新行，幂等演示立刻失效。
            String txnId = "DEMO-" + accountRef + "-" + day + "-" + (i + 1);

            lines.add(new BankStatementLine(
                    txnId,
                    accountRef,
                    to,
                    amount,
                    payer,
                    "DEMO-PAYER-" + (i + 1),
                    "振込 " + vin,
                    "DEMO|" + txnId + "|" + amount + "|" + vin));
        }
        return new BankStatementResult(lines, "DEMO-" + accountRef + "-" + day);
    }
}
