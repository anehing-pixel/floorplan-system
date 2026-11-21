package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.AuthUser;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.domain.model.UserRole;
import com.company.floorplan.domain.repository.AuthUserRepository;
import com.company.floorplan.domain.service.RoleSeparationPolicy;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.spec.SecretKeySpec;

/**
 * 认证应用服务：登录校验 + JWT 签发/验签。
 *
 * <p><b>安全要点</b>：</p>
 * <ul>
 *   <li>用户名不存在和密码错误返回<b>同一个</b>错误（AUTH_004），防攻击者用报错差异枚举有效用户名；</li>
 *   <li>密码校验用 BCrypt（数据库只存散列，校验是"用明文再算一遍散列比对"）；</li>
 *   <li>JWT 密钥优先读环境变量 {@code FLOORPLAN_JWT_SECRET}（生产必配），
 *       开发环境用兜底值——密钥泄露 = 任何人可伪造身份，绝不进 git 的生产值；</li>
 *   <li>令牌有效期默认 8 小时；JWT 无法主动作废，短有效期是主要止损手段。</li>
 * </ul>
 */
@Service
public class AuthApplicationService {

    /** 有效期（小时），可用环境变量覆盖 */
    @Value("${floorplan.auth.token-hours:8}")
    private int tokenHours;

    @Value("${floorplan.auth.secret:dev-only-secret-key-change-me-in-production-0123456789}")
    private String secret;

    private final AuthUserRepository userRepository;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthApplicationService(AuthUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 登录：校验用户名密码，成功签发 JWT。
     *
     * @return token / tokenType / expiresAt / displayName / roles / permissions
     */
    public Map<String, Object> login(String username, String password) {
        // 注意：用户不存在与密码错误走同一个 if 出口，报错完全一致（防枚举）
        AuthUser user = userRepository.findByUsername(username)
                .filter(u -> encoder.matches(password, u.getPasswordHash()))
                .filter(AuthUser::isEnabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_004, Map.of("username", username)));

        Instant now = Instant.now();
        Instant expires = now.plusSeconds(tokenHours * 3600L);

        // 角色集合 → 有效权限点（并集）。令牌里直接带权限点，
        // 拦截器就无需在每个请求上回查 user_roles。
        List<UserRole> roles = userRepository.findRoles(username);
        Set<Permission> permissions = RoleSeparationPolicy.effectivePermissions(new LinkedHashSet<>(roles));

        Map<String, Object> claims = new HashMap<>();
        claims.put("displayName", user.getDisplayName());
        claims.put("role", roles.isEmpty() ? user.getRole() : roles.get(0).name());
        claims.put("roles", roles.stream().map(UserRole::name).toList());
        claims.put("perms", permissions.stream().map(Permission::name).sorted().toList());
        // 角色中文名一并签发：前端顶栏徽章直接展示中文，
        // 不必在前端另存一份"代码→中文"的映射表（那份表会与后端枚举漂移）。
        claims.put("roleLabels", roles.stream().map(UserRole::getLabel).toList());

        String token = Jwts.builder()
                .subject(user.getUsername())                        // 载荷：登录名
                .claims(claims)                                     // 附加：显示名、角色、权限点
                .issuedAt(Date.from(now))
                .expiration(Date.from(expires))
                .signWith(signingKey())                             // 算法由密钥长度自动定（HS256）
                .compact();

        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("tokenType", "Bearer");
        result.put("expiresAt", expires.toString());
        result.put("displayName", user.getDisplayName());
        // 角色随登录响应下发：前端据此决定是否显示某个操作入口
        // （真正的拦截在后端 @RequirePermission，前端隐藏只是体验优化）
        result.put("role", roles.isEmpty() ? user.getRole() : roles.get(0).name());
        result.put("roles", roles.stream().map(UserRole::name).toList());
        result.put("roleLabels", roles.stream().map(UserRole::getLabel).toList());
        result.put("permissions", permissions.stream().map(Permission::name).sorted().toList());

        // 兼岗冲突提醒：一个人同时持有不相容角色时，在登录响应里回一条提示。
        // 不阻断登录（现实中一人多岗常见），但让管理员在后台能看见。
        List<String> conflicts = RoleSeparationPolicy.findConflicts(new LinkedHashSet<>(roles));
        if (!conflicts.isEmpty()) {
            result.put("roleConflicts", conflicts);
        }
        return result;
    }

    /**
     * 验签：令牌合法且未过期时返回载荷；否则抛 AUTH_001。
     * <p>由拦截器调用，验签通过后把 subject 放进请求属性供审计使用。</p>
     */
    public Claims verify(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            // 过期、签名不符、格式错误统一按未认证处理，不向外泄露细节
            throw new BusinessException(ErrorCode.AUTH_001, Map.of("reason", "BAD_TOKEN"));
        }
    }

    /** HMAC-SHA256 密钥：至少 256 位（32 字节），否则 jjwt 抛弱密钥异常 */
    private SecretKey signingKey() {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
