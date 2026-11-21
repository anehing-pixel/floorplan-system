package com.company.floorplan.web.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注某个接口仅管理员（role=ADMIN）可调用。
 *
 * <p><b>为什么用注解而不是在拦截器里写路径白名单</b>：
 * 路径匹配会随着接口增加而越来越难维护——改个 URL 就可能漏掉鉴权。
 * 注解把"谁才能调"写在接口自己身上，改接口时鉴权跟着走，
 * 也不存在新增接口忘记加保护的问题（编译期看不到，但评审可见）。</p>
 *
 * <p><b>它拦的是"写"操作</b>：新增、修改、删除、启停这类会改变主数据或台账的动作。
 * 查询类接口对所有已登录用户开放——风控岗、运营岗都需要看经销商档案和车辆台账。</p>
 *
 * @see com.company.floorplan.web.config.JwtAuthInterceptor 实际执行鉴权的位置
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAdmin {
}
