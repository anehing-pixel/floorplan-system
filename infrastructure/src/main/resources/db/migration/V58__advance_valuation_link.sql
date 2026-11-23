-- ===========================================================================
-- V58：垫款指令表接入估值与 LTV 基准快照（W-U2）
-- ===========================================================================
--
-- 【为什么这一步是估值域能否落地的分水岭】
-- D08 §5.1.1 的五步调用链（取估值 → 定档 → 算上限 → 闸门 → 落基准快照）最后一步
-- 必须在 `advances` 上留下痕迹。留不下，则"这笔钱按什么基准、哪版政策、哪一档成数"
-- 在库里不存在，于是：
--   - 对账与追偿无法回答"这笔为什么是 7,280,000"；
--   - 估值重估后无法证明历史笔不受影响（P20 要证的正是这一行不变）；
--   - 审计要抽样"所有以降级估值作基准的垫款"，没有 valuation_id 就选不出样本。
--
-- 【列名取 D04 §5.2，D08 §5.1.1 的别名不复制成第二列】
-- D04 §5.2 给 advances 的加列清单（权威）：
--   valuation_id BIGINT / prudent_value BIGINT / ltv_ratio NUMERIC(5,4)
--   / ltv_policy_code / base_amount_source VARCHAR(24){INVOICE,PRUDENT}
-- D08 §5.1.1 步 4 写的是 ltv_ratio_snapshot / ltv_policy_code / ltv_policy_version / valuation_id。
-- 差别只有比率列的名字，且 D08 §5.1.1 与 §5.2 指的是同一个数。
-- 本迁移取 D04 的 `ltv_ratio`，**不再另建一份 ltv_ratio_snapshot**：
-- 金融表上两列存同一个值，早晚会分叉（哪列先写、哪列可空没有规则可依据），
-- 分叉之后判定读哪列就变成口头约定。别名差异登记为实现说明 §3-3，
-- 读侧（含 D11 断言与对账）一律以本列为准。
-- ltv_policy_version 由 D08 §5.1.1 要求（政策改版后判定必须仍能指向当时那一版），
-- 属两源并集，一并建。
--
-- 【为什么快照列全部可空】
-- 强制 NOT NULL 会让改造前的历史行无法通过迁移（Flyway 的 UPDATE 回填另有纪律：
-- D04 §10 是唯一允许 UPDATE 的迁移内容，且回填不属于本包）。
-- 因此这里用"NULL = LEGACY/历史行"的显式口径，回填与"新行必须写全"的强制
-- 由应用层 AdvanceCeilingSnapshot 负责（写不全就抛 LTV_004，不允许静默留 NULL）。
--
-- 【金额与比率标度】prudent_value / max_advance_amount = BIGINT 整数日元；
-- ltv_ratio = NUMERIC(5,4)，与 ltv_policy_bands.max_ratio 同标度，
-- 避免"政策用 5,4、快照用 7,4"这种在乘法两端不同精度的隐性舍入。
-- ===========================================================================

-- 1) 基准来源三件套（D04 §5.2 原列）
ALTER TABLE advances ADD COLUMN IF NOT EXISTS valuation_id BIGINT;
ALTER TABLE advances ADD COLUMN IF NOT EXISTS prudent_value BIGINT;
ALTER TABLE advances ADD COLUMN IF NOT EXISTS ltv_ratio NUMERIC(5,4);
ALTER TABLE advances ADD COLUMN IF NOT EXISTS ltv_policy_code VARCHAR(32);
ALTER TABLE advances ADD COLUMN IF NOT EXISTS base_amount_source VARCHAR(24);

COMMENT ON COLUMN advances.valuation_id IS
    '逻辑引用 vehicle_valuations.id。不建 FK 的三个理由： ① 历史行与 LEGACY 行为 NULL，FK 允许 NULL 但会把"删除估值"变成运行期错误， 而估值按 P20 只追加不删除，这条约束买不到任何保证； ② 目标架构里 advances 归 credit-service、vehicle_valuations 归 core（D02 服务边界）， 跨服务表建 FK 等于把两个部署单元绑死； ③ D04 §1 约定：同上下文内建 FK，跨上下文用逻辑引用（与现状 release_orders.redemption_id 一致）。 引用完整性由应用层保证：取不到估值即 LTV_002 fail-closed，不退回发票基准（L4）。';
COMMENT ON COLUMN advances.prudent_value IS
    '放款时点的审慎值快照（整数日元）。与 valuation_id 所指行的 prudent_value 可以不同： 那行之后被 SUPERSEDED 时，本列保留当时真正生效的数 —— 这正是"快照"两字的含义。';
COMMENT ON COLUMN advances.ltv_ratio IS
    '放款时命中档的上限成数快照（D04 §5.2 命名；D08 §5.1.1 称 ltv_ratio_snapshot，同一数）。 与 ltv_policy_code/ltv_policy_version 构成"哪版政策哪一档"的可复算三元组。';
