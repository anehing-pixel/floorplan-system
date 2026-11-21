-- V6: 经销商档案扩展（后台 CRUD 编辑）+ 权限边界验证用的普通角色账号
--
-- 背景：经销商是授信台账的主体（借款人）。后台需要能编辑其档案信息，
-- 但每辆车都挂在经销商名下，档案字段一旦变动会牵连大量台账展示，
-- 因此这里只扩展"主数据"字段，不动任何账务字段。

ALTER TABLE dealers ADD COLUMN IF NOT EXISTS contact_person VARCHAR(64);
ALTER TABLE dealers ADD COLUMN IF NOT EXISTS contact_phone  VARCHAR(32);
ALTER TABLE dealers ADD COLUMN IF NOT EXISTS address        VARCHAR(255);
ALTER TABLE dealers ADD COLUMN IF NOT EXISTS remark         VARCHAR(500);

COMMENT ON COLUMN dealers.contact_person IS '联系人（授信准入登记的接口人）';
COMMENT ON COLUMN dealers.contact_phone  IS '联系电话';
COMMENT ON COLUMN dealers.address        IS '营业地址';
COMMENT ON COLUMN dealers.remark         IS '备注';

-- 种子账号：普通业务操作员（role=USER）。
-- 用途：验证"只有 admin 才能编辑/删除经销商"这条边界确实生效——
-- 拿这个账号登录，调用写接口应被 403 拦下。
INSERT INTO users (username, password_hash, display_name, role)
SELECT 'operator', crypt('operator123', gen_salt('bf', 10)), '业务操作员', 'USER'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'operator');
