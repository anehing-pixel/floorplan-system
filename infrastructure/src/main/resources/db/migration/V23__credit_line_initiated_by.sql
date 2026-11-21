-- ===========================================================================
-- V23：授信额度补记「发起人」（CORE_VERIFIER_REPORT B-5 内控修复）
-- ===========================================================================
--
-- 【为什么要这一列】
-- 现有 initiateCreditLine / completeCreditLine 已是双段审批链，但兼容入口
-- createCreditLine 用同一个 actor 一次性跑完两段，等于单人自动审批，绕过
-- "发起与执行分离"内控要求。completeCreditLine 重新从数据库加载额度，
-- 因此发起人必须落库，不能只放在内存里。
--
-- 【修复方式】默认保持本地/冒烟可用（floorplan.credit.strict-sod=false）；
-- 生产把该开关置 true 后：
--   1) createCreditLine 单人自动审批入口直接拒绝（AUTH_003）；
--   2) completeCreditLine 校验复核人 != initiated_by，同人复核同样拒绝。
-- 这样代码层具备完整 SOD 能力，是否强制属于部署配置，不需要改表结构。
-- ===========================================================================

ALTER TABLE credit_lines
    ADD COLUMN IF NOT EXISTS initiated_by VARCHAR(64);

COMMENT ON COLUMN credit_lines.initiated_by IS
    '授信发起人（自然人账号）；B-5：strict-sod 开启时复核人不得与发起人相同，强制职责分离';
