-- ===========================================================================
-- V18：担保权属权威源（collateral-reg-service 的私有表）
-- ===========================================================================
--
-- 【为什么另建权威表，不直接复用 V10 的三张表】
-- V10 已有 collateral_ledger / collateral_registration_batches /
-- collateral_registration_items，分别回答"台账状态""批次登记""批次↔车辆明细"。
-- 但"某 VIN 当前由谁享有担保、登记编号多少、是否生效"这个放款门禁问题，
-- 在三处各有一份且互不一致（即"担保三源不一致"痛点）。
-- 本迁移建立**唯一权威权属**收敛点：
--   ① collateral_registration          当前权属（一 VIN 一行，可覆盖更新）
--   ② collateral_registration_history  权属流水（登记/变更/抹消，只追加）
--   ③ collateral_source_snapshot       三源快照（每次上报留痕，供冲突裁决回溯）
--
-- 【表名说明：为什么流水表叫 history 而不是 items】
-- 任务卡原拟名 collateral_registration_items 已被 V10 第185行占用
-- （批次↔车辆明细，含 batch_id/ledger_id 非空 FK）。若此处以同名建"流水表"，
-- CREATE TABLE IF NOT EXISTS 在已应用 V10 的库上会**静默跳过**，
-- 仓储按本迁移的列读写时将直接运行时报错。故流水表改名
-- collateral_registration_history，语义（每次登记/变更/抹消一条）不变。
--
-- 【Q27 阻塞：不写死判定规则】
-- "担保以哪张表为准""什么算 REGISTERED/三源冲突如何裁决"业务口径尚未书面确认。
-- 本迁移只提供结构与留痕，不把判定规则固化进 schema；判定由 collateral 服务的
-- AuthoritativeSourceStrategy 以可配置开关承载（默认关闭，待 Q27 定源后再启用）。
-- 在开关打开前，查询一律 fail-closed 返回 NONE，core 据此拒付。
-- ===========================================================================

-- ===========================================================================
-- ① collateral_registration：一 VIN 一条当前权属
-- ===========================================================================
CREATE TABLE IF NOT EXISTS collateral_registration (
    vin             VARCHAR(32)  PRIMARY KEY,             -- 车架号：权威权属主键，一台一行
    owner_name      VARCHAR(256),                         -- 权利人（担保权人）
    reg_no          VARCHAR(64),                          -- 登记编号（法务省登记编号，字符串）
    status          VARCHAR(16)  NOT NULL DEFAULT 'NONE', -- REGISTERED / PENDING / CANCELLED / NONE
    amount          BIGINT,                               -- 担保债权额（整数日元）
    registered_at   TIMESTAMPTZ,                          -- 登记生效时间
    discharged_at   TIMESTAMPTZ,                          -- 抹消/解除时间
    source          VARCHAR(32),                          -- 当前权属采信的来源（见 SourceSystem）
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_cr_status CHECK (status IN ('REGISTERED', 'PENDING', 'CANCELLED', 'NONE'))
);

-- 门禁按状态批量扫描（VIN 直查走主键，此索引供运营排查"已登记清单"）
CREATE INDEX IF NOT EXISTS idx_cr_status ON collateral_registration (status)
    WHERE status = 'REGISTERED';

COMMENT ON TABLE collateral_registration IS '担保权属权威表：一 VIN 一条当前权属；Q27 定源前查询 fail-closed 返回 NONE';
COMMENT ON COLUMN collateral_registration.source IS '当前权属采信来源：REGISTRATION_BATCH / COLLATERAL_LEDGER / VEHICLE_UNITS';
COMMENT ON COLUMN collateral_registration.status IS '权属状态：REGISTERED/PENDING/CANCELLED/NONE；判定口径待 Q27 书面确认';
COMMENT ON COLUMN collateral_registration.amount IS '担保债权额（整数日元）';

