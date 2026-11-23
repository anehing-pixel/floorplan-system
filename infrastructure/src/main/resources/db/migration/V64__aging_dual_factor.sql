-- ===========================================================================
-- V64：库龄双因子拆分列 + 库龄豁免规则表（W-U2 / R9.3）
-- ===========================================================================
--
-- 【规格源】D04 §5.2 的 aging_plans 行（加列）+ D04 §3.14.1（新表，表名由
--   D13 §4.4 WU2-11 定名）+ D08 §7.2（三因子取大 + 棘轮公式）+ D06 UC-R9-8（四个规则码）。
--
-- 【为什么要拆分因子列】
-- 现状 `aging_plans.reduce_amount` 只有一个结果数。结果数无法回答两类问题：
--   1) 这个递减额是"车龄到了"还是"行情跌了"造成的 —— 两者的处置动作不同
--      （前者是常态递减，后者要触发 LTV 突破告警与追加还款）；
--   2) 递减额为什么比上期大 —— 没有因子拆分，作业回归与业务质询都只能靠重跑。
-- 拆分列是**输入证据**，`reduce_amount` 仍是最终生效值（棘轮后的 target_reduction），
-- 两者并存是有意的：改公式不许动生效值。
--
-- 【棘轮为什么落在既有列上而不是新列】
-- D08 §7.2：`target_reduction = MAX(required_reduction, 既有 reduce_amount)`。
-- 行情回升时不自动下调已下发的追加还款要求 —— 通知已送达车商，撤回造成对账口径混乱，
-- 回升只影响下一次判定。所以 reduce_amount 的语义（只增不减）由作业保证，不新建列。
-- ===========================================================================

ALTER TABLE aging_plans ADD COLUMN IF NOT EXISTS rule_code VARCHAR(32);
ALTER TABLE aging_plans ADD COLUMN IF NOT EXISTS valuation_driven_amount BIGINT;
ALTER TABLE aging_plans ADD COLUMN IF NOT EXISTS day_driven_amount BIGINT;

COMMENT ON COLUMN aging_plans.rule_code IS
    '本次递减所依据的豁免/规则码，取值指向 aging_exempt_rules.rule_code（D04 §5.2）。 ★ 指向 rule_code 而不指向 id：规则换版后，历史行要还能说清"当时免的是哪条规则"， 指向 id 会让历史行随规则改写而失去含义（与 V58 的 ltv_policy_version 同一手法）。 NULL = 本行未套用任何豁免规则（正常递减）。';
COMMENT ON COLUMN aging_plans.valuation_driven_amount IS
    '估值因子驱动的递减额（整数日元）= CEILING(max(0, 敞口本金 - FLOOR(最新 ACTIVE 估值 prudent_value × 命中档 max_ratio))) （D08 §7.2）。与 LTV 突破的追加还款额同源，两处必须读同一个最新 ACTIVE 估值， 不许一边用快照一边用实时值。';
COMMENT ON COLUMN aging_plans.day_driven_amount IS
    '按日因子驱动的递减额（整数日元）= FLOOR(advance_amount × 速率 / 100 × 在库日)， 上限截断 ≤ advance_amount × aging_day_decay_cap（默认 0.35）。 速率与封顶都从配置读（[待定源 D-05]，见实现说明 §4），不硬编码在 Java 里。';
COMMENT ON COLUMN aging_plans.reduce_amount IS
    '本轮生效的递减额（棘轮后 = MAX(三因子取大, 既有值)，只增不减）。 USED 改造后它仍是唯一生效值，两个 *_driven_amount 只是它的构成证据（V64 加列注释覆盖旧口径）。';

