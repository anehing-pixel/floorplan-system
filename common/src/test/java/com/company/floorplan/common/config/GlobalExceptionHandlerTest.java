package com.company.floorplan.common.config;

import com.company.floorplan.common.dto.ApiResponse;
import com.company.floorplan.domain.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 安全基线：未知异常不得把内部信息（SQL/路径/连接串/类名）回给客户端。
 */
class GlobalExceptionHandlerTest {

    @Test
    void unknownException_responseContainsNoInternalMessage() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("traceId", "trace-sec-common-1");

        RuntimeException internal = new RuntimeException(
                "jdbc:postgresql://secret-db:5434/floorplan password=hunter2 at com.company.SecretDao");

        ResponseEntity<ApiResponse<Void>> resp = handler.handleUnknown(internal, request);

        assertEquals(500, resp.getStatusCode().value());
        ApiResponse<Void> body = resp.getBody();
        assertEquals(ErrorCode.SYS_001.getCode(), body.getCode());
        assertEquals(ErrorCode.SYS_001.getMessage(), body.getMessage(),
                "客户端只应拿到固定文案，不能拼接 ex.getMessage()");
        assertFalse(body.getMessage().contains("hunter2"), "不得泄露凭据");
        assertFalse(body.getMessage().contains("secret-db"), "不得泄露连接串");
        assertFalse(body.getMessage().contains("SecretDao"), "不得泄露内部类名");
        assertEquals("trace-sec-common-1", body.getTraceId(),
                "响应 traceId 必须复用请求入口的号，才能与服务端日志对上");
    }
}
