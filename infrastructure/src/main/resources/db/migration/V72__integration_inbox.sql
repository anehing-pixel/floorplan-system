-- ===========================================================================
-- V72：integration.inbound_messages + integration.nonces（W-U3 / R11.2 R11.3）
-- ===========================================================================
--
-- 【这两张表是 DMZ 与 core 之间唯一的入站接触面】
-- partner → DMZ 只写这两张表；core 只读 inbound_messages 并推进其处理态（D02 §5.7 例外声明）。
-- 出站方向完全相反，见 V73。
--
-- 【R11.3 的两条硬要求如何落成库级事实】
--   ① 「signature_ok=false 也必须留痕」→ 验签失败同样 INSERT 一行，不返回就不写。
--      但只写文字规约等于靠自觉，所以本文件加了 D04 §4.3 补的 CHECK：
--      签名未过的行**只能**是 REJECTED_BAD_SIGNATURE / DUPLICATE 两个终态，
--      永远进不了轮询部分索引的可领取集合——这就是 D11 A5.7 在 DB 层的唯一落点。
--      （D13 §4.5 WU3-07 的另一方案是新增终态 SIGNATURE_FAILED；G9 未了结项写「待 W-U3 确认取哪种」。
--        本包**不新增枚举值**，沿用 D00 §8.4 已有的 REJECTED_BAD_SIGNATURE + CHECK 约束：
--        D00 §8.4 是枚举定源、并行包不得各自扩枚举，且 CHECK 已使「未验签的行被领走」
--        在库层不可表示，新终态不带来额外保证。此取舍登记在实现说明。）
--   ② 「不存原文」→ 只有 payload_ref（本地文件路径）+ payload_digest（SHA-256）+
--      payload_preview（截断且脱敏）。全文进文件系统，不进集成库，
--      否则集成库会变成第二个业务真相源。
--
-- 【幂等键的列面取舍】
-- D04 §4.3 定的是单列 idempotency_key UNIQUE，本文件照抄，不改列面。
-- D13 §4.5 WU3-09 建议 (partner_code, endpoint, idempotency_key)：跨 partner 误命中会
-- 把 A 方的 message_id 回给 B 方，既是漏吞也是信息泄露。本包的处置是
-- **在应用层挡住**（DMZ 命中已有行时先比 partner_code，不同则回 409 且不回显对方 message_id），
-- 列面变更留作收口裁决项。为什么不直接改列面：改唯一键形状 = 改 D04 定稿的库规格，
-- 与「文档定源、代码照实现」的并行纪律冲突，且会影响 W-U4 已按 D04 写的对账断言。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- integration.inbound_messages：入站收件箱（队列，不是 outbox）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS integration.inbound_messages (
    id                 BIGSERIAL       PRIMARY KEY,                     -- 即对外的 message_id（D07 §3.9 查询接口用它；内部主键，不参与业务判定）
    idempotency_key    VARCHAR(160)    NOT NULL UNIQUE,                 -- 对方显式传的 Idempotency-Key，或按 D07 §2.4 由业务组合键派生
    partner_code       VARCHAR(64)     NOT NULL,                        -- 逻辑引用 partners；验签前就要读，故必须落库（审计主体，R12.2）
    endpoint           VARCHAR(128),                                    -- 如 /external/wms/inbound-confirm；反查与 WU3-09 的处置都用它
    -- message_type 取值域见 D04 §4.3 的十一个值，用 CHECK 兜住（R13.4 双层约束）。
    -- DISPOSAL_RESULT / MARKET_QUOTE 是 I-09 结果回收与 I-12 行情回传复用的类型（D07 §4.3）。
    message_type       VARCHAR(48)     NOT NULL,
    -- 验签结果。**false 也必须落库**（R11.3），攻击尝试是审计对象。
    signature_ok       BOOLEAN         NOT NULL,
    signature_reason   VARCHAR(256),                                    -- 失败原因分类（TIMESTAMP_SKEW/NONCE_REPLAY/SIGNATURE_MISMATCH/KEY_VERSION_REJECTED…）
    nonce              VARCHAR(64),                                     -- 原样存，用于事后关联 nonces 行；不参与任何判定
    timestamp_header   BIGINT,                                          -- 对方上报时间，**epoch 毫秒**（D07 §2.2 定源；D02 §5.4 写「秒」属未同步，见实现说明）
    payload_ref        VARCHAR(512),                                    -- 本地文件路径：原始报文留存（需求书 §9）。库里不留全文
    payload_digest     VARCHAR(64),                                     -- 正文 SHA-256（小写十六进制）：幂等比对与签名末段都用它
    payload_preview    TEXT,                                            -- 截断且脱敏摘要，排障用；见下方 CHECK 的约束说明
    status             VARCHAR(24)     NOT NULL,                        -- MessageStatus（D00 §8.4），入站/出站共用
    attempts           INT             NOT NULL DEFAULT 0,              -- core 消费重试次数；上限 3，与 event_consumptions 对齐
    last_error         TEXT,                                            -- 最后一次失败摘要。**不得含对方可读的堆栈/密钥**（D13 §4.5 WU3-15）
    business_ref       VARCHAR(64),                                     -- 反查用：vin / order_no / settlement_no（D07 §3.9 与 core 列表页）
    trace_id           VARCHAR(64),                                     -- 对方 X-Trace-Id 原值，缺失时由请求过滤器生成
    client_ip          VARCHAR(64),                                     -- 来源 IP，留痕与白名单核对
    received_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    processed_at       TIMESTAMPTZ,                                     -- core 处理完成时间；未处理为空
    CONSTRAINT chk_inbox_message_type CHECK (message_type IN (
        'AUCTION_LOT', 'AUCTION_SETTLEMENT', 'WMS_INBOUND', 'WMS_SNAPSHOT', 'WMS_RECEIPT',
        'REGISTRATION_RESULT', 'BANK_TXN', 'COMPLIANCE_RESULT', 'MARKET_QUOTE', 'DISPOSAL_RESULT')),
    CONSTRAINT chk_inbox_status CHECK (status IN (
        'RECEIVED', 'ACCEPTED', 'REJECTED_BAD_SIGNATURE', 'DUPLICATE',
        'PENDING', 'PROCESSED', 'FAILED', 'DEAD')),
    -- ★ A5.7 的库级落点：签名未过 ⇒ 只能落两个不可领取终态。
    --   没有这条，「失败也留痕」与「失败不许被消费」就只是应用层自觉。
    CONSTRAINT chk_inbox_unverified_not_claimable CHECK (
        signature_ok = TRUE OR status IN ('REJECTED_BAD_SIGNATURE', 'DUPLICATE')),
    -- attempts 上限写进 CHECK：轮询的「满 3 次置 DEAD」若被绕过，坏消息会永久占住批次。
    CONSTRAINT chk_inbox_attempts CHECK (attempts >= 0 AND attempts <= 3),
    -- 留痕完整性：验签失败的行必须给出原因，否则审计只拿到一个布尔值，无法定位是哪道闸门拦的。
    CONSTRAINT chk_inbox_reason_present CHECK (signature_ok = TRUE OR signature_reason IS NOT NULL)
);