COMMENT ON COLUMN advances.ltv_policy_code IS '命中的政策码。';
COMMENT ON COLUMN advances.base_amount_source IS
    'INVOICE = LEGACY 发票基准；PRUDENT = USED 审慎值基准。NULL 视为 INVOICE 的历史行。 D04 §5.2 特意标「关键：显式标记金额基准来源，防止取数混用」—— USED/LEGACY 并存期所有分组统计与新旧基准差对比都按本列切分。 追加垫款（TOPUP）不扩取值：D04 §5.2 的集合就是这两值，扩它需要裁决。';

-- 2) 政策版本（D08 §5.1.1 步 4）
ALTER TABLE advances ADD COLUMN IF NOT EXISTS ltv_policy_version INT;
COMMENT ON COLUMN advances.ltv_policy_version IS
    '命中政策的版本号。政策改版后，历史笔的判定必须仍能指回当时那一版的带集合， 否则 ltv_policy_code 单独存在只能指向"最新版"，可复算性就断了。';

-- 3) 闸门证据（D08 §5.1.1 步 4 要求"落基准快照"，G5/P11 要求闸门结论可事后验证）
ALTER TABLE advances ADD COLUMN IF NOT EXISTS max_advance_amount BIGINT;
ALTER TABLE advances ADD COLUMN IF NOT EXISTS ceiling_gate_result VARCHAR(16);
COMMENT ON COLUMN advances.max_advance_amount IS
    '当时算出的允许上限 = FLOOR(prudent_value * ltv_ratio)（整数日元，向下取整）。 存结果而不是只存输入：重算要用政策带，而带集合可能被改写或被 RETIRED， 上限列是闸门放行时的证据，不是闸门的输入。';
COMMENT ON COLUMN advances.ceiling_gate_result IS
    'PASS / PASS_WITH_ALERT / BLOCKED。LTV_005（比率越界告警）不阻断放款， 必须留下"放行但告警"的痕迹，否则告警在数据上与"从未检查"不可区分。';

-- 4) 申请与指令分离 —— 【本迁移有意不建 application_id，理由如下】
-- D03 §2 聚合表第 18 行把 `advance` 标为「⚠️ 扩展」、模型名 AdvanceApplication
-- （垫款申请 + 审定金额 + 闸门结果），但 D00–D13 全量检索命中：
--   `advance_applications` = 0 处、`application_id` = 0 处、`申请单` = 0 处
-- ⟹ 申请与指令分离在规格里只有模型名，没有表、没有列、没有号段。
-- 加一个指向不存在表的 application_id + 部分唯一索引，看起来是"提前接线"，
-- 实际是用一条永远为 NULL 的列冒充幂等保证 —— 比不建更危险，
-- 因为它会在验收时被当成"分离已实现"。故本包只在 advances 上补齐
-- "审定金额与闸门结果"两类可直接落地的列（上面 3)），
-- 申请单表列为待裁决（实现说明 §3-4）。
-- 现行重复垫款防线（本轮未改动，仍是这三层）：
--   L1 advances.idempotency_key 唯一（V1:86）+ 应用层先查后返回首次结果
--   L2 findOccupyingByVin + uk_adv_vin_active 部分唯一索引（V14，一车一在途指令）
--   L3 stageAdvance 的 credit_lines / vehicle_units 行锁串行化（ADR-010）

-- 索引：闸门结论抽样（"放行但告警"清单、结算与审计的对账入口）
CREATE INDEX IF NOT EXISTS idx_adv_ceiling_gate ON advances (ceiling_gate_result, id DESC)
    WHERE ceiling_gate_result IS NOT NULL;
-- 索引：按估值反查引用它的垫款（估值被质疑时的影响面定位，也是 valuation_id 唯一有用的读路径）
CREATE INDEX IF NOT EXISTS idx_adv_valuation ON advances (valuation_id) WHERE valuation_id IS NOT NULL;
-- 索引：基准来源分组（新旧基准差对比、LEGACY 存量统计）
CREATE INDEX IF NOT EXISTS idx_adv_base_source ON advances (base_amount_source, id DESC)
    WHERE base_amount_source IS NOT NULL;

-- ===========================================================================
-- 【顺序依赖，合主干时必须复核】
-- valuation_id 逻辑引用 V55 建的 vehicle_valuations，本迁移号段 V58 在 V55 之后，
-- 段内顺序正确；但 V55 依赖 W-U1 的 V50（vehicle_units USED 列，见 D04 §2 表），
-- 主干上若出现 V50 与 V55–V64 交错应用，需按 D04 §2「冲突时整块后移、不逐文件穿插」处理。
-- 本包在分支上能自证的是号段内顺序，不能自证的是合主干后的顺序 —— 后者属收口方。
-- ===========================================================================
