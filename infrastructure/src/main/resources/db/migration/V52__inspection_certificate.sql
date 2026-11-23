-- =====================================================================
-- V52__inspection_certificate.sql
-- 用途：R2.5 / R5.1 車検証（ inspection certificate ）主数据 + R5.3 保管与权利瑕疵流水。
-- 归属包：W-U1。新建 public 表数 = +2（`inspection_certificates`、`certificate_custody_events`，
--   D04 §2.1 已把这两张表计在 V52）。
-- 定源：D04 §3.9 / §3.10 + D03 §2.5 + D00 §8.1–8.2 枚举字典。
--
-- 【本文件故意不含 uk_cert_active_vin】
--   「一车一证」部分唯一索引按批次计划（D04 §2）**落在 V53__certificate_constraint.sql**，
--   本文件只把表与列建出来。若在这里顺手建索引，V53 就成了空文件——AGENTS §6.1 禁止。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. inspection_certificates 車検証台账（R5.1 核心）
-- ---------------------------------------------------------------------
-- 为什么車検証必须独立成表：车辆是"占有即使用"的动产，日本二手车的权属凭证是
-- 車検証（登録証明書）的**持有 + 自動車登録ファイル的登记事实**，不是钥匙或 GPS。
-- 本表只登记"证"这一份事实；权利瑕疵的明细在 certificate_custody_events，
-- 聚合结论回写本表 encumbrance_status（D04 §3.9：明细在 §3.10）。
CREATE TABLE IF NOT EXISTS inspection_certificates (
    id                       BIGSERIAL    PRIMARY KEY,
    cert_key                 VARCHAR(96)  NOT NULL,
    certificate_no           VARCHAR(64)  NOT NULL,
    vin                      VARCHAR(32)  NOT NULL,
    registration_plate       VARCHAR(16),
    owner_name               VARCHAR(128),
    owner_address            VARCHAR(128),
    user_name                VARCHAR(128),
    user_address             VARCHAR(128),
    first_registered_on      DATE,
    inspection_expiry_date   DATE         NOT NULL,
    issue_date               DATE         NOT NULL,
    security_type            VARCHAR(32),
    mortgage_flag            BOOLEAN,
    encumbrance_status       VARCHAR(24),
    holder_custodian_code    VARCHAR(32),
    holder_type              VARCHAR(32),
    custody_location         VARCHAR(128),
    status                   VARCHAR(24)  NOT NULL,
    predecessor_id           BIGINT,
    pledged_at               TIMESTAMPTZ,
    released_at              TIMESTAMPTZ,
    lost_at                  TIMESTAMPTZ,
    release_order_no         VARCHAR(32),
    evidence_path            VARCHAR(512),
    evidence_digest          VARCHAR(64),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                  INT          NOT NULL DEFAULT 0,
    -- D00 §8.1 CertificateStatus 定源六态（RECEIVED/PLEDGED/RELEASE_PENDING/RELEASED/REISSUED/LOST）。
    -- D03 §3.2 状态图里还有第七个节点 AMENDED_IN_PROGRESS（换证在途）——它不在定源六态内，
    -- 本包**不自造第七个枚举值**：换证在途由 V52 的 custody 流水 event_type='AMENDED' 表达
    -- （九值里有它，是定源写法），换证完成时旧证直接置 REISSUED。
    -- 该七态↔六态差异已作为文档冲突上报（详见 docs/AGENT_TASK-W-U1-实现说明.md）。
    CONSTRAINT ck_cert_status CHECK (status IN
        ('RECEIVED', 'PLEDGED', 'RELEASE_PENDING', 'RELEASED', 'REISSUED', 'LOST')),
    -- D00 §8.2 EncumbranceType 六值；除 NONE 外全部硬阻断（P-7），阻断判定在应用/闸门侧
    CONSTRAINT ck_cert_encumbrance CHECK (encumbrance_status IS NULL
        OR encumbrance_status IN ('NONE', 'MORTGAGE', 'OWNERSHIP_RESERVATION',
                                  'ATTACHMENT', 'THEFT_REPORT', 'UNKNOWN')),
    -- USED 子集 [待定源 D-03] 未签 ⟹ 本 CHECK 取"全枚举并集"，不预先收窄成某个子集，
    -- 以免把未签的默认担保方式固化成数据库约束（收窄属业务裁决，不属本包）。
    CONSTRAINT ck_cert_security_type CHECK (security_type IS NULL
        OR security_type IN ('ASSIGNMENT', 'RETENTION', 'MANUFACTURER_REPURCHASE',
                             'INSPECTION_CERT_CUSTODY', 'MORTGAGE', 'TITLE_TRANSFER',
                             'OTHER', 'NONE')),
    -- predecessor_id 自引用：换证链（UC-R2-5）
    CONSTRAINT fk_cert_predecessor FOREIGN KEY (predecessor_id)
        REFERENCES inspection_certificates (id)
);

