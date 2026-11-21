package com.company.floorplan.web.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 请求参数流水日志：把每个 API 调用的入参记到运行日志里。
 *
 * <p><b>为什么需要它</b>：Tomcat 的 access_log 只能记 URL（含 query string），
 * 变量表里<b>没有任何</b>能取到 request body 的字段。POST/PUT 的业务参数都在 body 里，
 * 光看 access_log 等于"只看到了门牌号，没看到来意"。要记 body 必须在应用侧包装请求流。</p>
 *
 * <p><b>三重防护（缺一不可，本类的存在前提）</b>：</p>
 * <ol>
 *   <li><b>接口白名单</b>（{@code skip-body-paths}）：命中即<b>不包装请求流</b>，body 一个字节都不进缓存。
 *       默认含 {@code /api/auth/login} —— 前端登录把用户名密码放在 body 明文传输，
 *       一旦落日志，密码会被按天滚动保留 30 天，而日志文件的读权限通常远宽于数据库。</li>
 *   <li><b>字段级脱敏</b>（{@code sensitive-fields}）：body 里匹配到的敏感字段，值替换为 {@code ***}，
 *       保留字段名。作用是"能证明传过这个字段，但内容未留存"。</li>
 *   <li><b>长度截断</b>（{@code max-body-chars}）：防止一个批量收车请求（几百台车）把日志撑爆。</li>
 * </ol>
 *
 * <p><b>噪音控制</b>：只记 {@code /api/} 前缀的请求。前端一次页面刷新会拉
 * {@code /js/app.js}、{@code /css/console.css} 等静态资源，全记的话有效日志会被淹没。</p>
 *
 * <p><b>traceId 贯穿</b>：请求进入即生成 traceId，放进请求属性
 * {@value #ATTR_TRACE_ID}、写入响应头 {@value #HEADER_TRACE_ID}，并被
 * {@link GlobalExceptionHandler} 复用。于是同一个请求的两行日志能对上：
 * <pre>
 * INFO  REQ POST /api/advances?... actor=admin traceId=9f2c... body={"vin":"TESTVIN001","invoiceAmount":1850000}
 * WARN  业务异常 code=ADVANCE_001 traceId=9f2c... msg=额度不足
 * </pre>
 * 用户报错时给他响应头里的 X-Trace-Id，就能一条 grep 同时捞出入参和失败原因。</p>
 *
 * <p><b>顺序</b>：用 {@code HIGHEST_PRECEDENCE + 10} 尽量靠前，
 * 这样统计到的耗时覆盖认证拦截器与业务处理的总时长。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    /** 请求属性名：本次调用的追踪号，与 {@link GlobalExceptionHandler} 共用。 */
    public static final String ATTR_TRACE_ID = "traceId";
    /** 响应头名：前端可从 Network 面板直接复制，用于报障。 */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /** 只记 API，静态资源与页面本身不记。 */
    private static final String API_PREFIX = "/api/";

    /** 值可能是字符串、数字或布尔，三种都要能替换掉。 */
    private static final String VALUE_GROUP = "(\"(?:[^\"\\\\]|\\\\.)*\"|-?[0-9][0-9.eE+-]*|true|false|null)";

    private final boolean enabled;
    private final int maxBodyChars;
    private final List<String> skipBodyPaths;
    private final Pattern sensitivePattern;

    public RequestLoggingFilter(
            @Value("${floorplan.request-log.enabled:true}") boolean enabled,
            @Value("${floorplan.request-log.max-body-chars:2000}") int maxBodyChars,
            @Value("${floorplan.request-log.skip-body-paths:/api/auth/login}") String skipBodyPaths,
            @Value("${floorplan.request-log.sensitive-fields:password,passwd,secret,token,authorization,credential,apiKey,api_key,refreshToken,idToken}")
            String sensitiveFields) {
        this.enabled = enabled;
        this.maxBodyChars = Math.max(0, maxBodyChars);
        this.skipBodyPaths = split(skipBodyPaths);
        if (sensitiveFields == null || sensitiveFields.isBlank()) {
            this.sensitivePattern = null;
        } else {
            String names = String.join("|", split(sensitiveFields).stream()
                    .map(String::toLowerCase).toList());
            // 字段名可能带层级前缀（如 "user.password"），故允许引号内前缀与后缀
            this.sensitivePattern = Pattern.compile(
                    "(?i)(\"[^\"]*(?:" + names + ")[^\"]*\"\\s*:\\s*)" + VALUE_GROUP);
        }
    }

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        for (String s : Arrays.asList(csv.split(","))) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** 关闭开关、或非 API 请求，直接不进本过滤器。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !request.getRequestURI().startsWith(API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString();
        request.setAttribute(ATTR_TRACE_ID, traceId);
        // 响应头对跨域默认不可读，显式暴露，前端 fetch 才拿得到
        response.setHeader(HEADER_TRACE_ID, traceId);
        response.addHeader("Access-Control-Expose-Headers", HEADER_TRACE_ID);

        String uri = request.getRequestURI();
        boolean bodyPolicySkip = isSkipBody(uri);

        // 白名单接口不包装：请求流保持原样，body 从不进入本进程的内存缓存
        HttpServletRequest downstream = bodyPolicySkip ? request : new ContentCachingRequestWrapper(request);

        long start = System.nanoTime();
        try {
            chain.doFilter(downstream, response);
        } finally {
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            log.info("REQ {} {}{} -> {} {}ms actor={} traceId={} body={}",
                    request.getMethod(),
                    uri,
                    query(request),
                    response.getStatus(),
                    costMs,
                    actor(request),
                    traceId,
                    bodyPolicySkip ? "[REDACTED-接口在白名单内]" : extractBody(downstream));
        }
    }

    /** 精确匹配或前缀匹配（兼容带路径变量的写法）。 */
    private boolean isSkipBody(String uri) {
        for (String p : skipBodyPaths) {
            if (uri.equals(p) || uri.startsWith(p + "/")) {
                return true;
            }
        }
        return false;
    }

    private static String query(HttpServletRequest request) {
        String qs = request.getQueryString();
        return qs == null ? "" : "?" + qs;
    }

    /**
     * 操作人身份。
     *
     * <p>先读 {@code requestLogActor}（{@link JwtAuthInterceptor} 验签通过后回填），
     * 再回退 {@code authUsername}，两者都没有说明是未认证请求，记 anon。
     * 过滤器早于拦截器执行，但日志写在 {@code chain.doFilter} 之后的 finally 块里，
     * 所以此时一定能读到拦截器放进的值。</p>
     */
    private static String actor(HttpServletRequest request) {
        Object u = request.getAttribute("requestLogActor");
        if (u == null) {
            u = request.getAttribute("authUsername");
        }
        return u == null ? "anon" : String.valueOf(u);
    }

    private String extractBody(HttpServletRequest request) {
        if (!(request instanceof ContentCachingRequestWrapper wrapper)) {
            return "-";
        }
        byte[] raw = wrapper.getContentAsByteArray();
        if (raw.length == 0) {
            return "-";   // GET/DELETE 等无 body，或请求体为空
        }
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            return "[文件上传，内容不记录]";
        }
        Charset cs = request.getCharacterEncoding() == null
                ? StandardCharsets.UTF_8
                : Charset.forName(request.getCharacterEncoding());
        String text = new String(raw, cs);
        return sanitize(text, raw.length);
    }

    /** 先脱敏再截断：顺序不能反，否则截断可能切在敏感值中间导致替换失效。 */
    private String sanitize(String text, int bytes) {
        String out = text.replace('\r', ' ').replace('\n', ' ')   // 保证一行一条，方便 grep
                .replace('\t', ' ');
        if (sensitivePattern != null) {
            out = sensitivePattern.matcher(out).replaceAll("$1\"***\"");
        }
        if (out.length() > maxBodyChars) {
            out = out.substring(0, maxBodyChars) + "...[截断，共 " + out.length() + " 字符]";
        }
        if (bytes > out.length()) {
            log.debug("请求体字节数 {} 大于记录长度 {}", bytes, out.length());
        }
        return out;
    }
}
