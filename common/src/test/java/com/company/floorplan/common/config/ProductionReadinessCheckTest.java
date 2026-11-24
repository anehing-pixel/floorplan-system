package com.company.floorplan.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产就绪检查单测：弱密钥与多实例不安全配置在 prod profile 下必须拒绝启动。
 */
class ProductionReadinessCheckTest {

    private static final String STRONG_JWT = "prod-test-secret-key-with-at-least-32-bytes-abcdef";
    private static final String STRONG_DB = "prod-db-password-not-default";

    private static MockEnvironment env() {
        return new MockEnvironment();
    }

    private static void strong(MockEnvironment env) {
        env.setProperty("floorplan.auth.secret", STRONG_JWT);
        env.setProperty("spring.datasource.password", STRONG_DB);
        env.setProperty("floorplan.payment.webhook-secret", "prod-webhook-secret");
        env.setProperty("floorplan.accounts.encryption-key", "prod-account-key-with-32-bytes-minimum!");
        env.setProperty("floorplan.attachments.s3.bucket", "prod-attachments-bucket");
        env.setProperty("floorplan.lock.provider", "redis");
        env.setProperty("floorplan.bank-fetch.source", "csv");
    }

    private static void check(MockEnvironment env) {
        new ProductionReadinessCheck(env).afterPropertiesSet();
    }

    @Test
    void prodProfile_withStrongConfig_starts() {
        MockEnvironment env = env();
        env.setActiveProfiles("prod");
        strong(env);
        assertDoesNotThrow(() -> check(env));
    }

    @Test
    void prodProfile_withDevJwtSecret_failsFast() {
        MockEnvironment env = env();
        env.setActiveProfiles("prod");
        strong(env);
        env.setProperty("floorplan.auth.secret", ProductionReadinessCheck.DEV_JWT_SECRET);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> check(env));
        assertTrue(ex.getMessage().contains("floorplan.auth.secret"), "必须点名不合格的键");
        assertTrue(!ex.getMessage().contains(ProductionReadinessCheck.DEV_JWT_SECRET),
                "异常信息不能把密钥值打印出来");
    }

    @Test
    void prodProfile_withDevDbPassword_failsFast() {
        MockEnvironment env = env();
        env.setActiveProfiles("prod");
        strong(env);
        env.setProperty("spring.datasource.password", ProductionReadinessCheck.DEV_DB_PASSWORD);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> check(env));
        assertTrue(ex.getMessage().contains("spring.datasource.password"));
    }

    @Test
    void prodProfile_withShortJwtSecret_failsFast() {
        MockEnvironment env = env();
        env.setActiveProfiles("prod");
        strong(env);
        env.setProperty("floorplan.auth.secret", "short-secret");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> check(env));
        assertTrue(ex.getMessage().contains("floorplan.auth.secret"));
        assertTrue(ex.getMessage().contains("32"));
    }

    @Test
    void prodProfile_withDevAccountEncryptionKey_failsFast() {
        MockEnvironment env = env();
        env.setActiveProfiles("prod");
        strong(env);
        env.setProperty("floorplan.accounts.encryption-key", "dev-account-key-change-me-32bytes");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> check(env));
        assertTrue(ex.getMessage().contains("floorplan.accounts.encryption-key"));
    }

    @Test
    void prodProfile_withBlankS3Bucket_failsFast() {
        MockEnvironment env = env();
        env.setActiveProfiles("prod");
        strong(env);
        env.setProperty("floorplan.attachments.s3.bucket", "");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> check(env));
        assertTrue(ex.getMessage().contains("floorplan.attachments.s3.bucket"));
    }

    @Test
    void prodProfile_withMemoryLockProvider_failsFast() {
        MockEnvironment env = env();
        env.setActiveProfiles("prod");
        strong(env);
        env.setProperty("floorplan.lock.provider", "memory");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> check(env));
        assertTrue(ex.getMessage().contains("floorplan.lock.provider"));
    }

    @Test
    void prodProfile_withDemoBankSource_failsFast() {
        MockEnvironment env = env();
        env.setActiveProfiles("prod");
        strong(env);
        env.setProperty("floorplan.bank-fetch.source", "demo");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> check(env));
        assertTrue(ex.getMessage().contains("floorplan.bank-fetch.source"));
    }

    @Test
    void devProfile_withDefaults_onlyWarns() {
        MockEnvironment env = env();
        // 不设 active profile：本地开发，默认值只告警不阻断
        env.setProperty("floorplan.auth.secret", ProductionReadinessCheck.DEV_JWT_SECRET);
        env.setProperty("spring.datasource.password", ProductionReadinessCheck.DEV_DB_PASSWORD);
        env.setProperty("floorplan.lock.provider", "memory");
        assertDoesNotThrow(() -> check(env));
    }

    @Test
    void strictFlag_withoutProdProfile_failsFast() {
        MockEnvironment env = env();
        env.setProperty("floorplan.security.strict-secrets", "true");
        env.setProperty("floorplan.auth.secret", ProductionReadinessCheck.DEV_JWT_SECRET);
        env.setProperty("spring.datasource.password", ProductionReadinessCheck.DEV_DB_PASSWORD);

        assertThrows(IllegalStateException.class, () -> check(env));
    }
}