-- cert_key = CERT-{certificate_no}（D00 §13.1），本表幂等锚点：同证号重推不落第二行
CREATE UNIQUE INDEX IF NOT EXISTS uk_cert_key ON inspection_certificates (cert_key);
CREATE INDEX IF NOT EXISTS idx_cert_status ON inspection_certificates (status);
-- 車検到期预警作业（D06 §15 / R5.2）
CREATE INDEX IF NOT EXISTS idx_cert_expiry ON inspection_certificates (inspection_expiry_date);
-- 在库勾稽：按保管者取所持证（R5.4 与 WMS 快照比对的前半）
CREATE INDEX IF NOT EXISTS idx_cert_custodian
    ON inspection_certificates (holder_custodian_code, status);
-- 换证链追溯（旧证→新证）
CREATE INDEX IF NOT EXISTS idx_cert_predecessor ON inspection_certificates (predecessor_id);

COMMENT ON TABLE inspection_certificates IS '車検証台账（R5.1）。写入口：建账与换证=web(core) UsedVehicleMasterApplicationService（W-U1）；
    质押/解质/放证=release-service 与 collateral-reg-service（W-U4）。
    权属权威源**不是本表**——本表是 INSPECTION_CERT 这一路证据的载体，最终结论读 collateral_registration_batches（ADR-Q27 唯一权威源）；
    多源并存时的顺位与投影见 V54。';
COMMENT ON COLUMN inspection_certificates.cert_key IS '幂等锚点 CERT-{certificate_no}（D00 §13.1）';
COMMENT ON COLUMN inspection_certificates.certificate_no IS '証書番号（車検証番号）。与 VIN 的一致性由 CERT_003 校验（D06 G-D1）；同号换发新证走 predecessor_id 成链';
COMMENT ON COLUMN inspection_certificates.owner_name IS '所有者（抵押权/让渡担保结构下常≠使用者，必须分列存）';
COMMENT ON COLUMN inspection_certificates.inspection_expiry_date IS '車検満了日（NOT NULL：无证检到期日的車検証不能作为建账依据）';
COMMENT ON COLUMN inspection_certificates.security_type IS '本证承载的担保形态（SecurityType）。USED 域默认值 [待定源 D-03] 未签，本列只存事实不做默认';
COMMENT ON COLUMN inspection_certificates.encumbrance_status IS '权利瑕疵聚合结论（EncumbranceType）：明细见 certificate_custody_events。
    除 NONE 外任一项均硬阻断质押（D03 §2.5 I3 / P-7）；UNKNOWN 按不通过处理，不得因"没查"放行';
COMMENT ON COLUMN inspection_certificates.holder_custodian_code IS '保管者（逻辑引用 custodians.code，V65/W-U4 扩列，本包不建 FK）';
COMMENT ON COLUMN inspection_certificates.status IS '六态之一。状态一律经领域方法流转（domain CertificateStateMachine），禁止 UPDATE ... SET status=...；
    REISSUED/LOST 是"退出在场"的两种终态，其余四态各只允许一张有效证（V53 的 uk_cert_active_vin 兜底）';
