package com.company.floorplan.recon.rule;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 规则装配：8 条全部为真实判定，顺序固定为 R1..R8。
 *
 * <p>本类只装配 bean，不含业务判定；判定逻辑全在 rule 包的纯函数里。
 * R5–R8 共用 {@link ExactValueReconRule} 的精确相等判定，左右口径标签在此逐条写清，
 * 便于差异报告自证口径。</p>
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
                new ExactValueReconRule(ReconRuleCode.R5,
                        "台账累计计提利息", "计息流水累计(主表+归档)"),
                new ExactValueReconRule(ReconRuleCode.R6,
                        "V10 有效登记(1=有)", "担保台账 REGISTERED(1=是)"),
                new ExactValueReconRule(ReconRuleCode.R7,
                        "递减计划已付期数", "递减核销流水笔数"),
                new ExactValueReconRule(ReconRuleCode.R8,
                        "处置认定损失", "借据 WRITTEN_OFF 未偿敞口")));
    }
}
