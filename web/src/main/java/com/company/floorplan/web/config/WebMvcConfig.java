package com.company.floorplan.web.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 装配：注册 JWT 拦截器。
 *
 * <p><b>关键约定</b>：只拦 {@code /api/**}。
 * 千万不要写成 {@code /**}——那样静态页面（login.html）也会被拦，
 * 浏览器打开登录页直接 401，连表单都看不到。
 * 静态资源不在 /api/** 内，天然放行，无需额外配置。</p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtAuthInterceptor jwtAuthInterceptor;

    public WebMvcConfig(JwtAuthInterceptor jwtAuthInterceptor) {
        this.jwtAuthInterceptor = jwtAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns("/api/**")                  // 只拦 API
                .excludePathPatterns("/api/auth/login")      // 登录接口例外
                // 银行回调端点：不走用户 JWT 鉴权，改由 HMAC 签名校验（PaymentWebhookVerifier）。
                // 若不放行，银行没有用户令牌会被拦截器直接 401，永远到不了业务代码。
                .excludePathPatterns("/api/advances/*/confirm", "/api/advances/*/fail");
    }

    /**
     * 开发期跨域放行：页面可能从预览面板（独立端口）或 file:// 打开，
     * 此时浏览器会发 CORS 预检，后端必须应答 Access-Control 头才能通。
     * <b>生产部署应收紧为具体域名</b>（页面与 API 同源部署时本配置不会生效——同源请求不走 CORS）。
     * 注意本项目用 Bearer 头而非 Cookie，跨域请求不带凭证，通配源的风险可控。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