COMMENT ON COLUMN inspection_certificates.predecessor_id IS '换证链：新证指向旧证，旧证置 REISSUED（UC-R2-5「同 VIN 証書番号变更有历史」）';
COMMENT ON COLUMN inspection_certificates.release_order_no IS '放证线关联指令号（逻辑引用 release_orders.order_no，V17；写侧属 W-U4）';

-- ---------------------------------------------------------------------
-- 2. certificate_custody_events 证的保管与核查流水（R2.5 R5.3）
-- ---------------------------------------------------------------------
-- 这是「谁在什么时候对这张证做了什么」的 append-only 账，也是 SOD 与审计的证据面。
-- 不叫 audit_events 而单独建表：审计表记操作，本表记**证的状态历史与核查结论**，
-- 二者维度不同（同一动作可能写多条状态迁移）。
CREATE TABLE IF NOT EXISTS certificate_custody_events (
    id               BIGSERIAL   PRIMARY KEY,
    certificate_id   BIGINT      NOT NULL REFERENCES inspection_certificates (id),
    event_type       VARCHAR(24) NOT NULL,
    from_status      VARCHAR(24),
    to_status        VARCHAR(24),
    encumbrance_type VARCHAR(24),
    check_source     VARCHAR(24),
    actor_type       VARCHAR(16) NOT NULL,
    actor            VARCHAR(64) NOT NULL,
    occurred_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    evidence_path    VARCHAR(512),
    reason           VARCHAR(500),
    trace_id         VARCHAR(64),
    CONSTRAINT ck_cce_event_type CHECK (event_type IN
        ('RECEIVED', 'PLEDGED', 'TRANSFERRED', 'RELEASED', 'REISSUED',
         'LOST', 'AMENDED', 'ENCUMBRANCE_FOUND', 'CHECK_DONE')),
    CONSTRAINT ck_cce_encumbrance CHECK (encumbrance_type IS NULL
        OR encumbrance_type IN ('NONE', 'MORTGAGE', 'OWNERSHIP_RESERVATION',
                                'ATTACHMENT', 'THEFT_REPORT', 'UNKNOWN')),
    -- D00 §8.2 RegistryCheckSource 三值：USED 域不含 OEM_PORTAL（R5.3 明文）
    CONSTRAINT ck_cce_check_source CHECK (check_source IS NULL
        OR check_source IN ('MANUAL', 'REGISTRY_AGENT', 'REGISTRY_API')),
    CONSTRAINT ck_cce_actor_type CHECK (actor_type IN ('USER', 'PARTNER'))
);

CREATE INDEX IF NOT EXISTS idx_cce_cert_time
    ON certificate_custody_events (certificate_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_cce_type_time
    ON certificate_custody_events (event_type, occurred_at DESC);

COMMENT ON TABLE certificate_custody_events IS '車検証保管/质押/换证/核查流水（append-only，R2.5 + R5.3）。
    actor 二义性显式建模：actor_type=USER 时 actor 是自然人（来自 AuditActor，不由请求体自报），
    actor_type=PARTNER 时 actor 是 partner_code（R12.2）。';
COMMENT ON COLUMN certificate_custody_events.event_type IS '九值定源（D04 §3.10）。AMENDED=换证/記載変更**在途**事件：
    因为定源状态枚举只有六态、没有"换证中"这一态，在途事实就记在这里（见 V52 头注的七态↔六态冲突）';
COMMENT ON COLUMN certificate_custody_events.check_source IS '登记核查来源：MANUAL / REGISTRY_AGENT（代办回执）/ REGISTRY_API。
    USED 域**禁用** LEGACY 的 OEM_PORTAL（厂家门户查不到二手车权属，R5.3）；
    存量 financing_lien_checks.check_source 仍含 OEM_PORTAL，收紧在 V54';
COMMENT ON COLUMN certificate_custody_events.encumbrance_type IS '本条流水发现的瑕疵类型；与聚合结论列（inspection_certificates.encumbrance_status）分列存，便于"何时由谁发现"可追溯';
COMMENT ON COLUMN certificate_custody_events.trace_id IS '链路追踪号，与 audit_events.trace_id 对齐（同一动作两处可勾稽）';
