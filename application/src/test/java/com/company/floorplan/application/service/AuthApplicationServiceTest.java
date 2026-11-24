package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.AuthUser;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.domain.model.UserRole;
import com.company.floorplan.domain.repository.AuthUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证安全基线单测：JWT 版本撤销 + 每次请求权限回查。
 *
 * <p>不连库：用内存 Fake 仓储模拟 users / user_roles。测试的是
 * {@link AuthApplicationService} 的判定顺序与"以库内角色为准"的口径，
 * 端到端（退出登录后旧令牌 401、改角色后旧令牌权限立即变化）由冒烟覆盖。</p>
 */
class AuthApplicationServiceTest {

    private static final String SECRET = "unit-test-secret-key-with-at-least-32-bytes-123456";
    private static final String USERNAME = "sec-user";

    /** 内存仓储：足够表达 users 行、user_roles 行与 token_version。 */
    private static class FakeRepo implements AuthUserRepository {
        final Map<String, AuthUser> users = new HashMap<>();
        final Map<String, List<UserRole>> roles = new HashMap<>();

        @Override
        public Optional<AuthUser> findByUsername(String username) {
            return Optional.ofNullable(users.get(username));
        }

        @Override
        public List<UserRole> findRoles(String username) {
            return roles.getOrDefault(username, List.of());
        }

        @Override
        public int bumpTokenVersion(String username) {
            AuthUser user = users.get(username);
            if (user == null) {
                return 0;
            }
            user.setTokenVersion(user.getTokenVersion() + 1);
            return 1;
        }
    }

    private static AuthApplicationService serviceWith(FakeRepo repo) throws Exception {
        AuthApplicationService service = new AuthApplicationService(repo);
        setField(service, "tokenHours", 8);
        setField(service, "secret", SECRET);
        return service;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static AuthUser user(String password) {
        AuthUser u = new AuthUser();
        u.setUsername(USERNAME);
        u.setPasswordHash(new BCryptPasswordEncoder().encode(password));
        u.setDisplayName("安全测试用户");
        u.setRole("PURCHASE_OPERATOR");
        u.setEnabled(true);
        u.setTokenVersion(0);
        return u;
    }

    private static String login(AuthApplicationService service) {
        Map<String, Object> result = service.login(USERNAME, "pw123456");
        return String.valueOf(result.get("token"));
    }

    @Test
    void verify_usesCurrentDatabaseRoles_notTokenSnapshot() throws Exception {
        FakeRepo repo = new FakeRepo();
        repo.users.put(USERNAME, user("pw123456"));
        repo.roles.put(USERNAME, List.of(UserRole.PURCHASE_OPERATOR));
        AuthApplicationService service = serviceWith(repo);

        String token = login(service);
        AuthApplicationService.AuthContext before = service.verify(token);
        assertTrue(before.permissions().contains(Permission.PURCHASE_IMPORT),
                "登录时 PURCHASE_OPERATOR 应带 PURCHASE_IMPORT");

        // 管理员移除该用户的角色：令牌签名/版本都还没变，但权限必须立即消失
        repo.roles.put(USERNAME, List.of());
        AuthApplicationService.AuthContext after = service.verify(token);
        assertTrue(after.roles().isEmpty(), "角色移除后回查结果应为空");
        assertFalse(after.permissions().contains(Permission.PURCHASE_IMPORT),
                "不得继续使用令牌里 perms 快照（权限回查）");
    }

    @Test
    void verify_rejectsTokenAfterTokenVersionBumped() throws Exception {
        FakeRepo repo = new FakeRepo();
        repo.users.put(USERNAME, user("pw123456"));
        repo.roles.put(USERNAME, List.of(UserRole.PURCHASE_OPERATOR));
        AuthApplicationService service = serviceWith(repo);

        String token = login(service);
        service.verify(token); // 确认撤销前可用

        assertEquals(1, service.revokeAllTokens(USERNAME), "撤销应更新 1 行");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.verify(token));
        assertEquals(ErrorCode.AUTH_001, ex.getErrorCode(), "旧令牌必须因版本不等而失效");
    }

    @Test
    void verify_rejectsTokenWhenUserDisabled() throws Exception {
        FakeRepo repo = new FakeRepo();
        AuthUser u = user("pw123456");
        repo.users.put(USERNAME, u);
        repo.roles.put(USERNAME, List.of(UserRole.PURCHASE_OPERATOR));
        AuthApplicationService service = serviceWith(repo);

        String token = login(service);
        service.verify(token);

        u.setEnabled(false);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.verify(token));
        assertEquals(ErrorCode.AUTH_001, ex.getErrorCode(), "禁用账号后旧令牌必须立即失效");
    }

    @Test
    void login_rejectsWrongPasswordWithSameAuth004() throws Exception {
        FakeRepo repo = new FakeRepo();
        repo.users.put(USERNAME, user("pw123456"));
        repo.roles.put(USERNAME, List.of(UserRole.PURCHASE_OPERATOR));
        AuthApplicationService service = serviceWith(repo);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.login(USERNAME, "wrong-password"));
        assertEquals(ErrorCode.AUTH_004, ex.getErrorCode());
    }
}
