-- ===========================================================================
-- V19：放车放证指令（B2 出库指令环节）—— 六张表 + 指令编号序列
-- ===========================================================================
--
-- 【为什么独立建一个服务的表】
-- 出库指令是"跨机构异步协同"：发出 → 等对方确认 → 放车 → 解抵，
-- 节奏以天计，且依赖外部响应。它不能与 core 的同步账本事务共表共库事务——
-- 否则账本会被外部机构的响应时间拖住（这也是 release-service 独立成服务的理由）。
--
-- 【六张表分工】（按《放车放证指令设计》§2，明细表/状态历史表按任务卡补充）
--   ① release_orders        指令主表：六要素 + 状态 + 通道
--   ② release_order_items   指令明细：一台车对应的 VEHICLE/CERTIFICATE/KEY 释放项
--   ③ release_state_history 状态历史：每次流转留痕，可回答"谁在何时把车放了"
--   ④ release_receipts      回执：接收方回传的实际释放内容与现场证据
--   ⑤ custodians            接收方（监管方）：Q1 未定，四类 custodian_type 全建模
--   ⑥ redemptions           赎车申请：指令的来源凭证（本服务只读引用，不写）
--
-- 【状态机口径（任务卡为准）】
--   PENDING_RELEASE → SENT → CONFIRMED → RELEASED → LIEN_DISCHARGED
--   另有 CANCELLED 终态。核心约束：
--     · 未经 CONFIRMED 不得 RELEASED（禁止跳步放车）
--     · 未 RELEASED 不得 LIEN_DISCHARGED（禁止未放车先解抵）
--   状态枚举放 VARCHAR(24)——LIEN_DISCHARGED 长度 15，留足扩展余地。
--
-- 【金额与时间】
--   金额一律 BIGINT（整数日元，禁止浮点）；时间一律 TIMESTAMPTZ（带时区，日本业务）。
--   风格照抄 V15__bank_payment_collection.sql。
-- ===========================================================================

-- 指令编号：RO-{YYYY}-{6位序号}，全局唯一、一旦生成不复用。
-- 用数据库序列而非 count(*)+1：并发下 count 会撞号，而指令是法律凭证，撞号不可接受。
CREATE SEQUENCE IF NOT EXISTS release_order_no_seq START WITH 1 INCREMENT BY 1;

-- ---------------------------------------------------------------------------
-- ① 释放指令主表
-- ---------------------------------------------------------------------------
-- 【为什么不设指向 redemptions/custodians 的外键】
-- 这两个实体分属其他限界上下文（赎车申请/监管方配置），由别的服务维护。
-- 跨服务外键会让本服务的写入依赖别人的库表与迁移顺序，
-- 拆库时第一件事就是删外键——所以从一开始就不加，只存逻辑 ID。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS release_orders (
    id                  BIGSERIAL PRIMARY KEY,

    -- ---- 指令身份与来源 ----
    order_no            VARCHAR(32)  NOT NULL UNIQUE,   -- RO-{YYYY}-{6位序号}，法律凭证，不可复用
    redemption_id       BIGINT       NOT NULL,          -- 赎车申请 ID（逻辑引用，跨上下文）
    dealer_id           BIGINT       NOT NULL,          -- 经销商
    vin                 VARCHAR(32)  NOT NULL,          -- 车架号

    -- ---- 指令六要素（设计稿 §1.2 ⑤）----
    certificate_no      VARCHAR(64),                   -- 车辆合格证号（不是法务局登记证明书）
    custodian_id        BIGINT,                        -- 接收方/监管方（逻辑引用，Q1 待定）
    release_items       VARCHAR(64)  NOT NULL,         -- 释放内容：VEHICLE,CERTIFICATE,KEY（逗号分隔）
    valid_until         TIMESTAMPTZ  NOT NULL,         -- 有效期（默认 7 个日历日）

    -- ---- 状态机 ----
    status              VARCHAR(24)  NOT NULL DEFAULT 'PENDING_RELEASE',
        -- PENDING_RELEASE 待下发 / SENT 已下发 / CONFIRMED 对方已确认 /
        -- RELEASED 已放车 / LIEN_DISCHARGED 已解押 / CANCELLED 已撤销
    sent_at             TIMESTAMPTZ,                   -- 下发时间（PENDING_RELEASE → SENT）
    confirmed_at        TIMESTAMPTZ,                   -- 回执确认时间（SENT → CONFIRMED）
    released_at         TIMESTAMPTZ,                   -- 放车时间（CONFIRMED → RELEASED）
    lien_discharged_at  TIMESTAMPTZ,                   -- 解抵时间（RELEASED → LIEN_DISCHARGED）
    cancelled_at        TIMESTAMPTZ,

    -- ---- 设计稿保留字段（下发/回执/审计）----
    issued_at           TIMESTAMPTZ,                   -- 生成指令时间
    issued_by           VARCHAR(64),                   -- 下发人（来自令牌的自然人）
    acknowledged_at     TIMESTAMPTZ,                   -- 监管方接收确认时间
    rejected_reason     VARCHAR(500),
    delivery_channel    VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',
        -- MANUAL 人工转交 / PORTAL 监管方门户 / API 系统直连 / MQ 消息
        -- Q9/Q10 未定：起步人工转交，真实 WMS 对接是 ReleaseChannel 的 TODO 接缝
    delivered_at        TIMESTAMPTZ,
    receipt_id          BIGINT,                        -- 回执 ID（逻辑引用）

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- 同一台车同一有效期不重复发指令：防止重复点击生成两张法律凭证
    CONSTRAINT uk_ro_vin UNIQUE (vin, valid_until)
);

