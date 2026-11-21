-- ===========================================================================
-- V11：贷后监管（事件总线 · 风险预警 · 工单） + 融资担保期限模型 + 抹消状态语义订正
-- ===========================================================================
-- 本迁移三件事：
--   ① 抹消状态语义订正：新增 NOT_DUE（未到抹消时点），把误标的 NOT_REQUIRED 订正回来
--   ② 融资担保期限模型：vehicle_units 加到期日/宽限期/罚息利率/期限来源 + 期限变更历史表
--   ③ 贷后监管三件套：domain_events（事件总线）+ risk_alerts（预警）+ work_orders（工单）
--
-- 【为什么必须有 ①】
-- 登记批次刚建立时我把 cancellation_status 默认成了 NOT_REQUIRED，
-- 而 markReleased 用 NOT_REQUIRED 判断"这批车不需要抹消"——
-- 两个语义被混用，导致**债务结清后不产生抹消待办**，
-- 担保在法务局登记簿上一直挂着却无人跟进。这是让渡担保监管最忌讳的漏管。
-- 修正：NOT_DUE = 将来要抹消（只是还没到时间）；NOT_REQUIRED = 永远不需要抹消。

-- ===========================================================================
-- ① 抹消状态语义订正
-- ===========================================================================

ALTER TABLE collateral_registration_batches
    DROP CONSTRAINT IF EXISTS chk_cancel_status;

ALTER TABLE collateral_registration_batches
    ADD CONSTRAINT chk_cancel_status CHECK (cancellation_status IN
        ('NOT_DUE', 'NOT_REQUIRED', 'REQUESTED', 'APPLIED', 'DONE', 'CANCELLED'));

COMMENT ON COLUMN collateral_registration_batches.cancellation_status IS
    '抹消状态：NOT_DUE=未到抹消时点（担保存续中，将来需抹消）/ REQUESTED=待共同申请 / APPLIED=已提交 / DONE=抹消完成 / NOT_REQUIRED=确定无需抹消 / CANCELLED=免于抹消';

-- 数据订正：登记刚建立、尚未结清的批次，NOT_REQUIRED → NOT_DUE
-- 判定依据：cancellation_status='NOT_REQUIRED' 且该批次下的车都未结清（released=false）。
-- 已结清但状态仍是 NOT_REQUIRED 的，不在此处订正（那是历史逻辑留下的空状态，
-- 交由 markReleased 重算时修正，避免误伤）。
UPDATE collateral_registration_batches b
SET cancellation_status = 'NOT_DUE'
WHERE b.cancellation_status = 'NOT_REQUIRED'
  AND NOT EXISTS (
      SELECT 1
      FROM collateral_registration_items i
      JOIN collateral_ledger l ON l.id = i.ledger_id
      WHERE i.batch_id = b.id
        AND l.released = TRUE
  );

-- ===========================================================================
-- ② 融资担保期限模型
-- ===========================================================================
-- 【为什么必须有】
-- 现有 vehicle_units 40 个字段中没有任何期限字段，系统**根本无法判定**
-- "这台车是不是逾期"——只能判"售出后有没有还钱"（行为驱动）。
-- 而合同约定的是**时间驱动**（约定的最长期限过了没有）。
-- 后果：一台车放款后一直不卖、库龄也没到递减触发点，
-- 即使合同期限早已届满，系统永远不会把它标为逾期。
--
-- 另：overdue_days 字段虽存在，但全库只有放款归零与展期归零两处写入，
-- **从未被计算过**（死字段）。本迁移配套激活其计算逻辑。

ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS maturity_date        DATE;
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS grace_period_days    INTEGER;
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS overdue_rate         NUMERIC(10,6);
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS term_source          VARCHAR(16);
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS term_confirmed_at   TIMESTAMPTZ;
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS term_confirmed_by   VARCHAR(64);

COMMENT ON COLUMN vehicle_units.maturity_date IS '融资担保期限到期日（合同约定）——逾期判定与罚息计时的基准';
COMMENT ON COLUMN vehicle_units.grace_period_days IS '合同约定的宽限期（天）；逾期自 到期日+宽限期 起算';
COMMENT ON COLUMN vehicle_units.overdue_rate IS '逾期罚息年利率（可空=沿用基准利率）';
COMMENT ON COLUMN vehicle_units.term_source IS '期限来源：CONTRACT 合同录入 / REGISTRATION 登记带出 / MANUAL_REVIEW 人工读合同 / INFERRED 系统推断 / UNKNOWN 未知';
COMMENT ON COLUMN vehicle_units.term_confirmed_by IS '期限确认人（自然人；人工读合同的路径必填）';
COMMENT ON COLUMN vehicle_units.term_confirmed_at IS '期限确认时间';

