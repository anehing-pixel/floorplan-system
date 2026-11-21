package com.company.floorplan.domain.exception;

/**
 * 银行流水拉取失败。
 *
 * <p>单独一个异常类型，而不是复用 {@link BusinessException}，
 * 因为它的处置方式完全不同：业务异常意味着"这次请求不合法，改一下再来"，
 * 而采集失败意味着"外部系统出了问题，需要重试或人工介入"。
 * 前者不该触发告警，后者必须触发告警——混成一个类型，值班就分不清该不该起床。</p>
 */
public class BankStatementException extends RuntimeException {

    private final String source;
    private final String accountRef;

    public BankStatementException(String source, String accountRef, String message, Throwable cause) {
        super("[" + source + "/" + accountRef + "] " + message, cause);
        this.source = source;
        this.accountRef = accountRef;
    }

    public String getSource() {
        return source;
    }

    public String getAccountRef() {
        return accountRef;
    }
}
