package com.company.floorplan.web.config;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.web.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 全局异常处理：领域/业务异常映射为错误码，未知异常归为 SYS_001。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 取本次请求的追踪号。
     *
     * <p>优先复用 {@link RequestLoggingFilter} 在请求入口生成的 traceId，
     * 使"入参流水"（INFO REQ 行）与"失败原因"（本类 WARN/ERROR 行）共享同一个号，
     * 一次 grep 就能把两者串起来。取不到时（过滤器关闭、或非 API 请求）自己生成一个，
     * 保证响应体里永远有 traceId 可供报障。</p>
     */
    private static String traceIdOf(HttpServletRequest request) {
        Object existing = request.getAttribute(RequestLoggingFilter.ATTR_TRACE_ID);
        return existing == null ? java.util.UUID.randomUUID().toString() : String.valueOf(existing);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex, HttpServletRequest request) {
        ErrorCode code = ex.getErrorCode();
        String traceId = traceIdOf(request);
        log.warn("业务异常 code={} traceId={} msg={}", code.getCode(), traceId, ex.getMessage());
        ApiResponse<Void> body = ApiResponse.fail(code.getCode(), ex.getMessage(), traceId, ex.getDetails());
        return ResponseEntity.status(HttpStatus.valueOf(code.getHttpStatus())).body(body);
    }

    /**
     * 静态资源不存在（如浏览器自动请求 favicon.ico）：返回 404 而非走兜底 500。
     * Spring Boot 3.2 起缺失静态资源抛 NoResourceFoundException，
     * 若不单独处理会被下方 Exception 兜底误报成 SYS_001 系统错误。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        ApiResponse<Void> body = ApiResponse.fail(
                ErrorCode.NOT_FOUND_001.getCode(), "资源不存在", traceIdOf(request), null);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * Spring MVC 入参绑定异常（缺必填查询参数、参数类型不匹配、请求体不可解析）：
     * 属于客户端传错参数，归 400 VALID_001，不落到 {@code handleUnknown} 误报成 500 SYS_001。
     * 例：{@code GET /api/vehicles} 未带必填的 {@code dealerId}。
     */
    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception ex, HttpServletRequest request) {
        String traceId = traceIdOf(request);
        log.warn("请求参数错误 traceId={} msg={}", traceId, ex.getMessage());
        ApiResponse<Void> body = ApiResponse.fail(
                ErrorCode.VALID_001.getCode(), "请求参数错误：" + ex.getMessage(), traceId, null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception ex, HttpServletRequest request) {
        String traceId = traceIdOf(request);
        log.error("系统异常 traceId={}", traceId, ex);
        ApiResponse<Void> body = ApiResponse.fail(
                ErrorCode.SYS_001.getCode(), ErrorCode.SYS_001.getMessage() + "：" + ex.getMessage(), traceId, null);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