-- 期限历史：原始期限 + 展期链 + 人工修订，全部留痕
-- 多次展期要能回答"最初约定到哪天、展了哪几次、每次延到哪天"
CREATE TABLE IF NOT EXISTS financing_terms (
    id             BIGSERIAL PRIMARY KEY,
    vin            VARCHAR(32) NOT NULL,
    change_type    VARCHAR(24) NOT NULL,
        -- ORIGINAL 初始约定 / EXTENSION 展期顺延 / MANUAL_REVIEW 人工读合同补录 /
        -- CORRECTION 人工修正 / TERMINATED 结清或处置终止
    old_maturity_date DATE,
    new_maturity_date DATE NOT NULL,
    old_grace_days INTEGER,
    new_grace_days INTEGER,
    term_source    VARCHAR(16) NOT NULL,
    source_ref     VARCHAR(128),          -- 依据（合同附件 ID / 登记批次号 / 工单号）
    reason         VARCHAR(500),
    operator       VARCHAR(64) NOT NULL,   -- 操作人（自然人）
    operated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_financing_terms_vin ON financing_terms (vin);
CREATE INDEX IF NOT EXISTS idx_financing_terms_at ON financing_terms (operated_at DESC);

COMMENT ON TABLE financing_terms IS '融资担保期限变更历史：原始约定 + 展期链 + 人工修订，全程可追溯';
COMMENT ON COLUMN financing_terms.source_ref IS '期限依据（合同附件ID / 登记批次号 / 工单号）——人工读合同路径必填';

-- 期限缺失巡检用：放款后 N 天内未录入期限的车
CREATE INDEX IF NOT EXISTS idx_vu_term_missing ON vehicle_units (advanced_at)
    WHERE maturity_date IS NULL AND term_source IS NULL;

-- ===========================================================================
-- ③-1 domain_events：统一事件总线
-- ===========================================================================
-- 所有外部数据（DMS/主机厂/银行/盘点App/法务省/人工补录）先落事件表，
-- 再由应用层消费。好处：
--   ① 幂等有物理保障（唯一约束在数据库层，重复推送不会重复处理）
--   ② 失败可重放（处理失败留在表里，人工/定时可重试）
--   ③ 原始载荷留存（双方数据冲突时要能回看"对方到底报了什么"）
--   ④ 对账有依据（外部文件与内部台账的差异可逐笔追到事件行）

CREATE TABLE IF NOT EXISTS domain_events (
    id            BIGSERIAL PRIMARY KEY,
    event_type    VARCHAR(48)  NOT NULL,
        -- VEHICLE_SOLD / VEHICLE_DELIVERED / VEHICLE_INVOICED / VEHICLE_SHIPPED /
        -- VEHICLE_REGISTERED / PAYMENT_RECEIVED / SALES_INVOICE_UPLOADED /
        -- INSPECTION_CERT_UPLOADED / AUDIT_RESULT / REGISTRATION_RECEIPT /
        -- CANCELLATION_COMPLETED / MANUAL_CORRECTION
    source        VARCHAR(32)  NOT NULL,
        -- DMS / OEM / BANK / AUDIT_APP / LEGAL / MANUAL / SYSTEM
    dealer_id     BIGINT,
    vin           VARCHAR(32),
    biz_key       VARCHAR(128) NOT NULL,   -- 业务主键（幂等锚点，如银行流水号）
    payload       JSONB        NOT NULL,   -- 外部原始载荷（防腐层转换前）
    ref_type      VARCHAR(32),             -- 转换后引用的对象类型
    ref_id        VARCHAR(64),             -- 转换后引用的对象 ID
    event_time    TIMESTAMPTZ  NOT NULL,   -- 事件发生时间（对方系统时间，非我方接收时间）
    received_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    status        VARCHAR(16)  NOT NULL DEFAULT 'RECEIVED',
        -- RECEIVED 已接收 / PROCESSED 已处理 / FAILED 处理失败 /
        -- IGNORED 判定为无效 / DUPLICATED 重复
    error_detail  TEXT,
    processed_at  TIMESTAMPTZ,
    CONSTRAINT uk_domain_event UNIQUE (source, event_type, biz_key)
);

CREATE INDEX IF NOT EXISTS idx_domain_events_status ON domain_events (status, received_at);
CREATE INDEX IF NOT EXISTS idx_domain_events_vin ON domain_events (vin);
CREATE INDEX IF NOT EXISTS idx_domain_events_type ON domain_events (event_type, event_time DESC);

COMMENT ON TABLE domain_events IS '统一事件总线：外部与人工数据统一入口，保证幂等、可重放、可对账';

-- ===========================================================================
-- ③-2 risk_alerts：风险预警实例
-- ===========================================================================
-- 与既有 alerts 表并存：alerts 是"其他流程顺带产生的告警"（语义清晰，已被 9 处代码使用），
-- risk_alerts 承载"风险维度主动算出来的预警"，两者不合并。

CREATE TABLE IF NOT EXISTS risk_alerts (
    id              BIGSERIAL PRIMARY KEY,
    alert_code      VARCHAR(48) NOT NULL,     -- 预警类型
    severity        VARCHAR(8)  NOT NULL,     -- LOW/MEDIUM/HIGH/CRITICAL
    dealer_id       BIGINT,
    vin             VARCHAR(32),
    batch_id        BIGINT,
    metric_value    NUMERIC(18,4),            -- 触发时的指标值
    threshold_value NUMERIC(18,4),           -- 当时的阈值快照（阈值可改，事后复盘要能复现）
    detail          TEXT,
    evidence_refs   JSONB,                    -- 依据（事件ID / 附件ID / 证据链ID）
    first_seen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    occurrence_count INTEGER NOT NULL DEFAULT 1,
    status          VARCHAR(16) NOT NULL DEFAULT 'OPEN'
        -- OPEN 待处理 / ACKNOWLEDGED 已受理 / IN_PROGRESS 处理中 /
        -- RESOLVED 已解决 / DISMISSED 已忽略（误报）/ AUTO_CLOSED 自动闭环
);

-- 同一预警未闭环时唯一。
-- 【修正 2026-10-26】原写法把带 WHERE 的唯一性写成了表内 CONSTRAINT：
--   CONSTRAINT uk_risk_alert_open UNIQUE (COALESCE(vin,''), ...) WHERE status IN (...)
-- PostgreSQL 的表内 UNIQUE 约束①不支持 WHERE 子句（部分唯一只能建独立索引），
-- ②不允许在约束列里使用 COALESCE 这类表达式。该语法错误导致 V11 从未成功应用，
-- 整个迁移在 v11 处回滚，后面的 V12 也因此一直跑不到。改为独立的部分唯一索引。
CREATE UNIQUE INDEX IF NOT EXISTS uk_risk_alert_open
    ON risk_alerts (alert_code, COALESCE(vin, ''), COALESCE(batch_id, 0))
    WHERE status IN ('OPEN','ACKNOWLEDGED','IN_PROGRESS');

CREATE INDEX IF NOT EXISTS idx_risk_alerts_status ON risk_alerts (status, severity);
CREATE INDEX IF NOT EXISTS idx_risk_alerts_dealer ON risk_alerts (dealer_id);
CREATE INDEX IF NOT EXISTS idx_risk_alerts_vin ON risk_alerts (vin);

COMMENT ON TABLE risk_alerts IS '风险预警实例：规则引擎产出，带阈值快照与去重计数';
COMMENT ON COLUMN risk_alerts.threshold_value IS '触发时的阈值快照——阈值可配置，历史预警须能复现当时依据';

-- ===========================================================================
-- ③-3 risk_rules：预警阈值配置
-- ===========================================================================
-- 沿用既有先例：授信评级口径已是"后台动态配置 + 即时生效"（rating_configs 表）。
-- 预警阈值同理——改阈值不改代码。

CREATE TABLE IF NOT EXISTS risk_rules (
    id              BIGSERIAL PRIMARY KEY,
    rule_code       VARCHAR(48) UNIQUE NOT NULL,
    metric          VARCHAR(32)  NOT NULL,      -- 指标标识
    threshold_type  VARCHAR(16) NOT NULL,       -- GT / GTE / LT / LTE / OUTSIDE
    threshold_value NUMERIC(18,4) NOT NULL,
    unit            VARCHAR(16),                -- DAY / RATIO / COUNT / PERCENT
    severity        VARCHAR(8)  NOT NULL,
    window_days     INTEGER,                    -- 观察窗口（趋势类规则）
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    applies_to      VARCHAR(16) NOT NULL DEFAULT 'ALL',
    updated_by      VARCHAR(64),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE risk_rules IS '预警规则与阈值配置：后台可改，即时生效，勿写死在代码里';

-- ===========================================================================
-- ③-4 work_orders：工单
-- ===========================================================================
-- alerts 只能"标记已处理"——没有责任人、SLA、处置动作、处理记录、升级机制。
-- 贷后管理的核心是这些，告警表承载不了，故独立建表。

CREATE TABLE IF NOT EXISTS work_orders (
    id            BIGSERIAL PRIMARY KEY,
    order_no      VARCHAR(32) UNIQUE NOT NULL,
    order_type    VARCHAR(32) NOT NULL,
        -- TERM_CONFIRM 期限确认 / AUDIT_DIFF 盘点差异 / SOLD_UNPAID 已售未回款 /
        -- DOC_MISSING 资料补录 / REG_ISSUE 登记异常 / DATA_CONFLICT 数据冲突 /
        -- DISPOSAL 违约处置 / CREDIT_FREEZE 额度冻结解冻
    source_alert_id BIGINT REFERENCES risk_alerts (id),
    dealer_id     BIGINT,
    vin           VARCHAR(32),
    batch_id      BIGINT,
    severity      VARCHAR(8) NOT NULL,
    priority      VARCHAR(8) NOT NULL,      -- P1/P2/P3（与 severity 独立：severity 是风险度，priority 是处理顺序）
    status        VARCHAR(16) NOT NULL DEFAULT 'OPEN',
        -- OPEN 待受理 / ACKNOWLEDGED 已受理 / IN_PROGRESS 处理中 /
        -- PENDING_EXTERNAL 待外部 / RESOLVED 已解决 / CANCELLED 已取消
    assignee      VARCHAR(64),             -- 处理人（自然人，来自令牌身份）
    sla_due_at    TIMESTAMPTZ NOT NULL,     -- 处理时限（按工作日历计算）
    sla_breached  BOOLEAN NOT NULL DEFAULT FALSE,
    resolution    TEXT,
    resolution_code VARCHAR(32),
    created_by    VARCHAR(64),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    acknowledged_at TIMESTAMPTZ,
    resolved_at   TIMESTAMPTZ,
    CONSTRAINT chk_wo_status CHECK (status IN
        ('OPEN','ACKNOWLEDGED','IN_PROGRESS','PENDING_EXTERNAL','RESOLVED','CANCELLED'))
);

CREATE INDEX IF NOT EXISTS idx_wo_assignee ON work_orders (assignee, status);
CREATE INDEX IF NOT EXISTS idx_wo_sla ON work_orders (status, sla_due_at);
CREATE INDEX IF NOT EXISTS idx_wo_dealer ON work_orders (dealer_id);

CREATE TABLE IF NOT EXISTS work_order_actions (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT NOT NULL REFERENCES work_orders (id),
    action_type VARCHAR(32) NOT NULL,
        -- ACKNOWLEDGE 受理 / ASSIGN 转派 / COMMENT 备注 / REQUEST_DOC 索取资料 /
        -- ESCALATE 升级 / RESOLVE 解决 / CANCEL 取消 / ATTACH 附证据
    action_data JSONB,
    attachment_id BIGINT,                   -- 补录的证据（照片/证明书/银行回单）
    operator    VARCHAR(64) NOT NULL,       -- 操作人（自然人）
    operated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_woa_order ON work_order_actions (order_id);

COMMENT ON TABLE work_orders IS '贷后工单：异常分派给贷后人员，带 SLA 与处理记录';
COMMENT ON COLUMN work_orders.sla_due_at IS '处理时限（按工作日历计算——日本节假日不能按自然日算）';

-- ===========================================================================
-- ③-5 risk_concentration_snapshots：集中度历史快照
-- ===========================================================================
-- 集中度占比的分母每天在变，必须有历史序列才能算趋势。
CREATE TABLE IF NOT EXISTS risk_concentration_snapshots (
    id            BIGSERIAL PRIMARY KEY,
    biz_date      DATE NOT NULL,
    dimension     VARCHAR(16) NOT NULL,     -- DEALER 经销商 / MODEL 车型 / BRAND 品牌 / REGION 区域
    dimension_key VARCHAR(128) NOT NULL,    -- 维度取值（车型名/品牌/经销商编码）
    exposure      NUMERIC(18,2) NOT NULL,    -- 该维度敞口金额
    total_exposure NUMERIC(18,2) NOT NULL,   -- 当日总敞口
    ratio         NUMERIC(10,6) NOT NULL,    -- 占比
    vehicle_count INTEGER NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_rcs UNIQUE (biz_date, dimension, dimension_key)
);

CREATE INDEX IF NOT EXISTS idx_rcs_dim ON risk_concentration_snapshots (dimension, biz_date DESC);

COMMENT ON TABLE risk_concentration_snapshots IS '集中度每日快照：车型/品牌/经销商/区域敞口占比趋势';

-- ===========================================================================
-- ③-6 车辆残值字段（一阶段估算用）
-- ===========================================================================
-- 一阶段用车龄+里程折旧曲线估算残值率；真实行情（二阶段）与人工估价（三阶段）覆盖。
-- 一阶段估算值仅用于预警，不作为处置定价依据。
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS residual_value_estimated NUMERIC(18,2);
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS residual_value_source VARCHAR(16);
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS residual_value_at TIMESTAMPTZ;

COMMENT ON COLUMN vehicle_units.residual_value_estimated IS '估算残值（一阶段：车龄+里程折旧曲线）——仅用于预警，不作处置定价依据';
COMMENT ON COLUMN vehicle_units.residual_value_source IS '残值来源：ESTIMATED 估算 / MARKET 行情接入 / MANUAL 人工估价';

-- ===========================================================================
-- 自检：确认新增列与表已就位（SQL 结构自检 SqlStructureSelfCheck 另有启动期校验）
-- ===========================================================================
