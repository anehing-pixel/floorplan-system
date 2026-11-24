package com.company.floorplan.web.config;

import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.web.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 安全基线（web 单体版）：未知异常不得把内部信息回给客户端。
 *
 * <p>web 有自己的增强版 {@link GlobalExceptionHandler}（多静态资源 404、请求过滤器 traceId），
 * 微服务版在 common；两处必须同口径，故两处都有本测试。</p>
 */
class GlobalExceptionHandlerTest {

    @Test
    void unknownException_responseContainsNoInternalMessage() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestLoggingFilter.ATTR_TRACE_ID, "trace-sec-web-1");

        RuntimeException internal = new RuntimeException(
                "org.postgresql.util.PSQLException: ERROR: relation \"secret_table\" does not exist "
                        + "password=hunter2");

        ResponseEntity<ApiResponse<Void>> resp = handler.handleUnknown(internal, request);

        assertEquals(500, resp.getStatusCode().value());
        ApiResponse<Void> body = resp.getBody();
        assertEquals(ErrorCode.SYS_001.getCode(), body.getCode());
        assertEquals(ErrorCode.SYS_001.getMessage(), body.getMessage(),
                "客户端只应拿到固定文案，不能拼接 ex.getMessage()");
        assertFalse(body.getMessage().contains("hunter2"), "不得泄露凭据");
        assertFalse(body.getMessage().contains("secret_table"), "不得泄露表名");
        assertFalse(body.getMessage().contains("PSQLException"), "不得泄露数据库异常类名");
        assertEquals("trace-sec-web-1", body.getTraceId());
    }
}
