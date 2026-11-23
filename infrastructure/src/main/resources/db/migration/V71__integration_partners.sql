-- ===========================================================================
-- V71：integration.partners + integration.partner_credentials（W-U3 / R11.2）
-- ===========================================================================
--
-- 【为什么两张表而不是一张】
-- `partners` 单行只有一个 `secret_ref` + 一个 `key_version` + 一个 `key_grace_until`，
-- 这个形状**表达不了 D02 §5.6 要求的「宽限期内新旧两版同时可验」**——要验旧版就得同时持有
-- 两个版本的密钥引用，而单行只有当前版；第二次轮换还会把第一次的历史彻底抹掉。
-- D13 §3 G9（§4.5 WU3-04）据此把版本历史落成 `partner_credentials`（每版一行、历史不删），
-- `partners` 上的三列降级为**指向本表的缓存**。
-- 留缓存列而不是删掉，是因为 V71 之前的设计集里到处引用 `partners.key_version`；
-- 删列属破坏性变更且需同步改 9 份文档，本期以「以本表为准」的注释约束收口。
--
-- 【INV-4 的落点】两张表都**不存明文密钥**，只存 `secret_ref`（keystore 引用）与
-- `secret_fingerprint`（前 8 位指纹，供管理台核对）。解密只发生在 DMZ 侧（D02 §5.6 INV-4）。
-- 「任何接口/日志/报文不可见密钥」由 core 侧管理端点的响应组装（不回显 secret_ref 之外的字段）
-- 与 D11 §4 的 INV-4 证伪实验共同保证。
--
-- 【授权面】DMZ 对这两张表**只读**（D04 §4.1 逐表授权清单）：
--   partners / partner_credentials 的写方是 core 管理台（PARTNER_CONFIG_MANAGE / PARTNER_KEY_ROTATE），
--   若 DMZ 可写，它就能自己插一行 status='ACTIVE' 的密钥版本让任意伪造报文通过验签——
--   那是给失守的 DMZ 留一条自签闸门，正是 INV-1 想防的事。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- integration.partners：partner 注册主数据（R11.2）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS integration.partners (
    id                  BIGSERIAL       PRIMARY KEY,                    -- 内部主键，不出现在任何对外报文（R7.3 不暴露内部 ID）
    partner_code        VARCHAR(64)     NOT NULL UNIQUE,                -- X-Partner-Code 的取值域，全局唯一
    partner_name        VARCHAR(128),                                   -- 展示名，审计与告警文案用
    -- 类型取自 D00 §8.4 PartnerType（10 个取值）。用 VARCHAR + CHECK 而不是 PG ENUM：
    -- 与主干既有表一致（R13.4 双层约束），且新增一种 partner 类型不必 ALTER TYPE。
    partner_type        VARCHAR(24)     NOT NULL,
    -- 状态三值：ACTIVE 放行 / SUSPENDED 熔断或人工停用 / RETIRED 退役（不再接受任何调用）。
    -- 停用只影响**新**调用，不追溯作废已产生的回执（D11 §4.1 E-1 第 4 行）。
    status              VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    -- ⚠️ 以下三列是 partner_credentials 的**缓存**，以 partner_credentials 为准（D04 §4.6）。
    --   只存引用与指纹前 8 位，不存明文、不存密文正文——INV-4。
    secret_ref          VARCHAR(128),                                   -- keystore 引用名/路径，接口不回显明文
    secret_fingerprint  VARCHAR(64),                                    -- 前 8 位指纹，供管理台核对
    key_version         INT,                                            -- 当前 ACTIVE 版本号（缓存值）
    key_grace_until     TIMESTAMPTZ,                                    -- 轮换宽限期截止，期内旧版仍可验
    allowed_ips         TEXT,                                           -- CSV，支持 CIDR；空 = 不按 IP 限制（生产必须非空，属部署核对项）
    allowed_scopes      TEXT,                                           -- CSV 端点权限集；D00 §8.4 与 D07 §3 的取值不一致，见实现说明待裁决项
    rate_limit_per_min  INT            NOT NULL DEFAULT 300,            -- D07 §2.6 默认值；滑动窗口计数在 DMZ 内存，多实例各自计数
    receipt_sla_hours   INT,                                            -- 回执 SLA，驱动指令 EXPIRED 判定（D09 §5.1）
    protocol            VARCHAR(16),                                    -- API / SFTP_FILE / PORTAL（D-04 未签，可空）
    endpoint_base       VARCHAR(256),                                   -- 出站基地址；DMZ 出站时拼接
    contact             VARCHAR(128),                                   -- 联系方式（运营对运营，不进报文）
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by          VARCHAR(64),                                    -- 录入人，取 AuditActor，不从请求体读（AGENTS §6.3）
    CONSTRAINT chk_partner_type CHECK (partner_type IN (
        'AUCTION', 'WMS', 'CUSTODIAN', 'CERT_DEPOSITORY', 'LEGAL_AGENT',
        'REGISTRY', 'CREDIT_BUREAU', 'VALUATION_VENDOR', 'BANK', 'INTERNAL')),
    CONSTRAINT chk_partner_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'RETIRED')),
    CONSTRAINT chk_partner_protocol CHECK (protocol IS NULL OR protocol IN ('API', 'SFTP_FILE', 'PORTAL')),
    -- 限流阈值必须为正：0 或负数会让「限流」变成「全拒」或「全放」，两种都不是本意。
    CONSTRAINT chk_partner_rate_limit CHECK (rate_limit_per_min IS NULL OR rate_limit_per_min > 0)
);

