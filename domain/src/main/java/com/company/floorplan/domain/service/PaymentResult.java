package com.company.floorplan.domain.service;

/**
 * 支付指令的<b>受理结果</b>（同步返回的那一次）。
 *
 * <p><b>三态而不是两态</b>：真实渠道除了"成功""失败"，还有一类必须显式表达的
 * {@link State#UNKNOWN} —— 请求已经发出去、但没拿到确定的终态（超时、连接中断、
 * 渠道只回受理号未回结果）。它既不是成功也不是失败，<b>钱可能已经划走</b>。</p>
 *
 * <p>把 UNKNOWN 建成独立状态的原因是：调用方对"失败"的处理是<b>回滚并释放额度</b>，
 * 对"结果未知"的处理必须是<b>保留待查单、绝不释放额度</b>。
 * 两者混为一谈就会出现"释放了额度、钱其实付出去了"的双花事故。</p>
 */
public final class PaymentResult {

    /** 受理结果状态。 */
    public enum State {
        /** 渠道明确回复出款成功。 */
        SUCCEEDED,
        /** 渠道明确回复未出款（余额不足、账户冻结等）。 */
        FAILED,
        /** 渠道未给出终态，必须靠 {@code PaymentGateway#query} 收敛。 */
        UNKNOWN
    }

    private final State state;
    private final String reference;
    private final String message;

    private PaymentResult(State state, String reference, String message) {
        this.state = state;
        this.reference = reference;
        this.message = message;
    }

    /** 出款成功，携带渠道流水号。 */
    public static PaymentResult ok(String reference) {
        return new PaymentResult(State.SUCCEEDED, reference, null);
    }

    /** 渠道明确回复未出款。 */
    public static PaymentResult fail(String message) {
        return new PaymentResult(State.FAILED, null, message);
    }

    /**
     * 结果未知。
     *
     * @param channelReference 渠道受理号（可为 null）；有值时必须落库，
     *                         否则将来凭原指令号查不到单
     */
    public static PaymentResult unknown(String channelReference, String message) {
        return new PaymentResult(State.UNKNOWN, channelReference, message);
    }

    public boolean isSuccess() {
        return state == State.SUCCEEDED;
    }

    public boolean isFailed() {
        return state == State.FAILED;
    }

    /** 结果未知：调用方应保留指令待收敛，不能按失败回滚。 */
    public boolean isUnknown() {
        return state == State.UNKNOWN;
    }

    public State state() {
        return state;
    }

    public String getReference() {
        return reference;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return state + (reference == null ? "" : "/" + reference);
    }
}
