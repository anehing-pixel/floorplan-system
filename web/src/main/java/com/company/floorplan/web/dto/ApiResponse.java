package com.company.floorplan.web.dto;

import java.util.Map;

/**
 * 统一响应结构：{ code, message, data, traceId, details? }
 * 对齐《库存融资核心系统-接口文档.md》第 1 节。
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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details;
    }
}
