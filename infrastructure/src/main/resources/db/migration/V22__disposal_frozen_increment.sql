-- ===========================================================================
-- V22：违约处置工单补记「本次冻结增量」（CORE_VERIFIER_REPORT B-2 阻塞修复）
-- ===========================================================================
--
-- 【为什么必须有这一列】
-- 处置开单时 CreditLine.freezeForDisposal(exposure) 冻结整条额度线，
-- 但旧代码的 cancel() / close() 从不解冻 → 额度线永久 FROZEN，
-- 该经销商其他正常在库车辆的放款一并被阻断（"核心流程不可用"）。
--
-- 解冻必须按【本次真实增量】精确回退，不能用 exposure 也不能用累计值：
--   CreditLine.freeze(amount) 内部是 min(amount, available())，
--   实际冻结量可能被可用额封顶；freezeForDisposal(0) 更特殊——只置 FROZEN 不加金额。
--   故增量 = 冻结后 frozenAmount − 冻结前 frozenAmount，必须逐单落库。
--
-- 【与 B-3 的耦合】B-3 让 DISPOSING 不再误回补额度；两者必须同批修，
-- 否则单独补解冻会把"潜在资损"闭环成实际资损。
-- ===========================================================================

ALTER TABLE disposal_cases
    ADD COLUMN IF NOT EXISTS frozen_increment BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN disposal_cases.frozen_increment IS
    '本次开单对该额度线的冻结增量（整数日元）= 冻结后frozenAmount−冻结前frozenAmount；结案/撤销时按此精确 unfreeze，不可改用累计值或敞口';
