-- V9: 补齐角色种子账号（授信审查岗 / 增信业务岗）
--
-- 背景：V8 种了管理员、录入、审核、经办、复核、出纳、结算、处置、只读九类账号，
-- 但冒烟测试跑到"额度年审"与"保险/回购/展期"环节时缺少对应岗位账号。
-- 这里补齐两个岗位，使全链路都能用**该动作真正该由谁做**的账号来跑，
-- 而不是拿管理员账号代劳——后者会绕过"管理员不碰业务单据"的设计前提。
--
-- 另有一次种子账号口令约定：xxx123（与 admin123 / operator123 风格一致，仅开发用）。
--
-- 【前置修复】users.role 原本是 VARCHAR(20)，装不下 RISK_ADVISORY_OFFICER（22 字符），
-- 会报 "value too long for type character varying(20)"。先扩列再插种子。
ALTER TABLE users ALTER COLUMN role TYPE VARCHAR(40);

COMMENT ON COLUMN users.role IS '主角色（UserRole 枚举名）；完整角色集合见 user_roles 表';

INSERT INTO users (username, password_hash, display_name, role)
SELECT 'credit', crypt('credit123', gen_salt('bf', 10)), '授信审查岗', 'CREDIT_REVIEWER'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'credit');

INSERT INTO users (username, password_hash, display_name, role)
SELECT 'advisory', crypt('advisory123', gen_salt('bf', 10)), '增信业务岗', 'RISK_ADVISORY_OFFICER'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'advisory');

INSERT INTO users (username, password_hash, display_name, role)
SELECT 'post_lending', crypt('postlend123', gen_salt('bf', 10)), '贷后管理岗', 'POST_LENDING_MANAGER'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'post_lending');

INSERT INTO users (username, password_hash, display_name, role)
SELECT 'collection', crypt('collect123', gen_salt('bf', 10)), '催收岗', 'COLLECTION_OFFICER'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'collection');

INSERT INTO users (username, password_hash, display_name, role)
SELECT 'collateral', crypt('collateral123', gen_salt('bf', 10)), '担保管理岗', 'COLLATERAL_OFFICER'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'collateral');

-- 写入角色关联表（主角色）
INSERT INTO user_roles (username, role_code, is_primary)
SELECT u.username, u.role, TRUE
FROM users u
WHERE u.username IN ('credit', 'advisory', 'post_lending', 'collection', 'collateral')
  AND NOT EXISTS (
      SELECT 1 FROM user_roles r WHERE r.username = u.username AND r.role_code = u.role
  );

-- 催收岗兼岗：催收岗天然也要能办理部分还款核销（现实中常见），这里显式授予
-- CURTAILMENT_EXECUTE 与 REPAYMENT_EXECUTE 已在角色定义中，此处仅登记关联。
