-- ===========================================================================
-- V66：WMS 在库快照 / 明细 / 账实差异（W-U4，R6.2 / R6.3）
-- ===========================================================================
--
-- 【规格来源】D04 §3.17（snapshots + items）、§3.18（reconcile_diffs）；
--   入站报文 D07 §3.4（I-05）；用例 D06 §6.3（UC-R6-3/4/5/6）；
--   差异不可静默 D09 §9、D10 §2（RECON_IGNORE 二人）、RB-3。
--
-- 【三张表的分工，为什么不能压成一张】
--   wms_inventory_snapshots  = 对方报"我这儿有什么"的**一次事实**（不可变）
--   wms_inventory_items      = 该次事实的逐台明细（唯一 (snapshot_id, vin)）
--   wms_reconcile_diffs      = 我方把这遍事实与自己的台账**比出来的结论**
-- 事实与结论分表是本项目一贯口径（V17 把流水/匹配/释放分三张表的同一条理由）：
-- 快照重算只应产生新的结论，绝不能改写对方的原始申报。
--
-- 【不可变性怎么保证】
-- 不建 UPDATE 触发器（那是把业务规则藏进框架回调，ADR-002 的反面），
-- 改由两条可见的机制：
--   ① `status` 三值 + 「同日重推 → 新行，旧行置 SUPERSEDED」的应用层规则（D06 §6.3）；
--   ② 幂等锚点 UNIQUE (snapshot_key)，重放同一快照不会新增第二行，只会命中已有行返回首次结果。
-- 快照内容列（item_count 等）由明细重算得到，不作为对账依据参与写入。
--
-- 【依赖】D04 §2：V66 依赖 V65（本包内）。不引用其他包的对象。
-- 存量安全：全部 CHECK 的取值集合 = 本迁移新建列的默认域，存量行只会取默认值，VALIDATE 不会失败。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- ① wms_inventory_snapshots（D04 §3.17 主表）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS wms_inventory_snapshots (
    id                        BIGSERIAL PRIMARY KEY,
    snapshot_key              VARCHAR(96)  NOT NULL,      -- SNAP:{wh}-{yyyymmdd}，幂等锚点
    warehouse_custodian_code  VARCHAR(32)  NOT NULL,      -- 逻辑引用 custodians.custodian_code
    snapshot_date             DATE         NOT NULL,      -- 业务日期：对账窗口的锚，不用 TIMESTAMPTZ
    item_count                INTEGER      NOT NULL DEFAULT 0,
    received_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    partner_code              VARCHAR(64)  NOT NULL,
    source_message_id         BIGINT,                     -- 逻辑引用 integration.inbound_messages.id（V72）
    status                    VARCHAR(16)  NOT NULL DEFAULT 'RECEIVED',
        -- RECEIVED 已受理 / APPLIED 已参与对账 / SUPERSEDED 被同日重推取代
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_wms_snapshot_key UNIQUE (snapshot_key),
    CONSTRAINT chk_wms_snapshot_status
        CHECK (status IN ('RECEIVED', 'APPLIED', 'SUPERSEDED'))
);

-- 同日重推的可寻址入口：按 (仓, 日期) 捞当前有效快照（status <> 'SUPERSEDED'）
CREATE UNIQUE INDEX IF NOT EXISTS uk_wms_snapshot_active
    ON wms_inventory_snapshots (warehouse_custodian_code, snapshot_date)
    WHERE status <> 'SUPERSEDED';
CREATE INDEX IF NOT EXISTS idx_wms_snapshot_partner ON wms_inventory_snapshots (partner_code, snapshot_date DESC);

COMMENT ON TABLE  wms_inventory_snapshots IS
    'WMS 在库快照（I-05）。不可变事实：同日重推写新行并把旧行置 SUPERSEDED，绝不原地改写';
COMMENT ON COLUMN wms_inventory_snapshots.snapshot_key IS
    'SNAP:{warehouse_custodian_code}-{yyyymmdd}（D04 §3.17）。uk_wms_snapshot_key = 快照受理幂等锚点；'
    '注意它**不是** outbox 的 aggregate_key——事件的键是 wms_inbound_confirms.inbound_no / 本表 id（D00 §10.2）';
COMMENT ON COLUMN wms_inventory_snapshots.source_message_id IS
    '可空：人工导入的历史快照（U-R4 同形态）没有入站报文，必须能与 partner 推送区分开，'
    '因此本列不建 NOT NULL、不建外键（V72 在 W-U3 号段）';

-- ---------------------------------------------------------------------------
-- ② wms_inventory_items（D04 §3.17 明细）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS wms_inventory_items (
    id                        BIGSERIAL PRIMARY KEY,
    snapshot_id               BIGINT       NOT NULL REFERENCES wms_inventory_snapshots (id),
    vin                       VARCHAR(32)  NOT NULL,
    bin_location              VARCHAR(32),
    odometer_km               INTEGER,
    condition_summary         VARCHAR(500),
    inspection_certificate_no VARCHAR(64),
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_wms_item_snapshot_vin UNIQUE (snapshot_id, vin),
    CONSTRAINT chk_wms_item_odometer CHECK (odometer_km IS NULL OR odometer_km >= 0)
);

CREATE INDEX IF NOT EXISTS idx_wms_item_vin ON wms_inventory_items (vin);

COMMENT ON TABLE wms_inventory_items IS
    '快照明细。唯一 (snapshot_id, vin)：同一遍快照里重复 VIN 属对方数据错误，受理时按首条保留 + 建差异，不静默合并';

