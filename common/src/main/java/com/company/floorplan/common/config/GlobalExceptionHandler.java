package com.company.floorplan.common.config;

import com.company.floorplan.common.dto.ApiResponse;
import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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
        String traceId = traceIdOf(request);
        log.warn("业务异常 code={} traceId={} msg={}", code.getCode(), traceId, ex.getMessage());
        ApiResponse<Void> body = ApiResponse.fail(code.getCode(), ex.getMessage(), traceId, ex.getDetails());
        return ResponseEntity.status(HttpStatus.valueOf(code.getHttpStatus())).body(body);
    }

    /**
     * 入参校验异常（如三源快照批量导入的 VIN / 来源校验）：归 400 VALID_001，
     * 不再落到 {@code handleUnknown} 冒泡成 500 SYS_001——客户端传错参数不是系统故障。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex,
                                                                   HttpServletRequest request) {
        String traceId = traceIdOf(request);
        log.warn("入参校验失败 traceId={} msg={}", traceId, ex.getMessage());
        ApiResponse<Void> body = ApiResponse.fail(
                ErrorCode.VALID_001.getCode(), ex.getMessage(), traceId, null);
        return ResponseEntity.status(HttpStatus.valueOf(ErrorCode.VALID_001.getHttpStatus())).body(body);
    }

    /**
     * Spring MVC 入参绑定异常（缺必填参数、类型不匹配、请求体不可解析）：客户端传参错误，
     * 归 400 VALID_001，不落到 {@code handleUnknown} 误报成 500 SYS_001。
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

    /**
     * 未知异常统一兜底。
     *
     * <p><b>不把 {@code ex.getMessage()} 回给客户端</b>：它可能含 SQL 片段、内部类名、
     * 文件路径、连接串等实现细节（安全基线，2026-11-24）。客户端只拿到
     * {@code SYS_001} 的固定文案与 traceId；完整异常栈进服务端日志，
     * 排障时按 traceId 检索。</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception ex, HttpServletRequest request) {
        String traceId = traceIdOf(request);
        log.error("系统异常 traceId={}", traceId, ex);
        ApiResponse<Void> body = ApiResponse.fail(
                ErrorCode.SYS_001.getCode(), ErrorCode.SYS_001.getMessage(), traceId, null);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /** 复用请求入口生成的 traceId；取不到时生成一个，保证响应体永远可报障。 */
    private static String traceIdOf(HttpServletRequest request) {
        Object existing = request.getAttribute("traceId");
        if (existing == null || "null".equals(String.valueOf(existing))) {
            return java.util.UUID.randomUUID().toString();
        }
        return String.valueOf(existing);
    }
}
