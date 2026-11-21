package com.company.floorplan.domain.service;

/**
 * 支付<b>查单</b>结果（凭原指令号向渠道回查得到的终态）。
 *
 * <p>与 {@link PaymentResult} 的区别在于语义来源：{@code PaymentResult} 是
 * "我发起付款时渠道当场回了什么"，{@code QueryResult} 是"事后回查，渠道现在说这笔是什么状态"。
 * 异步支付链路里后者才是最终裁判——补偿作业靠它把 UNKNOWN 收敛成 PAID 或 FAILED。</p>
 *
 * @param state   回查到的状态
 * @param paidAt  渠道记录的出款时间（可为 null）
 * @param message 补充说明
 */
public record QueryResult(State state, java.time.Instant paidAt, String message) {

    /** 回查可能的状态。 */
    public enum State {
        /** 渠道确认已出款 —— 指令应补记为已支付。 */
        SUCCEEDED,
        /** 渠道确认未出款 —— 指令可置失败并释放额度。 */
        NOT_FOUND_OR_FAILED,
        /** 渠道仍不给终态 —— 保留指令，下轮再查。 */
        STILL_PROCESSING
    }

    public static QueryResult succeeded(java.time.Instant paidAt) {
        return new QueryResult(State.SUCCEEDED, paidAt, null);
    }

    public static QueryResult notFound() {
        return new QueryResult(State.NOT_FOUND_OR_FAILED, null, "渠道无此笔出款记录");
    }

    public static QueryResult stillProcessing() {
        return new QueryResult(State.STILL_PROCESSING, null, "渠道仍在处理中");
    }
}