-- 状态 + 有效期是巡检主入口："ISSUED/SENT 超期未回执"的就是资损敞口
CREATE INDEX IF NOT EXISTS idx_ro_status ON release_orders (status, valid_until);
CREATE INDEX IF NOT EXISTS idx_ro_dealer ON release_orders (dealer_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ro_vin     ON release_orders (vin);

COMMENT ON TABLE  release_orders IS '放车放证指令主表。状态机 PENDING_RELEASE→SENT→CONFIRMED→RELEASED→LIEN_DISCHARGED，另 CANCELLED';
COMMENT ON COLUMN release_orders.certificate_no IS '车辆合格证号——与法务局登记事项证明书严格区分，后者用于抹消，不是放证对象';
COMMENT ON COLUMN release_orders.order_no IS '指令编号 RO-{YYYY}-{6位序号}，来自 release_order_no_seq，生成后永不复用';

-- ---------------------------------------------------------------------------
-- ② 指令明细：一次放车可能放车 / 放证 / 放钥匙的组合
-- ---------------------------------------------------------------------------
-- 【为什么把设计稿的 release_items VARCHAR 再拆一张明细表】
-- 设计稿用一个逗号分隔字段表达组合，但"放车没放证"是真实的法律状态差异
-- （车交付了、物权凭证没转移），必须能逐项记录释放时间与结果。
-- 字符串解析做不到这一点，所以主表保留原字段做展示，明细表承载逐项事实。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS release_order_items (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT       NOT NULL REFERENCES release_orders (id),
    vin         VARCHAR(32)  NOT NULL,
    item_type   VARCHAR(24)  NOT NULL,
        -- VEHICLE 放车 / CERTIFICATE 放证 / KEY 放钥匙（只作事实记录，日本渠道不扣押钥匙）
    item_no     VARCHAR(64),                           -- 对应凭证号（如合格证编号）
    released    BOOLEAN      NOT NULL DEFAULT FALSE,   -- 该项是否已实际释放
    released_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_roi_order_item UNIQUE (order_id, item_type)
);

CREATE INDEX IF NOT EXISTS idx_roi_order ON release_order_items (order_id);
COMMENT ON TABLE release_order_items IS '指令释放项明细。逐项记录 VEHICLE/CERTIFICATE/KEY 的实际释放，支持"放车未放证"的部分释放';

-- ---------------------------------------------------------------------------
-- ③ 状态历史：审计轨迹，不可更新、不可删除
-- ---------------------------------------------------------------------------
-- 【为什么必须落历史，而不是只存当前 status】
-- 指令是法律凭证，事后要回答"谁在何时把状态从 CONFIRMED 推到 RELEASED"。
-- 只有当前状态无法回答；而且巡检要对比"指令已 RELEASED 但车辆台账未更新"。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS release_state_history (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT       NOT NULL REFERENCES release_orders (id),
    from_status VARCHAR(24),                           -- null 表示建单（无前驱状态）
    to_status   VARCHAR(24)  NOT NULL,
    action      VARCHAR(32)  NOT NULL,                 -- CREATE / DISPATCH / CONFIRM / RELEASE / DISCHARGE_LIEN / CANCEL
    operator    VARCHAR(64),                           -- 操作人（令牌自然人或 partner_code）
    reason      VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rsh_order ON release_state_history (order_id, id);
COMMENT ON TABLE release_state_history IS '指令状态流转历史。只追加不修改，是放车放证的审计轨迹';

-- ---------------------------------------------------------------------------
-- ④ 回执：接收方回传的实际释放内容
-- ---------------------------------------------------------------------------
-- 【为什么异常类型必须能表达】
-- REJECTED 拒收 / PARTIAL 部分释放 / DELAYED 延迟——
-- 三种异常对应三种不同的工单处置；只记"成功/失败"无法解释账实缺口。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS release_receipts (
    id              BIGSERIAL PRIMARY KEY,
    receipt_no      VARCHAR(32)  NOT NULL UNIQUE,
    release_order_id BIGINT      NOT NULL REFERENCES release_orders (id),

    released_items  VARCHAR(64),                       -- 实际释放内容（可能部分释放）
    released_at     TIMESTAMPTZ  NOT NULL,             -- 实际释放时间
    photos          VARCHAR(512),                      -- 现场照片附件 ID 列表
    odometer_km     INTEGER,                           -- 交付里程（证据链）
    receiver_name   VARCHAR(64),                       -- 接收人签字
    receiver_note   VARCHAR(500),

    exception_type  VARCHAR(24),                       -- REJECTED / PARTIAL / DELAYED
    exception_reason VARCHAR(500),

    confirmed_by    VARCHAR(64),                       -- 监管方操作人（非我方自然人）
    confirmed_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    source          VARCHAR(16)  NOT NULL DEFAULT 'PORTAL',
        -- PORTAL / API / MANUAL 人工转录 / MQ；两类来源在审计上必须可区分
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_receipt_ro ON release_receipts (release_order_id);
COMMENT ON TABLE release_receipts IS '监管方回执。异常类型 REJECTED/PARTIAL/DELAYED 必须可表达，否则账实缺口无法解释';

-- ---------------------------------------------------------------------------
-- ⑤ 接收方 / 监管方（Q1：接收方是谁未定 → 四类全建模，按 type 区分）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS custodians (
    id              BIGSERIAL PRIMARY KEY,
    custodian_code  VARCHAR(32)  NOT NULL UNIQUE,
    custodian_name  VARCHAR(128) NOT NULL,
    custodian_type  VARCHAR(24)  NOT NULL,
        -- THIRD_PARTY_WAREHOUSE 第三方监管仓 / ON_SITE_SUPERVISOR 驻场监管员 /
        -- CERTIFICATE_DEPOSITORY 合格证保管点 / DEALER_WAREHOUSE 经销商自有仓库
    contact_name    VARCHAR(64),
    contact_phone   VARCHAR(32),
    contact_email   VARCHAR(128),
    -- 外部通道对接信息（鉴权见 ADR-009；密钥只存引用不存明文）
    api_endpoint    VARCHAR(256),
    api_secret_ref  VARCHAR(128),
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON TABLE custodians IS '监管方/接收方配置。Q1 业务未定：四类 custodian_type 全部建模，不下结论';

-- ---------------------------------------------------------------------------
-- ⑥ 赎车申请（指令的来源凭证；本服务只按 ID 引用，不负责其状态流转）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS redemptions (
    id              BIGSERIAL PRIMARY KEY,
    redemption_no   VARCHAR(32)  NOT NULL UNIQUE,
    dealer_id       BIGINT       NOT NULL,
    vin             VARCHAR(32)  NOT NULL,
    requester       VARCHAR(64)  NOT NULL,             -- 自然人或经销商 partner_code
    request_channel VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',
        -- MANUAL 人工录入 / PARTNER_API 经销商系统推送

    -- 资金校验（环节②，由结算服务写入，本服务只读）
    fund_verified       BOOLEAN      NOT NULL DEFAULT FALSE,
    verified_account_ref VARCHAR(32),
    verified_amount     BIGINT,                        -- 整数日元
    verified_at         TIMESTAMPTZ,
    verified_by         VARCHAR(64),

    status          VARCHAR(16)  NOT NULL DEFAULT 'REQUESTED',
        -- REQUESTED / FUND_VERIFIED / ALLOCATED / SECURITY_RELEASED /
        -- ORDER_ISSUED / CONFIRMED / CANCELLED
    cancelled_at    TIMESTAMPTZ,
    cancel_reason   VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- 同一台车只允许一条未撤销赎车申请：撤销后可重新申请
    CONSTRAINT uk_redemption_vin UNIQUE (vin) WHERE status <> 'CANCELLED'
);
COMMENT ON TABLE redemptions IS '赎车申请（设计稿§2①）。release-service 仅按 ID 引用，资金校验/冲抵/解押归结算与担保服务';
