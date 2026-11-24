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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 认证应用服务：登录校验 + JWT 签发/验签 + 撤销与权限回查。
 *
 * <p><b>安全要点</b>：</p>
 * <ul>
 *   <li>用户名不存在和密码错误返回<b>同一个</b>错误（AUTH_004），防攻击者用报错差异枚举有效用户名；</li>
 *   <li>密码校验用 BCrypt（数据库只存散列，校验是"用明文再算一遍散列比对"）；</li>
 *   <li>JWT 密钥优先读环境变量 {@code FLOORPLAN_JWT_SECRET}（生产必配），
 *       开发环境用兜底值——密钥泄露 = 任何人可伪造身份，绝不进 git 的生产值；</li>
 *   <li><b>令牌可撤销</b>（2026-11-24）：登录时把 {@code users.token_version} 写进 {@code ver} 声明；
 *       每次请求验签后回查该用户当前版本，不相等即拒绝。退出登录/强制下线 = 版本 +1，
 *       该账号所有已签发令牌立即失效；</li>
 *   <li><b>权限回查</b>（2026-11-24）：每次请求从 {@code user_roles} 重新计算有效权限，
 *       不再信任令牌里签发时的 {@code perms}——角色被调整后旧令牌的权限立即跟着变；</li>
 *   <li>账号被禁用（{@code enabled=false}）时，旧令牌在下一次请求即失效。</li>
 * </ul>
 *
 * <p><b>兼容旧令牌</b>：变更前签发的令牌没有 {@code ver} 声明，部署后一律失效（需重新登录）。
 * 这是刻意的安全默认值，不做"缺版本号就放行"的降级。</p>
 */
@Service
public class AuthApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AuthApplicationService.class);

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
     * 验签通过后的身份上下文。
     *
     * <p>拦截器只应使用本对象里的角色/权限，不要再从 {@link Claims} 里读 {@code roles}/{@code perms}——
     * 那两份是登录时的快照，而本对象的角色/权限是本次请求从库里现查的。</p>
     */
    public record AuthContext(String username, String primaryRole,
                              Set<UserRole> roles, Set<Permission> permissions) {
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

        List<UserRole> roles = resolveRoles(user);
        Set<Permission> permissions = RoleSeparationPolicy.effectivePermissions(new LinkedHashSet<>(roles));
        String primaryRole = primaryRoleOf(roles, user);

        Map<String, Object> claims = new HashMap<>();
        claims.put("displayName", user.getDisplayName());
        claims.put("role", primaryRole);
        claims.put("roles", roles.stream().map(UserRole::name).toList());
        claims.put("perms", permissions.stream().map(Permission::name).sorted().toList());
        // 角色中文名一并签发：前端顶栏徽章直接展示中文，
        // 不必在前端另存一份"代码→中文"的映射表（那份表会与后端枚举漂移）。
        claims.put("roleLabels", roles.stream().map(UserRole::getLabel).toList());
        // 撤销版本号（V82）：每次请求验签后回查，不等即失效。
        // 不含本声明的旧令牌在 verify 里按 -1 处理 -> 直接失效（强制重新登录）。
        claims.put("ver", user.getTokenVersion());

        String token = Jwts.builder()
                .subject(user.getUsername())                        // 载荷：登录名
                .claims(claims)                                     // 附加：显示名、角色、权限点、版本号
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
        // （真正的拦截在后端 @RequirePermission + 每次请求的权限回查）
        result.put("role", primaryRole);
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
     * 验签 + 撤销/禁用/权限回查。
     *
     * <p>顺序刻意如此：先验签名与过期（廉价、不碰库），再回查用户与版本，
     * 最后从 {@code user_roles} 现算角色与权限。任何一步不通过都抛 AUTH_001；
     * 对外统一 {@code BAD_TOKEN}，不区分"用户禁用 / 令牌撤销 / 签名错误"，
     * 避免把账号状态泄露给调用方。精确原因只进服务端 debug 日志。</p>
     */
    public AuthContext verify(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            throw badToken("BAD_TOKEN", null);
        }

        String username = claims.getSubject();
        if (username == null || username.isBlank()) {
            throw badToken("NO_SUBJECT", null);
        }

        AuthUser user = userRepository.findByUsername(username)
                .filter(AuthUser::isEnabled)
                .orElseThrow(() -> badToken("USER_DISABLED_OR_MISSING", username));

        Object rawVersion = claims.get("ver");
        int tokenVersion = rawVersion instanceof Number n ? n.intValue() : -1;
        if (tokenVersion != user.getTokenVersion()) {
            throw badToken("TOKEN_REVOKED", username);
        }

        // 不再信任令牌里的 perms/roles 快照：以本次请求的库内角色为准。
        // 这样"管理员改了某人的角色"对已签发令牌立即生效，而不是等 8 小时过期。
        List<UserRole> roles = resolveRoles(user);
        Set<Permission> permissions = RoleSeparationPolicy.effectivePermissions(new LinkedHashSet<>(roles));
        return new AuthContext(username, primaryRoleOf(roles, user),
                Set.copyOf(roles), Set.copyOf(permissions));
    }

    /**
     * 撤销该账号的全部已签发令牌（退出登录 / 管理员强制下线）。
     *
     * <p>实现是 {@code users.token_version + 1}：已签发令牌在下次请求时因版本不等而失效。
     * 不支持只撤销单个设备；多设备场景要按设备撤销时，再引入 jti + 黑名单表。</p>
     *
     * @return 受影响行数；0 表示用户不存在
     */
    public int revokeAllTokens(String username) {
        if (username == null || username.isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_001, Map.of("reason", "BAD_TOKEN"));
        }
        int updated = userRepository.bumpTokenVersion(username);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND_001, "用户不存在：" + username);
        }
        log.info("已撤销用户全部 JWT：username={}", username);
        return updated;
    }

    /**
     * 解析用户角色：只认 {@code user_roles}（一人多岗）。
     *
     * <p><b>刻意不回退 {@code users.role} 旧字段</b>：安全基线要求"角色被移除"必须立即生效，
     * 若在 {@code user_roles} 为空时回退旧字段，管理员删掉某人的角色后旧字段会继续放行，
     * 撤权就成了假动作。没有角色 = 没有权限（fail-closed）；历史遗留用户需补
     * {@code user_roles} 行后才能操作。{@code users.role} 仅用于登录响应的展示兜底。</p>
     */
    private List<UserRole> resolveRoles(AuthUser user) {
        List<UserRole> roles = userRepository.findRoles(user.getUsername());
        return roles == null ? List.of() : roles;
    }

    /** 主角色：user_roles 首行（查询按 is_primary DESC 排）；无角色时回退旧字段。 */
    private static String primaryRoleOf(List<UserRole> roles, AuthUser user) {
        if (roles != null && !roles.isEmpty()) {
            return roles.get(0).name();
        }
        return user.getRole();
    }

    private BusinessException badToken(String reason, String username) {
        log.debug("JWT 校验失败 reason={} username={}", reason, username);
        return new BusinessException(ErrorCode.AUTH_001, Map.of("reason", "BAD_TOKEN"));
    }

    /** HMAC-SHA256 密钥：至少 256 位（32 字节），否则 jjwt 抛弱密钥异常 */
    private SecretKey signingKey() {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