-- ===========================================================================
-- 库龄豁免规则表（D04 §3.14.1）
-- ===========================================================================
-- 【为什么要表化】
-- 现状豁免是配置项 `aging.exempt-vins`（yml 里的 VIN 列表）：
-- 谁能加、加多久、为什么加，全都不在库里，也没有第二个人签过字。
-- 豁免免掉的是**追缴动作**，等于在风控最该收紧的时候松开一只手，
-- 这种动作必须逐条留痕、双人签认、有生效期，因此表化（R9.3 / UC-R9-8）。
--
-- 【豁免只免什么】
-- 只免 `top_up_amount` 的催缴；`target_reduction` 仍照 D08 §7.2 计算并落库
-- （D04 §3.14.1 与 D08 §10 `exemptRuleStillRecordsTargetReduction` 同一口径）。
-- ⟹ 豁免不改变风险事实，只暂停追缴动作。这一点必须在数据上看得见：
--    本表不参与递减额计算，作业命中豁免时跳过的是催缴那一半。
CREATE TABLE IF NOT EXISTS aging_exempt_rules (
    id                BIGSERIAL    PRIMARY KEY,
    -- ⚠ 规格冲突，照 D04 §3.14.1 建 UNIQUE，后果见本迁移末尾
    rule_code         VARCHAR(64)  NOT NULL UNIQUE,     -- 取值 = D06 UC-R9-8 四个码
    rule_name         VARCHAR(128) NOT NULL,
    scope_type        VARCHAR(16)  NOT NULL,            -- {VIN, DEALER, LOT_STATUS}
    scope_value       VARCHAR(64),                      -- 可空：LOT_STATUS 类规则按状态全量适用
    max_exempt_days   INT,                              -- [待定源 D-05] 未签字 ⟹ 不预置、不写默认值
    effective_from    DATE         NOT NULL,
    effective_to      DATE,                             -- NULL = 长期（但受 max_exempt_days 约束）
    status            VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    reason            TEXT         NOT NULL,            -- 无原因的豁免不允许存在
    requested_by      VARCHAR(64)  NOT NULL,            -- 申请人
    approved_by       VARCHAR(64)  NOT NULL,            -- 批准人
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           INT          NOT NULL DEFAULT 0,
    -- 二人签认（SOD）：D04 §3.14.1 要求 NOT NULL 且 CHECK 二者不等。
    -- 与 V55 覆盖二人、V57 政策二人同一手法 —— 单人豁免 = 任何人可自免追缴。
    CONSTRAINT ck_exempt_sod CHECK (approved_by <> requested_by),
    CONSTRAINT ck_exempt_scope_type CHECK (scope_type IN ('VIN','DEALER','LOT_STATUS')),
    CONSTRAINT ck_exempt_status CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
    -- 规则码闭集：取值来自 D06 UC-R9-8，第四个是 REGISTRATION_IN_PROGRESS（車検过户中）。
    -- 不扩集合：新增豁免类型属业务口径变更，须先改 D06 再改本 CHECK。
    CONSTRAINT ck_exempt_code CHECK (rule_code IN (
        'AGING_EXEMPT_AUCTION_PENDING_PICKUP',
        'AGING_EXEMPT_SERVICE_PENDING_LISTING',
        'AGING_EXEMPT_INTER_STORE_TRANSFER',
        'AGING_EXEMPT_REGISTRATION_IN_PROGRESS')),
    -- 期限有序 + 天数非负（天数未签字所以可空）
    CONSTRAINT ck_exempt_period CHECK (effective_to IS NULL OR effective_from <= effective_to),
    CONSTRAINT ck_exempt_days CHECK (max_exempt_days IS NULL OR max_exempt_days >= 0)
    -- 【scope_value 与 scope_type 的配套关系：本迁移有意不写 CHECK】
    -- D04 §3.14.1 给的是 `scope_value VARCHAR(64) NULL`，却没说 NULL 表示什么
    -- （"该类型全量适用"？还是"还没填"？）。两种解释会导出相反的约束
    -- （NOT NULL vs 允许 NULL），而猜错的方向是危险的：
    -- 把"没填"当成"全量适用"，一台车的豁免会自动扩到全部车。
    -- 因此既不建 CHECK、也不在代码里赋默认语义，列为待签字（实现说明 §3-9）；
    -- 签字前读取路径按"scope_value 为 NULL 的规则不命中任何范围"处理（fail-closed）。
);

-- 同一规则码 + 同一作用范围，至多一条生效（D04 §3.14.1 指定的部分唯一索引写法）
CREATE UNIQUE INDEX IF NOT EXISTS uk_exempt_active
    ON aging_exempt_rules (rule_code, scope_type, scope_value) WHERE status = 'ACTIVE';
-- 命中查询：按作用范围找生效规则（作业每轮都要判"这台车/这个 dealer 是否被免")
CREATE INDEX IF NOT EXISTS idx_exempt_scope ON aging_exempt_rules (scope_type, scope_value) WHERE status = 'ACTIVE';

COMMENT ON TABLE aging_exempt_rules IS
    '库龄豁免规则表（W-U2 / R9.3，替换硬编码 aging.exempt-vins，D06 UC-R9-8）。 豁免只暂停 top_up_amount 的催缴，target_reduction 仍照算仍落库； 二人签认（requested_by ≠ approved_by）由 CHECK 在库层保证。';
COMMENT ON COLUMN aging_exempt_rules.max_exempt_days IS
    '最长豁免天数 [待定源 D-05]。本包不预置数值；未签字前所有新规则该列为 NULL， 由作业按"无期限上限"处理并告警（不是静默长期免）。';

-- ===========================================================================
-- 【本迁移不预置任何内容行】
-- D04 §3.14.1 原文：「内容行一条不预置（谁有权豁免、最长时限属 [待定源 D-05]）」。
-- 种子行会直接制造"系统里已存在生效豁免规则"的事实，而豁免的批准权限与期限都未签字。
--
-- 【照建不误的规格冲突（已登记实现说明 §3-8，需 D04 裁决）】
-- D04 §3.14.1 同时要求：
--   (a) `rule_code VARCHAR(64) UNIQUE`，且取值 = 四个 AGING_EXEMPT_* 码；
--   (b) 部分唯一索引 `(rule_code, scope_type, scope_value) WHERE status='ACTIVE'`。
-- (a) 使 (b) 失去意义：rule_code 全表唯一时，四个码各自只能有一行，
-- 「按 VIN 逐台豁免」与「按经销商豁免」无法共存，同一码下多条不同范围规则一律插不进，
-- 报 UNIQUE 冲突 —— 而 scope_type/scope_value 两列的存在恰恰说明设计意图就是多条。
-- 本迁移按字面两条都建（不擅自删 (a)），并在实现说明里给出两个候选处置：
--   ① 去掉 rule_code 的全表 UNIQUE，只留 (b)（与 scope 列的存在自洽）；
--   ② 保留 (a)，把 scope_* 移到主表之外的规则明细表（改动更大，涉及 W-U5 的读取路径）。
-- 裁决前，W-U5 的 A-G 组（豁免）与 UC-R9-8 冒烟**不可能通过**，这是规格冲突的直接后果，
-- 不是本包漏实现。
-- ===========================================================================
