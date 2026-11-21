package com.company.floorplan.domain.service;

/**
 * 支付指令（ADR-007 决策一：打款对象必须落在指令里）。
 *
 * <p><b>为什么必须带上收款方</b>：改造前的接缝是
 * {@code pay(vin, amount, orderNo)} —— <b>签名里根本没有收款方</b>，
 * 注释写着"向厂家付款"，但传给渠道的只有一台车和一个金额。
 * 直付上游在代码里<b>没有落点</b>：钱打给谁，指令答不上来。</p>
 *
 * <p>现在收款方成为指令的一等公民：上游类型、上游 ID、账户引用码都在指令里，
 * 渠道实现必须据此确定受款人。冻结的账户快照仍留在垫款单上——
 * <b>指令带引用（去哪儿找账户）、快照留事实（当时是哪个账户）</b>，两者不是重复。</p>
 */
public class PaymentInstruction {

    /** 车架号。 */
    private final String vin;
    /** 付款金额（整数日元）。 */
    private final long amount;
    /** 业务指令号（幂等键）。 */
    private final String orderNo;
    /** 收款方类型：上游 / 经销商自身。 */
    private final String payeeType;
    /** 收款上游 ID（DEALER_SELF 时为 null）。 */
    private final Long payeeSupplierId;
    /** 收款账户引用码（只传引用，不传明文账号）。 */
    private final String payeeAccountRef;

    public PaymentInstruction(String vin, long amount, String orderNo,
                               String payeeType, Long payeeSupplierId, String payeeAccountRef) {
        this.vin = vin;
        this.amount = amount;
        this.orderNo = orderNo;
        this.payeeType = payeeType;
        this.payeeSupplierId = payeeSupplierId;
        this.payeeAccountRef = payeeAccountRef;
    }

    public String getVin() { return vin; }

    public long getAmount() { return amount; }

    public String getOrderNo() { return orderNo; }

    public String getPayeeType() { return payeeType; }

    public Long getPayeeSupplierId() { return payeeSupplierId; }

    public String getPayeeAccountRef() { return payeeAccountRef; }

    /**
     * 收款方摘要（用于日志与渠道报文，<b>不含明文账号</b>）。
     *
     * @return 收款方描述
     */
    @Override
    public String toString() {
        return "PaymentInstruction{vin=" + vin + ", amount=" + amount + ", orderNo=" + orderNo
                + ", payeeType=" + payeeType + ", payeeSupplierId=" + payeeSupplierId
                + ", payeeAccountRef=" + payeeAccountRef + "}";
    }
}
