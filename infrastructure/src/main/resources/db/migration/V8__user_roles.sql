-- V8: 角色体系 —— 用户多角色表 + 角色种子账号
--
-- 背景：V5 建 users 表时 role 只有 ADMIN / USER 两个值，业务落地后暴露出问题：
--   ① 二值角色无法表达业务分工（录入 / 审核 / 申请 / 复核 / 支付 / 贷后…）；
--   ② 一人往往兼岗（财务既复核又结算），单值列装不下；
--   ③ USER 语义含混——它既被当"只读"用，又被冒烟脚本当"业务经办"用。
--
-- 本次改动：
--   1. 新增 user_roles 表，一人多岗（一个用户可持多个角色）；
--   2. users.role 保留为"主角色"（显示与审计用），角色代码改用 UserRole 枚举名；
--   3. 回填历史账号：ADMIN → SYSTEM_ADMIN，USER → BUSINESS_OPERATOR；
--   4. 种子各业务角色账号，便于验证权限矩阵与职责分离。
--
-- 兼容性说明：users.role 仍保留且 NOT NULL，存量代码与审计字段
-- actor_role 继续可读；真正的授权判定已迁到 user_roles + 令牌权限点。

CREATE TABLE IF NOT EXISTS user_roles (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL,             -- 登录名（与 users.username 对齐）
    role_code   VARCHAR(40)  NOT NULL,             -- UserRole 枚举名
    is_primary  BOOLEAN      NOT NULL DEFAULT FALSE, -- 主角色标记（与 users.role 一致的那一个）
    granted_at  TIMESTAMP    NOT NULL DEFAULT now(),
    granted_by  VARCHAR(64),                       -- 授权人（管理员账号）
    CONSTRAINT uk_user_role UNIQUE (username, role_code),
    CONSTRAINT ck_user_role_code CHECK (role_code IN (
        'SYSTEM_ADMIN', 'CREDIT_REVIEWER', 'PURCHASE_OPERATOR', 'PURCHASE_REVIEWER',
        'BUSINESS_OPERATOR', 'FUNDING_REVIEWER', 'FUNDING_CASHIER',
        'COLLATERAL_OFFICER', 'POST_LENDING_MANAGER', 'COLLECTION_OFFICER',
        'SETTLEMENT_OFFICER', 'DISPOSAL_OFFICER', 'RISK_ADVISORY_OFFICER',
        'READONLY_USER'))
);

CREATE INDEX IF NOT EXISTS idx_user_roles_username ON user_roles (username);

COMMENT ON TABLE user_roles IS '用户角色关联表：一人多岗，角色决定有效权限点';
COMMENT ON COLUMN user_roles.role_code IS 'UserRole 枚举名，须与 domain 层 UserRole 保持一致';
COMMENT ON COLUMN user_roles.is_primary IS '主角色标记；每人至多一条 is_primary=TRUE';

-- ---------------------------------------------------------------------------
-- 回填：把历史 users.role 映射到新角色代码
-- ---------------------------------------------------------------------------
-- ADMIN → SYSTEM_ADMIN：系统管理员
-- USER  → BUSINESS_OPERATOR：业务经办岗（历史上它实际能做的正是发起垫款申请）
-- 其他值原样保留（理论上不会有）
UPDATE users SET role = 'SYSTEM_ADMIN' WHERE role = 'ADMIN';
UPDATE users SET role = 'BUSINESS_OPERATOR' WHERE role = 'USER';

INSERT INTO user_roles (username, role_code, is_primary)
SELECT u.username, u.role, TRUE
FROM users u
WHERE NOT EXISTS (
    SELECT 1 FROM user_roles r WHERE r.username = u.username AND r.role_code = u.role
);

-- ---------------------------------------------------------------------------
-- 种子账号：每个业务角色一个账号，用于验证权限矩阵
-- ---------------------------------------------------------------------------
-- 密码统一 xxx123（与既有 admin123 / operator123 风格一致，仅开发环境使用）
INSERT INTO users (username, password_hash, display_name, role)
SELECT 'import_op', crypt('import123', gen_salt('bf', 10)), '采购数据录入岗', 'PURCHASE_OPERATOR'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'import_op');

INSERT INTO users (username, password_hash, display_name, role)
SELECT 'review_op', crypt('review123', gen_salt('bf', 10)), '采购审核岗', 'PURCHASE_REVIEWER'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'review_op');