-- ---------------------------------------------------------------------------
-- ③ wms_reconcile_diffs（D04 §3.18）
-- ---------------------------------------------------------------------------
-- 【为什么差异表存双方快照摘要】
-- 差异会被忽略（IGNORED）。忽略之后原始快照仍要能解释"当时比出了什么"，
-- 否则 IGNORED 的理由就成了无法复核的空话（D09 §9、D10 §6.4「差异留痕」）。
-- our_snapshot / wms_snapshot 是比对现场的**证据快照**，不是对原始表的复制。
CREATE TABLE IF NOT EXISTS wms_reconcile_diffs (
    id                        BIGSERIAL PRIMARY KEY,
    biz_date                  DATE         NOT NULL,
    warehouse_custodian_code  VARCHAR(32)  NOT NULL,
    vin                       VARCHAR(32),                -- 仓级差异（如整仓无快照）可以为 NULL
    diff_type                 VARCHAR(24)  NOT NULL,
    severity                  VARCHAR(16)  NOT NULL,
    our_snapshot              TEXT,
    wms_snapshot              TEXT,
    status                    VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    work_order_id             BIGINT,                     -- 逻辑引用 work_orders.id；NULL = 未建工单（降级落点见 D06 §15）
    resolution_note           VARCHAR(500),
    actor                     VARCHAR(64),
    ignore_reason             VARCHAR(500),               -- IGNORED 必填，由应用层校验（D09 §9 / RB-3）
    ignored_by                VARCHAR(64),
    co_signed_by              VARCHAR(64),                -- 二人签认（D10 S-4：与 actor 不得相同）
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_wms_diff_type CHECK (diff_type IN
        ('MISSING_IN_WMS', 'EXTRA_IN_WMS', 'CERT_MISMATCH',
         'BIN_MISMATCH', 'ODOMETER_DIFF', 'CONDITION_DIFF')),
    CONSTRAINT chk_wms_diff_severity CHECK (severity IN ('INFO', 'WARNING', 'HIGH', 'CRITICAL')),
    CONSTRAINT chk_wms_diff_status CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'IGNORED')),
    -- MISSING_IN_WMS 恒 HIGH 的 DB 侧兜底（应用层 D06 §6.3 已先断言，双层不许只留一层）
    CONSTRAINT chk_wms_diff_missing_is_high CHECK (diff_type <> 'MISSING_IN_WMS' OR severity = 'HIGH'),
    -- 忽略必须留下理由 + 两个不同的人；缺任一项在数据库层就写不进去（D10 §6.4）
    CONSTRAINT chk_wms_diff_ignore_evidence CHECK (
        status <> 'IGNORED'
        OR (ignore_reason IS NOT NULL AND btrim(ignore_reason) <> ''
            AND ignored_by IS NOT NULL AND co_signed_by IS NOT NULL
            AND ignored_by <> co_signed_by)),
    -- 同一比对窗口的同类差异只有一条未关闭结论：重算不堆差异（D06 §6.3 幂等要求）
    CONSTRAINT chk_wms_diff_actor_present CHECK (
        status = 'OPEN' OR actor IS NOT NULL)
);

-- 唯一 (biz_date, warehouse_custodian_code, vin, diff_type)（D04 §3.18）。
-- vin 可为 NULL（仓级差异），而 PostgreSQL 里 NULL 互不相等 ⟹ 单建一条唯一索引
-- 会放进多条"同一天的同一仓级差异"。这里**不用** `NULLS NOT DISTINCT`（PG15+ 才有，
-- 项目声明 PG16 但主干与并行 schema 的实版本本轮无法验证（AGENTS.md §3 只声明版本，
-- 并行各 schema 由 agent_env.sh 建在同一个容器实例上，本轮禁止连库实证），
-- 改用等价的两条部分唯一索引：有 VIN 的按天+仓+VIN+类型，无 VIN 的按天+仓+类型。
CREATE UNIQUE INDEX IF NOT EXISTS uk_wms_diff_daily_vin
    ON wms_reconcile_diffs (biz_date, warehouse_custodian_code, vin, diff_type)
    WHERE vin IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_wms_diff_daily_wh
    ON wms_reconcile_diffs (biz_date, warehouse_custodian_code, diff_type)
    WHERE vin IS NULL;

CREATE INDEX IF NOT EXISTS idx_wms_diff_open ON wms_reconcile_diffs (status, severity, biz_date DESC);
CREATE INDEX IF NOT EXISTS idx_wms_diff_vin  ON wms_reconcile_diffs (vin, biz_date DESC);

COMMENT ON TABLE  wms_reconcile_diffs IS
    '账实差异结论（R6.3）。状态 OPEN/ACKNOWLEDGED/RESOLVED/IGNORED；'
    'IGNORED 需二人 + 理由，由 chk_wms_diff_ignore_evidence 在库层拦住单人静默忽略';
COMMENT ON COLUMN wms_reconcile_diffs.severity IS
    'MISSING_IN_WMS 恒 HIGH（我方认为在库、仓里说没有 = 担保物失踪面，D06 §6.3）；'
    'EXTRA_IN_WMS 由仓侧多出实物构成，不升 HIGH 但要出工单';
COMMENT ON COLUMN wms_reconcile_diffs.work_order_id IS
    '工单外键刻意不建：工单表属共享底座（V? 由收口方维护），且 D06 §15 已定降级落点为 '
    'risk_alerts + 门户待办；引用为空不代表差异没被跟进';
COMMENT ON COLUMN wms_reconcile_diffs.ignored_by IS
    '忽略动作的第一操作人（令牌自然人，D10 S-1：不得从请求体取）；co_signed_by 为第二人';