COMMENT ON TABLE  integration.partners IS 'partner 注册主数据（R11.2）：写方 core 管理台，DMZ 只读；密钥面以 partner_credentials 为准';
COMMENT ON COLUMN integration.partners.secret_ref IS 'keystore 引用，非明文；任何 GET 响应不回显（D02 INV-4）';
COMMENT ON COLUMN integration.partners.key_version IS '缓存列：当前 ACTIVE 版本，权威值在 partner_credentials（D04 §4.6）';
COMMENT ON COLUMN integration.partners.key_grace_until IS '轮换宽限期截止点；期内旧版可验，期外旧版一律拒绝（D07 §2.2）';
COMMENT ON COLUMN integration.partners.allowed_scopes IS 'CSV 端点权限集；scope 越权回 403 PTNR_002（D07 §2.5）';

-- ---------------------------------------------------------------------------
-- integration.partner_credentials：密钥版本历史（每版一行，历史不删）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS integration.partner_credentials (
    id                  BIGSERIAL       PRIMARY KEY,
    partner_code        VARCHAR(64)     NOT NULL,                       -- 逻辑引用 partners.partner_code（跨上下文用逻辑引用，不建 FK，D04 §1.1）
    -- 与 X-Key-Version 直接对齐；**单调递增**由应用层保证（PARTNER_KEY_ROTATE 只增不减）。
    key_version         INT             NOT NULL,
    secret_ref          VARCHAR(128)    NOT NULL,                       -- keystore 引用，不落明文（INV-4）
    secret_fingerprint  VARCHAR(64),                                    -- 前 8 位指纹，供管理台核对与吊销定位
    -- ACTIVE 当前版 / GRACE 宽限期内旧版 / REVOKED 已吊销（泄露或超宽限期）
    status              VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    valid_from          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    valid_until         TIMESTAMPTZ,                                    -- ACTIVE 为空；GRACE/REVOKED 必填（见下方 CHECK）
    rotated_by          VARCHAR(64),                                    -- 轮换操作人，取 AuditActor，**不从请求体读**
    rotate_reason       VARCHAR(256),                                   -- 例行轮换 / 泄露吊销
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT chk_credential_status CHECK (status IN ('ACTIVE', 'GRACE', 'REVOKED')),
    -- 「非 ACTIVE 必须给出 valid_until」做成库级事实：宽限期判定不能靠应用记性，
    -- 否则一行 GRACE 忘了填截止点就等于永久有效（轮换闸门形同虚设）。
    -- 另一半（ACTIVE 必须为空）**不建 CHECK**：ACTIVE 预填一个未来截止点是无害的保守形态，
    -- 强制为空只会让「计划内到期」无法表达。这一取舍已在实现说明登记。
    CONSTRAINT chk_credential_grace_deadline CHECK (status = 'ACTIVE' OR valid_until IS NOT NULL)
);

-- 每方每版本只一行；重复轮换出同版本号是编程错误，必须在库层挡住。
CREATE UNIQUE INDEX IF NOT EXISTS uq_credential_partner_version
    ON integration.partner_credentials (partner_code, key_version);

-- 每方**只允许一版 ACTIVE**（D04 §4.6）。用部分唯一索引而不是表内 UNIQUE(...) WHERE
-- ——后者是非法语法（D04 §1.1，V19 踩过）。
CREATE UNIQUE INDEX IF NOT EXISTS uq_credential_active_one
    ON integration.partner_credentials (partner_code) WHERE status = 'ACTIVE';

-- 验签主查询：按 (partner_code, X-Key-Version) 取凭据行。
CREATE INDEX IF NOT EXISTS idx_credential_partner_status
    ON integration.partner_credentials (partner_code, status);

COMMENT ON TABLE  integration.partner_credentials IS '密钥版本历史（D04 §4.6）：每版一行、历史不删；宽限期要同时验新旧两版';
COMMENT ON COLUMN integration.partner_credentials.status IS 'ACTIVE 当前版 / GRACE 宽限期旧版（不删，只由清理作业置 REVOKED）/ REVOKED 已吊销';
COMMENT ON COLUMN integration.partner_credentials.rotated_by IS '轮换操作人，来自 AuditActor；审计动作 PARTNER_SECRET_ROTATED 的另一半落 audit_events';

-- ---------------------------------------------------------------------------
-- 逐表授权（DMZ 只读；不授写权限的理由见文件头）
-- ---------------------------------------------------------------------------
GRANT SELECT ON integration.partners             TO floorplan_dmz;
GRANT SELECT ON integration.partner_credentials  TO floorplan_dmz;
-- BIGSERIAL 的隐式序列：DMZ 不向这两张表插行，本行只为「后续建表时序列已覆盖」的当刻兜底，
-- 真正的写序列需求在 V72/V73/V74 的授权段。
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA integration TO floorplan_dmz;
