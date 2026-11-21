package com.company.floorplan.recon.rule;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 规则装配：R1-R4 为真实判定，R5-R8 为占位实现，顺序固定。
 *
 * <p>本类只装配 bean，不含业务判定；判定逻辑全在 rule 包的纯函数里。</p>
 */
@Configuration
public class ReconRuleConfig {

    @Bean
    public ReconRuleSet reconRuleSet() {
        return new ReconRuleSet(List.of(
                new R1AdvanceVsRepaymentRule(),
                new R2VehicleVsAdvanceRule(),
                new R3CreditVsAdvanceRule(),
                new R4BankVsRepaymentRule(),
                // TODO 以下 4 条为占位，规则细节待业务确认（见 PlaceholderReconRule）
                new PlaceholderReconRule(ReconRuleCode.R5),
                new PlaceholderReconRule(ReconRuleCode.R6),
                new PlaceholderReconRule(ReconRuleCode.R7),
                new PlaceholderReconRule(ReconRuleCode.R8)));
    }
}
