package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.AuthUser;
import com.company.floorplan.domain.model.UserRole;
import com.company.floorplan.domain.repository.AuthUserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 用户仓储 JDBC 实现。
 *
 * <p>与 {@link JdbcDealerRepository} 同一模板：RowMapper 把一行记录转成领域对象，
 * 查不到时返回空盒子（Optional），由调用方决定"没有的时候怎么办"。</p>
 */
@Repository
public class JdbcAuthUserRepository implements AuthUserRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<AuthUser> MAPPER = (rs, i) -> {
        AuthUser u = new AuthUser();
        u.setId(rs.getLong("id"));
        u.setUsername(rs.getString("username"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setDisplayName(rs.getString("display_name"));
        u.setRole(rs.getString("role"));
        u.setEnabled(rs.getBoolean("enabled"));
        return u;
    };

    public JdbcAuthUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AuthUser> findByUsername(String username) {
        return jdbc.query("SELECT * FROM users WHERE username = ?", MAPPER, username).stream().findFirst();
    }

    /**
     * 查询角色列表，主角色排在前（保证登录响应的第一个角色是主角色）。
     *
     * <p>遇到库里存在但枚举里没有的角色代码（枚举删改后遗留）时<b>跳过</b>而非抛异常——
     * 认证不能因为一个脏数据把整个系统锁死；该角色本就无权限，跳过等于不放行，
     * 是安全的降级方向。</p>
     */
    @Override
    public List<UserRole> findRoles(String username) {
        List<String> codes = jdbc.query(
                "SELECT role_code FROM user_roles WHERE username = ? ORDER BY is_primary DESC, id",
                (rs, i) -> rs.getString("role_code"), username);
        List<UserRole> roles = new ArrayList<>();
        for (String code : codes) {
            try {
                roles.add(UserRole.valueOf(code));
            } catch (IllegalArgumentException ignored) {
                // 未知角色代码：跳过（无权限即安全侧）
            }
        }
        return roles;
    }
}