COMMENT ON TABLE  integration.inbound_messages IS '入站收件箱（R11.2）：DMZ 写、core 读并推进处理态；只存引用与摘要，不存报文全文';
COMMENT ON COLUMN integration.inbound_messages.signature_ok IS 'false 也必须落库（R11.3）；配合 CHECK 保证未验签行不可被核心领取（D11 A5.7）';
COMMENT ON COLUMN integration.inbound_messages.payload_ref IS '原始报文只落本地文件系统（需求书 §9），集成库不做业务真相源';
COMMENT ON COLUMN integration.inbound_messages.payload_preview IS '截断+脱敏摘要；PII 与签名材料不得出现（D07 §12 / WU3-15）';
COMMENT ON COLUMN integration.inbound_messages.idempotency_key IS '单列全局 UNIQUE 按 D04 §4.3；跨 partner 误命中的处置在应用层，见实现说明 WU3-09 项';

-- 拉取主查询（core 轮询）：只覆盖可领取状态，部分索引让未验签行在物理上不在扫描范围内。
CREATE INDEX IF NOT EXISTS idx_inbox_poll
    ON integration.inbound_messages (status, received_at)
    WHERE status IN ('RECEIVED', 'ACCEPTED', 'FAILED');

-- business_ref 反查（core 列表页 / D07 §7.8 inbox 查询 / 排障）。
CREATE INDEX IF NOT EXISTS idx_inbox_business
    ON integration.inbound_messages (business_ref);

-- 安全审计：按时间捞验签失败尝试（D07 §3 各接口的「失败也留痕」都落这里）。
CREATE INDEX IF NOT EXISTS idx_inbox_sigfail
    ON integration.inbound_messages (received_at)
    WHERE signature_ok = FALSE;

-- ---------------------------------------------------------------------------
-- integration.nonces：重放防护（DMZ 自清 TTL）
-- ---------------------------------------------------------------------------
-- 主键即去重约束：(partner_code, nonce) 只能有一行，插入冲突 = 重放。
-- 为什么按 partner 分键：不同 partner 各自随机生成的 nonce 撞值不该互相误判为重放。
CREATE TABLE IF NOT EXISTS integration.nonces (
    partner_code  VARCHAR(64)  NOT NULL,                                -- 逻辑引用 partners
    nonce         VARCHAR(64)  NOT NULL,                                -- 16–64 位十六进制（D07 §2.2）
    received_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),                  -- 首次见到该 nonce 的时刻
    expires_at    TIMESTAMPTZ  NOT NULL,                                -- TTL 到期点，由 NonceCleanupJob 清理
    CONSTRAINT chk_nonce_ttl CHECK (expires_at > received_at)
);

ALTER TABLE integration.nonces DROP CONSTRAINT IF EXISTS pk_nonces;
ALTER TABLE integration.nonces ADD CONSTRAINT pk_nonces PRIMARY KEY (partner_code, nonce);

-- 清理作业扫描索引（每日一次，按到期点捞）。
CREATE INDEX IF NOT EXISTS idx_nonce_expiry ON integration.nonces (expires_at);

COMMENT ON TABLE  integration.nonces IS '防重放 nonce 去重表（R11.3）：写方与清理方都是 DMZ；TTL 定源见实现说明（D00 §12=86400s 与 D06 §16=600s 冲突）';
COMMENT ON COLUMN integration.nonces.expires_at IS 'TTL 由配置项 floorplan.used.integration.nonce-ttl-seconds 决定；只有验签通过的请求才会占用 nonce';

-- ---------------------------------------------------------------------------
-- 逐表授权（D04 §4.1）
-- ---------------------------------------------------------------------------
-- 收件箱：DMZ 只能新增，不能改——状态迁移归 core 领取方。
-- 若给 UPDATE，DMZ 就能把自己写下的 REJECTED_BAD_SIGNATURE 改成 RECEIVED 绕过 A5.7，
-- 那条 CHECK 也只挡住「signature_ok=false」这一半。
GRANT SELECT, INSERT ON integration.inbound_messages TO floorplan_dmz;
-- 防重放表：DMZ 自清 TTL，故需要 DELETE。
GRANT SELECT, INSERT, DELETE ON integration.nonces   TO floorplan_dmz;
-- inbound_messages 是 BIGSERIAL，DMZ 插入要 nextval 权限。
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA integration TO floorplan_dmz;
