package com.company.floorplan.domain.model;

/**
 * 登录用户（认证用，与业务角色分离）。
 *
 * <p>注意 {@code passwordHash} 是 BCrypt 散列（形如 {@code $2a$10$...}），
 * 永远不可能是明文——该字段只进不出：仓储读出来给认证服务比对，
 * 任何接口都不应把它序列化到响应里。</p>
 */
public class AuthUser {

    private Long id;
    private String username;
    private String passwordHash;
    private String displayName;
    private String role;
    private boolean enabled;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