INSERT INTO users (username, password_hash, display_name, role)
SELECT 'fund_rev', crypt('fundrev123', gen_salt('bf', 10)), '资金复核岗', 'FUNDING_REVIEWER'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'fund_rev');

INSERT INTO users (username, password_hash, display_name, role)
SELECT 'cashier', crypt('cashier123', gen_salt('bf', 10)), '资金出纳岗', 'FUNDING_CASHIER'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'cashier');

INSERT INTO users (username, password_hash, display_name, role)
SELECT 'settlement', crypt('settle123', gen_salt('bf', 10)), '结算岗', 'SETTLEMENT_OFFICER'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'settlement');

INSERT INTO users (username, password_hash, display_name, role)
SELECT 'disposal', crypt('disposal123', gen_salt('bf', 10)), '违约处置岗', 'DISPOSAL_OFFICER'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'disposal');

-- 录入岗账号**兼任**增信业务岗：验证"一人多岗 ⇒ 权限取并集"
INSERT INTO users (username, password_hash, display_name, role)
SELECT 'multi_role', crypt('multi123', gen_salt('bf', 10)), '多岗兼任测试账号', 'PURCHASE_OPERATOR'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'multi_role');

-- 把种子的角色写入关联表
INSERT INTO user_roles (username, role_code, is_primary)
SELECT u.username, u.role, TRUE
FROM users u
WHERE u.username IN ('import_op', 'review_op', 'fund_rev', 'cashier', 'settlement', 'disposal', 'multi_role')
  AND NOT EXISTS (
      SELECT 1 FROM user_roles r WHERE r.username = u.username AND r.role_code = u.role
  );

-- 多岗账号的第二个角色（主角色仍是录入岗）
INSERT INTO user_roles (username, role_code, is_primary)
SELECT 'multi_role', 'RISK_ADVISORY_OFFICER', FALSE
WHERE NOT EXISTS (
    SELECT 1 FROM user_roles WHERE username = 'multi_role' AND role_code = 'RISK_ADVISORY_OFFICER'
);

-- operator 账号补上业务经办岗角色（与回填结果一致，幂等）
INSERT INTO user_roles (username, role_code, is_primary)
SELECT 'operator', 'BUSINESS_OPERATOR', TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM user_roles WHERE username = 'operator' AND role_code = 'BUSINESS_OPERATOR'
);

-- ---------------------------------------------------------------------------
-- 垫款单的三段职责分离留痕（申请 / 复核 / 支付）
-- ---------------------------------------------------------------------------
-- 每一段都记下操作自然人，才能在事后审计中回答
-- "这一笔是经办、复核、支付三个人做的，还是一个人做的"。
-- 单靠角色无法回答——一人多岗时角色可能相同，但自然人必须不同。
ALTER TABLE advances ADD COLUMN IF NOT EXISTS applicant      VARCHAR(64);   -- 申请人
ALTER TABLE advances ADD COLUMN IF NOT EXISTS reviewer       VARCHAR(64);   -- 复核人
ALTER TABLE advances ADD COLUMN IF NOT EXISTS reviewed_at    TIMESTAMPTZ;   -- 复核时间
ALTER TABLE advances ADD COLUMN IF NOT EXISTS disburser      VARCHAR(64);   -- 支付人

COMMENT ON COLUMN advances.applicant IS '垫款申请人（自然人，来自令牌身份）';
COMMENT ON COLUMN advances.reviewer IS '垫款复核人（自然人，须≠申请人）';
COMMENT ON COLUMN advances.disburser IS '支付人（自然人，须≠申请人且≠复核人）';

-- ---------------------------------------------------------------------------
-- 告警处置留痕：告警要有人认领与处置，否则告警池只会越积越满
-- ---------------------------------------------------------------------------
ALTER TABLE alerts ADD COLUMN IF NOT EXISTS handler VARCHAR(64);       -- 处置人
ALTER TABLE alerts ADD COLUMN IF NOT EXISTS handled_at TIMESTAMPTZ;     -- 处置时间
ALTER TABLE alerts ADD COLUMN IF NOT EXISTS handle_note TEXT;           -- 处置说明

COMMENT ON COLUMN alerts.handler IS '告警处置人（自然人，来自令牌身份）';
COMMENT ON COLUMN alerts.handle_note IS '处置说明：为什么这样处置，供事后复核';
