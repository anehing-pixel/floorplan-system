package com.company.floorplan.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 生产就绪检查（启动期 fail-fast）：弱密钥 / 多实例不安全的配置。
 *
 * <p><b>为什么必须在启动期而不是运行期</b>：JWT 密钥是开发默认值时，系统"能跑"，
 * 但任何人都能伪造任意身份；数据库密码是 console 默认值时，等于没有认证；
 * 多实例下用内存锁时，两个副本会同时执行同一作业。这些错误不会在冒烟里暴露
 * （单实例 + dev 数据），必须在部署那一刻拒绝启动。</p>
 *
 * <p><b>严格模式</b>：激活 {@code prod} / {@code production} profile，或显式设置
 * {@code floorplan.security.strict-secrets=true} 时，发现问题直接抛异常终止启动；
 * 本地开发（无 profile）只打 WARN，不阻断开发。</p>
 *
 * <p><b>检查项</b>（只检查本服务实际配置了的键；external-api 不配 JWT/webhook，不会被误判）：
 * <ul>
 *   <li>{@code floorplan.auth.secret}：存在时不得是开发默认值，且 UTF-8 至少 32 字节（HS256 要求）；</li>
 *   <li>{@code floorplan.payment.webhook-secret}：存在时不得是开发默认值（只有 web 配置了它）；</li>
 *   <li>{@code spring.datasource.password}：不得是开发默认 {@code floorplan} / DMZ 默认 {@code floorplan_dmz}；</li>
 *   <li>{@code floorplan.accounts.encryption-key}：存在时不得是开发默认值，且至少 32 字节（AES-256）；</li>
 *   <li>{@code floorplan.attachments.s3.bucket}：存在但为空时视为本地磁盘模式，生产拒绝（无 Object Lock）；</li>
 *   <li>{@code floorplan.lock.provider}：存在时必须为 {@code redis}（内存锁只适合单实例）；</li>
 *   <li>{@code floorplan.bank-fetch.source}：存在时不得为 {@code demo}（演示流水不能进生产）。</li>
 * </ul></p>
 *
 * <p><b>不打印密钥值</b>：日志只出现"哪个键不合格"，不出现密钥本身，避免把密钥写进日志。</p>
 *
 * <p><b>装配方式</b>：本类不带 {@code @Component}，由
 * {@link ProductionReadinessAutoConfiguration} 通过 Spring Boot 自动配置导入，
 * 这样十个服务与 web 单体无需各自 {@code @Import}。web 的组件扫描排除了
 * {@code com.company.floorplan.common.*}，自动配置不受该排除影响。</p>
 */
public class ProductionReadinessCheck implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ProductionReadinessCheck.class);

    /** 与 application.yml / AuthApplicationService 的 dev 默认值逐字一致。 */
    static final String DEV_JWT_SECRET = "dev-only-secret-key-change-me-in-production-0123456789";
    static final String DEV_WEBHOOK_SECRET = "dev-webhook-secret-change-me";
    static final String DEV_DB_PASSWORD = "floorplan";

    /** HS256 最小密钥长度（字节）。 */
    private static final int MIN_JWT_SECRET_BYTES = 32;

    private final Environment env;

    public ProductionReadinessCheck(Environment env) {
        this.env = env;
    }

    @Override
    public void afterPropertiesSet() {
        boolean strict = isProductionProfile()
                || Boolean.parseBoolean(env.getProperty("floorplan.security.strict-secrets", "false"));

        List<String> problems = new ArrayList<>();
        // 只检查"存在且是开发默认值"的键：external-api 不做 JWT/webhook，
        // 不能因为没配这两个键而把它判失败；核心服务与 web 的 yml 都显式配了它们。
        checkSecret("floorplan.auth.secret", Set.of(DEV_JWT_SECRET), "JWT 签名密钥",
                MIN_JWT_SECRET_BYTES, problems);
        checkSecret("floorplan.payment.webhook-secret", Set.of(DEV_WEBHOOK_SECRET),
                "银行回调 webhook 密钥", 0, problems);
        // DMZ 的 floorplan_dmz 也是开发默认角色密码，同样必须在生产替换
        checkSecret("spring.datasource.password", Set.of(DEV_DB_PASSWORD, "floorplan_dmz"),
                "数据库密码", 0, problems);
        // 收款账户 AES-GCM 密钥（只有 web 配置）：AES-256 要求 32 字节
        checkSecret("floorplan.accounts.encryption-key",
                Set.of("dev-account-key-change-me-32bytes"), "收款账户加密密钥", 32, problems);

        // 附件存储：只有 web 配置 floorplan.attachments.s3.bucket。
        // 生产必须用对象存储（本地磁盘没有 Object Lock 的不可删改保证）。
        String s3Bucket = env.getProperty("floorplan.attachments.s3.bucket");
        if (s3Bucket != null && s3Bucket.isBlank()) {
            problems.add("floorplan.attachments.s3.bucket（生产必须配置对象存储；本地磁盘无 Object Lock）");
            checkSecret("floorplan.attachments.local.token-secret",
                    Set.of("dev-local-attachment-token"), "本地附件直传密钥", 0, problems);
        }

        String lockProvider = env.getProperty("floorplan.lock.provider");
        if (lockProvider != null && !"redis".equalsIgnoreCase(lockProvider)) {
            problems.add("floorplan.lock.provider=" + lockProvider
                    + "（多实例必须 redis；内存锁只适合单实例）");
        }
        String bankSource = env.getProperty("floorplan.bank-fetch.source");
        if (bankSource != null && "demo".equalsIgnoreCase(bankSource)) {
            problems.add("floorplan.bank-fetch.source=demo（生产不得使用演示流水源）");
        }

        if (problems.isEmpty()) {
            log.info("生产就绪检查通过（strict={}）", strict);
            return;
        }

        String message = "生产就绪检查未通过：\n  - " + String.join("\n  - ", problems);
        if (strict) {
            // 严格模式：拒绝启动。部署环境（prod profile）绝不能带着开发默认值上线。
            throw new IllegalStateException(message
                    + "\n（当前为生产 profile 或 strict-secrets=true，禁止启动；请修正上述配置）");
        }
        log.warn("{}（当前非生产 profile，仅告警；部署前必须修正）", message);
    }

    private void checkSecret(String key, Set<String> devDefaults, String label,
                             int minBytes, List<String> problems) {
        String value = env.getProperty(key);
        if (value == null) {
            // 本服务没有这个键（如 external-api 不做 JWT/webhook）：不判失败
            return;
        }
        if (value.isBlank() || devDefaults.contains(value)) {
            problems.add(key + "（" + label + "仍是开发默认/空值）");
            return;
        }
        if (minBytes > 0 && value.getBytes(StandardCharsets.UTF_8).length < minBytes) {
            problems.add(key + "（" + label + "不足 " + minBytes + " 字节，HS256 无法安全使用）");
        }
    }

    private boolean isProductionProfile() {
        for (String profile : env.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }
}
