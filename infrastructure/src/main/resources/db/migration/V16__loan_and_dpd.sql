-- ===========================================================================
-- V16：借据 / DPD / 经销商贷后画像（loan agent，T2-2 + T3-1）
-- ===========================================================================
--
-- 【设计依据】
--   docs/贷后风险分层-借据事件与经销商画像设计.md（五表字段、DPD 分档、画像口径）
--   docs/库存融资核心系统-ADR008逾期状态归属借据设计.md（借据状态机、还款计划、逾期记录）
--
-- 【五张表】（命名按 AGENT_TASK.md T2-2，字段按设计稿）
--   loans                 借据当前状态载体（流程唯一读依赖）
--   loan_status_history   借据状态跃迁留痕（append-only）
--   repayment_plans       还款计划（到期的唯一来源；设计稿原名 repayment_schedules）
--   overdue_records       逾期记录（一笔借据仅一条；跨 M1→M4 同记录升档）
--   dealer_risk_profile   经销商贷后画像（只读投影，不参与流程；设计稿原名 dealer_risk_profiles）
--
-- 【关键纪律】
--   1. 金额一律 BIGINT（整数日元），风格照抄 V15。
--   2. 当前状态冗余在 loans 单行，单一写入点；事件表只 append，不反向写 loans。
--   3. 画像表没有 vin / loan_no，没有任何明细，天然不具备影响单笔流程的能力。
--   4. risk_grade（授信评级，在 credit/dealers）与 risk_tier（贷后画像，本表）是两套指标，严禁混用。
--   5. DPD 分档按设计稿 §4.3 种子数据的 1-based 口径：M1=1-30 / M2=31-60 / M3=61-90 / M4>=91。
--      详见 Java DpdCalculator 的 Javadoc：任务卡单测提示的 1-29/30-59/60-90/90+ 与该口径有边界差异，
--      实现以设计稿为准（"按设计稿实现不要自己发明"），单测同时覆盖两套边界点值。
--
-- 【跨 agent 契约】loans.vin / advance_id / dealer_id / status / principal / due_date / created_at
--   已冻结于 docs/接口契约-V16借据.md，payment agent 按此编码，字段不得改动。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- ① loans：借据当前状态载体
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS loans (
    id                  BIGSERIAL PRIMARY KEY,
    loan_no             VARCHAR(32)  UNIQUE NOT NULL,
    advance_id          BIGINT,              -- 资金来源凭证（钱没到，债不成立；ADR-008 §2.2）
    vin                 VARCHAR(32)  NOT NULL,
    dealer_id           BIGINT       NOT NULL,
    credit_line_id      BIGINT       NOT NULL,
    purchase_order_id   BIGINT,

    -- ---- 【跨 agent 契约字段】payment agent 的 VIN→借据匹配依赖这几列，勿改名勿改类型 ----
    principal           BIGINT       NOT NULL DEFAULT 0,  -- 借据本金（整数日元）
    due_date            DATE,                            -- 合同到期日（契约暴露名；与 maturity_date 同值）
    status              VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
        -- DRAFT/ACTIVE/OVERDUE/SETTLING/SETTLED/DEFAULTED/WRITTEN_OFF
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- ---- 期限（自 vehicle_units 下沉而来，权威源在此）----
    disbursed_at        TIMESTAMPTZ,
    maturity_date       DATE,                -- 设计稿原字段名，与 due_date 同值，保留兼容设计稿引用
    grace_period_days   INT          NOT NULL DEFAULT 0,
    overdue_rate        NUMERIC(10,6),       -- 逾期罚息年利率，NULL=沿用基准
    term_source         VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN',
        -- CONTRACT / REGISTRATION / MANUAL_REVIEW / INFERRED / UNKNOWN
    term_confirmed_by   VARCHAR(64),
    term_confirmed_at   TIMESTAMPTZ,

    -- ---- 四项分项余额（承接《放车放证》四级冲抵顺序）----
    principal_outstanding BIGINT NOT NULL DEFAULT 0,
    interest_outstanding  BIGINT NOT NULL DEFAULT 0,
    penalty_outstanding   BIGINT NOT NULL DEFAULT 0,
    fee_outstanding       BIGINT NOT NULL DEFAULT 0,

    -- ---- ① 逾期当前状态（流程读这里）----
    current_dpd         INT      NOT NULL DEFAULT 0,
    current_stage_code  VARCHAR(16),                   -- M1/M2/M3/M4，引用 dpd_stage_rules
    overdue_since       DATE,                          -- 本次逾期起始日
    max_dpd_ever        INT      NOT NULL DEFAULT 0,   -- 历史峰值，【只增不减】
    overdue_count       INT      NOT NULL DEFAULT 0,
    current_stage_entered_at TIMESTAMPTZ,

    -- ---- ② 扣款失败当前状态（流程读这里）----
    deduction_fail_streak  INT NOT NULL DEFAULT 0,
    deduction_fail_total   INT NOT NULL DEFAULT 0,
    last_deduction_fail_at TIMESTAMPTZ,
    last_deduction_fail_code VARCHAR(24),
        -- INSUFFICIENT_FUNDS / ACCOUNT_FROZEN / ACCOUNT_CLOSED / NAME_MISMATCH / BANK_REJECT / NO_ACCOUNT

    -- ---- ③ 违约当前状态（流程读这里）----
    default_flag        BOOLEAN NOT NULL DEFAULT FALSE,
    defaulted_at        TIMESTAMPTZ,
    default_reason_code VARCHAR(32),
    default_cured_at    TIMESTAMPTZ,

    version             INT      NOT NULL DEFAULT 0,   -- 乐观锁
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 契约查询：按 VIN 取未结清借据（payment agent 的 by-vin 端点走这个索引）
CREATE INDEX IF NOT EXISTS idx_loans_vin_active ON loans (vin)
    WHERE status IN ('DRAFT','ACTIVE','OVERDUE','SETTLING');
CREATE INDEX IF NOT EXISTS idx_loans_advance   ON loans (advance_id);
CREATE INDEX IF NOT EXISTS idx_loans_dealer    ON loans (dealer_id, status);
CREATE INDEX IF NOT EXISTS idx_loans_stage     ON loans (current_stage_code, current_dpd DESC);

COMMENT ON TABLE  loans IS '借据：债务事实来源与贷后当前状态载体。流程只读本表，不读画像表';
COMMENT ON COLUMN loans.vin IS '跨 agent 契约字段：VIN 唯一指向一笔未结清借据（见 docs/接口契约-V16借据.md）';
COMMENT ON COLUMN loans.advance_id IS '跨 agent 契约字段：资金来源凭证，银行回调 PAID 后才建借据';
COMMENT ON COLUMN loans.dealer_id IS '跨 agent 契约字段：经销商 ID，画像按此聚合';
COMMENT ON COLUMN loans.principal IS '跨 agent 契约字段：借据本金（整数日元），与 principal_outstanding（剩余）区分';
COMMENT ON COLUMN loans.due_date IS '跨 agent 契约字段：合同到期日，与 maturity_date 同值，契约暴露的稳定名';
COMMENT ON COLUMN loans.status IS '跨 agent 契约字段：DRAFT/ACTIVE/OVERDUE/SETTLING/SETTLED/DEFAULTED/WRITTEN_OFF';
COMMENT ON COLUMN loans.created_at IS '跨 agent 契约字段：借据创建时间';
COMMENT ON COLUMN loans.max_dpd_ever IS '历史 DPD 峰值，只增不减——画像 MAX(此列) 即最大逾期天数';
COMMENT ON COLUMN loans.current_stage_code IS '当前 DPD 阶段 M1/M2/M3/M4，每晚 DPD job 唯一写入';
COMMENT ON COLUMN loans.default_flag IS '违约标记，仅人工确认违约后写 true；系统建议不改此列';
COMMENT ON COLUMN loans.version IS '乐观锁：防止 DPD job 与状态机并发回写覆盖';

-- ---------------------------------------------------------------------------
-- ② loan_status_history：状态跃迁留痕（append-only，谁/何时/从哪到哪/依据）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS loan_status_history (
    id          BIGSERIAL PRIMARY KEY,
    loan_id     BIGINT NOT NULL,
    from_status VARCHAR(16),
    to_status   VARCHAR(16) NOT NULL,
    reason      VARCHAR(500),
    operator    VARCHAR(64) NOT NULL,      -- 'system' 或自然人
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_lsh_loan ON loan_status_history (loan_id, occurred_at DESC);

COMMENT ON TABLE  loan_status_history IS '借据状态机每次迁移的审计留痕（append-only，不可删改）';
COMMENT ON COLUMN loan_status_history.operator IS 'system=系统判定（如到期未足额），自然人=人工确认/处置';

-- ---------------------------------------------------------------------------
-- ③ repayment_plans：还款计划（到期的唯一来源；设计稿原名 repayment_schedules）
-- ---------------------------------------------------------------------------
-- 与 aging_plans（库龄递减）是两种形态，不是同一张表：aging_plans 不导致逾期，
-- repayment_plans 的到期未足额才是借据进 OVERDUE 的唯一入口（ADR-008 §2.5）。
CREATE TABLE IF NOT EXISTS repayment_plans (
    id             BIGSERIAL PRIMARY KEY,
    loan_id        BIGINT      NOT NULL,
    period_no      INTEGER     NOT NULL,      -- 期号（1 起）
    due_date       DATE        NOT NULL,      -- 应还日（逾期起算的权威来源）
    principal_due  BIGINT      NOT NULL,
    interest_due   BIGINT      NOT NULL,
    total_due      BIGINT      NOT NULL,
    principal_paid BIGINT      NOT NULL DEFAULT 0,
    interest_paid  BIGINT      NOT NULL DEFAULT 0,
    paid_at        TIMESTAMPTZ,
    status         VARCHAR(16) NOT NULL DEFAULT 'PENDING',
        -- PENDING / PARTIAL / PAID / OVERDUE / WRITTEN_OFF
    CONSTRAINT uk_rp_loan_period UNIQUE (loan_id, period_no)
);

CREATE INDEX IF NOT EXISTS idx_rp_due ON repayment_plans (due_date, status);

COMMENT ON TABLE  repayment_plans IS '按期还款计划（设计稿 repayment_schedules）。到期未足额是借据进 OVERDUE 的唯一来源';
COMMENT ON COLUMN repayment_plans.due_date IS '应还日。DPD 逾期起算点=首个未足额期的 due_date + 宽限期';

-- ---------------------------------------------------------------------------
-- ④ overdue_records：逾期记录（一笔借据同时只一条；跨档同记录升档）
-- ---------------------------------------------------------------------------
-- uk_overdue_loan 保证跨 M1→M4 是同一条记录的 current_stage_code 变化，
-- 否则每次升档插新记录会导致催收工单重复派发（设计稿 §3.3）。
CREATE TABLE IF NOT EXISTS overdue_records (
    id             BIGSERIAL PRIMARY KEY,
    overdue_no     VARCHAR(32) UNIQUE NOT NULL,
    loan_id        BIGINT      NOT NULL,
    vin            VARCHAR(32) NOT NULL,      -- 冗余：盘点/催收直接查
    dealer_id      BIGINT      NOT NULL,
    schedule_id    BIGINT      NOT NULL,      -- 触发逾期的期次（repayment_plans.id）
    overdue_date   DATE        NOT NULL,      -- 逾期起始日
    dpd            INTEGER     NOT NULL DEFAULT 0,
    current_stage_code  VARCHAR(16) NOT NULL, -- 本条当前阶段（替代 ADR-008 硬编码 bucket）
    max_stage_code      VARCHAR(16) NOT NULL, -- 本次逾期达到过的最高阶段
    stage_rule_version  VARCHAR(16) NOT NULL, -- 规则快照：事后可复现当时判定
    day_basis_snapshot  VARCHAR(8)  NOT NULL, -- CALENDAR / BUSINESS
    stage_threshold_snapshot JSONB NOT NULL,  -- {"M1":30,"M2":60,"M3":90}
    outstanding    BIGINT      NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'OPEN',
        -- OPEN / RESOLVED / ESCALATED / DEFAULTED
    escalated_at   TIMESTAMPTZ,
    resolved_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_overdue_loan UNIQUE (loan_id)
);

CREATE INDEX IF NOT EXISTS idx_or_dealer ON overdue_records (dealer_id, status);
CREATE INDEX IF NOT EXISTS idx_or_stage  ON overdue_records (current_stage_code, dpd DESC);

COMMENT ON TABLE  overdue_records IS '逾期记录（append-only 事实）。uk_overdue_loan 保证一笔借据同时仅一条，跨 M 档只升档不改记录数';
COMMENT ON COLUMN overdue_records.current_stage_code IS '当前 DPD 阶段；由 DpdCalculator 计算，规则快照三列保证历史可复现';
COMMENT ON COLUMN overdue_records.stage_threshold_snapshot IS '判定当时阈值快照。规则改后历史仍可按快照复算，故规则只禁用不删除';

-- ---------------------------------------------------------------------------
-- ⑤ dealer_risk_profile：经销商贷后画像（只读投影，全量重算 + data_as_of）
-- ---------------------------------------------------------------------------
-- 铁律：画像表没有 vin / loan_no / 任何明细；不得反向写 loans；不参与任何流程判定。
-- risk_tier（贷后画像 A/B/C/D）≠ risk_grade（授信评级，留在 dealers）。
CREATE TABLE IF NOT EXISTS dealer_risk_profile (
    id                  BIGSERIAL PRIMARY KEY,
    dealer_id           BIGINT UNIQUE NOT NULL,     -- 一个经销商一行

    -- ---- 敞口类 ----
    active_loan_count   INT    NOT NULL DEFAULT 0,
    outstanding_total   BIGINT NOT NULL DEFAULT 0,
    credit_usage_ratio  NUMERIC(7,4),               -- used/approved
    oldest_due_date     DATE,

    -- ---- 逾期汇总（只放 MAX / COUNT，不含明细）----
    current_overdue_count   INT    NOT NULL DEFAULT 0,   -- COUNT(loans WHERE status='OVERDUE')，按借据计数
    current_overdue_amount  BIGINT NOT NULL DEFAULT 0,
    current_worst_stage     VARCHAR(16),
    max_overdue_days        INT    NOT NULL DEFAULT 0,   -- MAX(loans.max_dpd_ever)
    overdue_count_12m       INT    NOT NULL DEFAULT 0,
    avg_overdue_days_12m    NUMERIC(8,2),
    worst_stage_12m         VARCHAR(16),

    -- ---- 违约汇总 ----
    defaults_last_12m       INT    NOT NULL DEFAULT 0,   -- DECLARED 且近 365 天，被 RESTATED 更正的不计
    defaults_total          INT    NOT NULL DEFAULT 0,
    default_amount_12m      BIGINT NOT NULL DEFAULT 0,
    last_default_at         TIMESTAMPTZ,
    days_since_last_default INT,

    -- ---- 扣款汇总 ----
    deduction_fail_count_30d  INT NOT NULL DEFAULT 0,
    deduction_fail_rate_30d   NUMERIC(7,4),             -- 分母为 0 时置 NULL 而非 0
    deduction_fail_streak_max INT NOT NULL DEFAULT 0,

    -- ---- 分层结果 ----
    risk_tier           VARCHAR(4),          -- A / B / C / D（贷后画像，非授信评级）
    tier_reason         VARCHAR(500),        -- 触发的最关键 2~3 条理由原文
    tier_rule_code      VARCHAR(48),
    tier_rule_version   VARCHAR(16),
    scored_at           TIMESTAMPTZ,

    -- ---- 口径元数据 ----
    data_as_of          DATE   NOT NULL,     -- 数据截止业务日：任何画像查询都要能回答"截至哪天"
    computed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    computed_by         VARCHAR(32),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_drp_tier ON dealer_risk_profile (risk_tier, max_overdue_days DESC);
CREATE INDEX IF NOT EXISTS idx_drp_asof ON dealer_risk_profile (data_as_of);

COMMENT ON TABLE  dealer_risk_profile IS '经销商贷后画像（只读投影）。全量重算，不参与任何贷后流程判定；故意停算不影响放款/冻结/催收/处置';
COMMENT ON COLUMN dealer_risk_profile.risk_tier IS '贷后画像分层 A/B/C/D。与 dealers.risk_grade（授信评级）是两套指标，严禁混用';
COMMENT ON COLUMN dealer_risk_profile.data_as_of IS '数据截止业务日。没有它，报表口径永远说不清';
COMMENT ON COLUMN dealer_risk_profile.max_overdue_days IS 'MAX(该经销商全部借据的 max_dpd_ever)，非按车统计';
