package com.company.floorplan.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注某个接口仅管理员可调用（兼容层）。
 * JwtAuthInterceptor 引用了它，故与拦截器一同放在 common，保持鉴权链代码完整。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAdmin {
}
