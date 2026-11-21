package com.company.floorplan.domain.repository;

import java.util.List;
import java.util.Optional;

import com.company.floorplan.domain.model.AuthUser;
import com.company.floorplan.domain.model.UserRole;

/**
 * 用户仓储接口（领域层声明"愿望"，实现归基础设施层）。
 *
 * <p>认证场景需要两个能力——
 * 按登录名找人（含其角色集合），以及按登录名取角色列表。
 * 方法签名本身就是契约，不需要读 SQL 就能理解系统能做什么。</p>
 */
public interface AuthUserRepository {

    Optional<AuthUser> findByUsername(String username);

    /**
     * 查询用户持有的全部角色。
     *
     * <p>一人多岗：一个账号可同时是"采购录入岗"和"增信业务岗"。
     * 角色集合决定其有效权限（各角色权限的并集）。</p>
     *
     * @param username 登录名
     * @return 角色列表；无角色时返回空列表
     */
    List<UserRole> findRoles(String username);
}
