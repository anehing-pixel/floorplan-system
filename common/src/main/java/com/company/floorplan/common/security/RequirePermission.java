package com.company.floorplan.common.security;

import com.company.floorplan.domain.model.Permission;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注某个接口需要哪些权限点才能调用。
 * 由 common 统一提供，各服务共享；持有者需具备所列全部权限点才放行。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    Permission[] value();
}
