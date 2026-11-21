package com.company.floorplan.common.dto;

import java.util.Map;

/**
 * 统一响应结构：{ code, message, data, traceId, details? }。
 *
 * <p>原先在 web/auth/bank/credit/risk 各有一份，抽到 common 后全服务共用一份，
 * 消除多份同步成本。JSON 结构与字段顺序保持不变，调用方无感。</p>
 */
public class ApiResponse<T> {

    private String code;
    private String message;
    private T data;
    private String traceId;
    private Map<String, Object> details;

    public ApiResponse() {
    }

    public static <T> ApiResponse<T> ok(T data, String traceId) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = "OK";
        r.message = "OK";
        r.data = data;
        r.traceId = traceId;
        return r;
    }

    public static <T> ApiResponse<T> fail(String code, String message, String traceId, Map<String, Object> details) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = code;
        r.message = message;
        r.traceId = traceId;
        r.details = details;
        return r;
    }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> details) { this.details = details; }
}
