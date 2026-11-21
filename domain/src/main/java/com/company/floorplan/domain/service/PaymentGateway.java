package com.company.floorplan.domain.service;

/**
 * 支付网关（对接银行/支付通道的接缝）。
 * 实现由基础设施/集成层提供，领域层只依赖接口——隔离外部变更。
 *
 * <p><b>真实渠道的两条硬性要求</b>（换实现前必须确认渠道支持，否则异步方案不成立）：</p>
 * <ol>
 *   <li><b>指令号幂等</b>：同一 {@code orderNo} 重复提交不得重复出款。
 *       异步链路的超时重试与补偿作业都靠它兜底；</li>
 *   <li><b>可查单</b>：{@link #query(String)} 能凭指令号回查到终态。
 *       没有查单能力，"结果未知"的指令就永远闭不了环。</li>
 * </ol>
 *
 * <p><b>ADR-007 变更</b>：付款指令改为携带收款方（{@link PaymentInstruction}）。
 * 改造前的签名 {@code pay(vin, amount, orderNo)} 里没有收款方，
 * 直付上游在代码里没有落点——钱打给谁，指令答不上来。</p>
 */
public interface PaymentGateway {

    /**
     * 向厂家（上游）付款。金额单位为整数日元。
     *
     * <p><b>返回三态</b>：成功、明确失败、以及<b>结果未知</b>。
     * 实现方不得把超时/异常"顺手"转成 {@code fail}——那会让调用方误判为未出款而释放额度。
     * 拿不到终态时必须返回 {@link PaymentResult#unknown}。</p>
     *
     * @param instruction 支付指令（含车架号、金额、幂等指令号与<b>收款方</b>）
     * @return 受理结果
     */
    PaymentResult pay(PaymentInstruction instruction);

    /**
     * 凭指令号回查支付终态。
     *
     * <p>供补偿作业调用：把 {@code UNKNOWN} 的指令收敛为
     * 已支付（补记 PAID）或未出款（置 FAILED 并释放额度）。</p>
     *
     * @param orderNo 发起 {@link #pay} 时使用的指令号
     * @return 回查结果；查不到记录视为未出款
     */
    QueryResult query(String orderNo);
}
