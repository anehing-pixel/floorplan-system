package com.company.floorplan.common.config;

import com.company.floorplan.common.dto.ApiResponse;
import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理（精简版，全服务共用）：业务异常映射错误码，未知异常归 SYS_001。
 * 不依赖 web 的 RequestLoggingFilter，traceId 取不到就自行生成。
 *
 * <p>web 单体仍用自己的增强版（多 NoResourceFoundException 处理 + RequestLoggingFilter.traceId），
 * 待网关接管、旧单体退场后再统一到这里。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex, HttpServletRequest request) {
        ErrorCode code = ex.getErrorCode();
        String traceId = String.valueOf(request.getAttribute("traceId"));
        if ("null".equals(traceId)) {
            traceId = java.util.UUID.randomUUID().toString();
        }
        log.warn("业务异常 code={} traceId={} msg={}", code.getCode(), traceId, ex.getMessage());
        ApiResponse<Void> body = ApiResponse.fail(code.getCode(), ex.getMessage(), traceId, ex.getDetails());
        return ResponseEntity.status(HttpStatus.valueOf(code.getHttpStatus())).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception ex, HttpServletRequest request) {
        String traceId = java.util.UUID.randomUUID().toString();
        log.error("系统异常 traceId={}", traceId, ex);
        ApiResponse<Void> body = ApiResponse.fail(
                ErrorCode.SYS_001.getCode(), ErrorCode.SYS_001.getMessage() + "：" + ex.getMessage(), traceId, null);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
