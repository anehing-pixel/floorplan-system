package com.company.floorplan.web.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.company.floorplan.domain.model.Permission;

/**
 * 标注某个接口需要哪些权限点才能调用。
 *
 * <p><b>为什么用注解而不是路径白名单</b>：
 * 路径匹配会随着接口增加而越来越难维护——改个 URL 就可能漏掉鉴权。
 * 注解把"谁才能调"写在接口自己身上，改接口时鉴权跟着走，
 * 也不存在新增接口忘记加保护的问题（编译期看不到，但评审可见）。</p>
 *
 * <p><b>与 {@link RequireAdmin} 的关系</b>：{@code RequireAdmin} 是早期粗粒度方案
 * （只有管理员能写），随着角色细分已不足以表达业务分工——
 * "录入岗能导入但不能审核""出纳岗能打款但不能申请"这类约束无法用二值判断表达。
 * 本注解取代它；{@code RequireAdmin} 保留兼容，但语义已映射为
 * "系统管理域的写操作"，由 {@code @RequirePermission} 承担真正的业务授权。</p>
 *
 * <p><b>判定语义</b>：用户持有<b>任意一个</b>角色、且该角色拥有所列<b>全部</b>权限点，
 * 即放行。角色之间权限是并集关系，因此"多角色合起来才够"这种设计不被支持——
 * 那会让权限推理变得不可读。要表达"两个岗协同才能做"，
 * 正确做法是拆成两个接口，各自校验自己那一半。</p>
 *
 * <p><b>它拦的是"写"操作</b>：查询类接口不标此注解，对所有已登录用户开放
 * ——风控岗、运营岗都需要看经销商档案和车辆台账。</p>
 *
 * @see com.company.floorplan.web.config.JwtAuthInterceptor 实际执行鉴权的位置
 * @see com.company.floorplan.domain.model.UserRole 角色与权限点的映射
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /**
     * 需要的权限点。多个权限点是<b>且</b>关系（全部具备才放行）。
     *
     * @return 权限点数组
     */
    Permission[] value();
}
