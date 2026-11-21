-- V5: 登录认证 —— 用户表 + 种子账号
-- 密码只存 BCrypt 散列（pgcrypto 的 crypt/gen_salt 生成，格式 $2a$，
-- 与 Java 侧 BCryptPasswordEncoder 完全兼容）。明文密码永不落库。

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,          -- 登录名
    password_hash VARCHAR(100) NOT NULL,                 -- BCrypt 散列（$2a$...，60 字符）
    display_name  VARCHAR(64),
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',  -- ADMIN / USER（一期先存不拦，二期做授权）
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);

-- 种子账号（开发环境）：admin / admin123
-- gen_salt('bf', 10)：BCrypt，成本因子 10（单次校验约 50~100ms，防暴力破解）
INSERT INTO users (username, password_hash, display_name, role)
SELECT 'admin', crypt('admin123', gen_salt('bf', 10)), '系统管理员', 'ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'admin');