-- ===========================================================================
-- ② collateral_registration_history：权属流水/历史（每次登记、变更、抹消一条）
-- ===========================================================================
-- 只追加、不更新：当前权属放 collateral_registration，历史全部留在本表，
-- 使"谁来核对这笔权属是什么时候、依据哪个源改的"可追溯。
CREATE TABLE IF NOT EXISTS collateral_registration_history (
    id           BIGSERIAL    PRIMARY KEY,
    vin          VARCHAR(32)  NOT NULL,               -- 车架号
    event_type   VARCHAR(16)  NOT NULL,               -- REGISTER / CHANGE / DISCHARGE / SNAPSHOT_ADOPT
    reg_no       VARCHAR(64),                         -- 本次事件的登记编号
    owner_name   VARCHAR(256),                        -- 本次事件的权利人
    status       VARCHAR(16)  NOT NULL,               -- 本次事件落定后的状态
    amount       BIGINT,                              -- 担保债权额（整数日元）
    source       VARCHAR(32),                         -- 触发来源（人工登记写 MANUAL；快照裁决写源编码）
    note         VARCHAR(500),                        -- 备注/裁决理由
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now(), -- 事件发生时间
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(), -- 落库时间
    CONSTRAINT chk_crh_event CHECK (event_type IN ('REGISTER', 'CHANGE', 'DISCHARGE', 'SNAPSHOT_ADOPT')),
    CONSTRAINT chk_crh_status CHECK (status IN ('REGISTERED', 'PENDING', 'CANCELLED', 'NONE'))
);

CREATE INDEX IF NOT EXISTS idx_crh_vin_occurred ON collateral_registration_history (vin, occurred_at DESC);

COMMENT ON TABLE collateral_registration_history IS '担保权属流水：登记/变更/抹消/快照裁决各追加一条，只追加不更新';
COMMENT ON COLUMN collateral_registration_history.event_type IS '事件类型：REGISTER/CHANGE/DISCHARGE/SNAPSHOT_ADOPT';
COMMENT ON COLUMN collateral_registration_history.note IS '备注：快照裁决时记录裁决理由，供 Q27 定源后回看';

-- ===========================================================================
-- ③ collateral_source_snapshot：三源快照（每源上报状态留痕，供冲突裁决）
-- ===========================================================================
-- 三源 = vehicle_units（车辆主数据担保字段）/ collateral_ledger（担保台账）/
--        collateral_registration_batches（登记批次表）。
-- 每次导入各写一行快照，不覆盖：这样 Q27 定源后能回溯"当时三个源各说啥"，
-- 而不是只剩一个被拍脑袋改过的结果。
CREATE TABLE IF NOT EXISTS collateral_source_snapshot (
    id           BIGSERIAL    PRIMARY KEY,
    vin          VARCHAR(32)  NOT NULL,
    source       VARCHAR(32)  NOT NULL,               -- VEHICLE_UNITS / COLLATERAL_LEDGER / REGISTRATION_BATCH
    status       VARCHAR(16)  NOT NULL,               -- 该源上报的状态
    reg_no       VARCHAR(64),                         -- 该源上报的登记编号
    owner_name   VARCHAR(256),                        -- 该源上报的权利人
    amount       BIGINT,                              -- 该源上报的担保债权额（整数日元）
    raw_payload  JSONB,                               -- 原始上报报文（争议时自证）
    captured_at  TIMESTAMPTZ  NOT NULL DEFAULT now(), -- 上报/采集时间
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_css_source CHECK (source IN ('VEHICLE_UNITS', 'COLLATERAL_LEDGER', 'REGISTRATION_BATCH')),
    CONSTRAINT chk_css_status CHECK (status IN ('REGISTERED', 'PENDING', 'CANCELLED', 'NONE'))
);

-- 裁决要按 (vin, source) 取最新快照
CREATE INDEX IF NOT EXISTS idx_css_vin_source_captured
    ON collateral_source_snapshot (vin, source, captured_at DESC);

COMMENT ON TABLE collateral_source_snapshot IS '担保三源快照：每次上报留痕，供冲突裁决回溯（Q27 定源前不参与门禁判定）';
COMMENT ON COLUMN collateral_source_snapshot.source IS '来源：VEHICLE_UNITS / COLLATERAL_LEDGER / REGISTRATION_BATCH';
COMMENT ON COLUMN collateral_source_snapshot.raw_payload IS '原始上报报文（JSONB），争议时自证';
