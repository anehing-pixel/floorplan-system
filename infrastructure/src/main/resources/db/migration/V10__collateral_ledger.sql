-- V10: 担保物台账（_collateral_ledger）+ 登记档案 + 证据链
--
-- 背景：担保在系统里此前只是 vehicle_units 上的两个字段
-- （security_type / security_status），没有独立的"担保物台账"。
-- 后果是三个真实缺口：
--   ① 放款确认后车辆进 IN_STOCK，但**担保登记没有独立台账**，
--      无法回答"这台车的担保登在哪里、编号多少、证明书在哪、抹了没有"；
--   ② 车卖出去后担保物清单**不会自动扣减**，库存口径与担保物口径混在一起；
--   ③ 结清只把 security_status 翻成 RELEASED，**不产出抹消登记待办**——
--      而日本让渡担保的抹消登记需双方共同申请，漏掉就等于担保一直挂着。
--
-- 本迁移建三张表：
--   ① collateral_ledger        担保物台账（按 VIN 一台一行，四个状态独立标记）
--   ② collateral_registrations 登记档案（登记编号 / 证明书 / 抹消凭证）
--   ③ collateral_evidence_links 证据链（合同·登记·放款·盘点·回款·处置串起来）
--
-- 法律要点（决定了字段设计，勿随意删列）：
--   · 日本《譲渡担保契約及び所有権留保契約に関する法律》2025-05-30 成立、
--     2025-06-06 公布，**施行日最晚 2027-12-06**（公布后 2 年 6 个月内）——
--     现在尚未施行，故本表按"旧法（判例/实务）+ 新法施行后"双兼容设计：
--     登记方式字段同时容纳 占有改定 / 登记 / 引渡し，新法施行后不必改表。
--   · **让渡担保登记有 5 年期限且不能续展**（到期待重新设定）——
--     故 registration 必须带 registered_at + expires_at，系统要能算出到期清单。
--   · **抹消登记需权利人与登记义务人共同申请**——
--     故 cancellation 是一张独立"待办"（status 区分 REQUESTED/APPLIED/DONE），
--     不是简单翻个状态字段。

-- ===========================================================================
-- ① collateral_ledger：担保物台账（VIN 维度）
-- ===========================================================================
-- 设计要点：四个状态**独立成列**而非单一状态机，因为它们的时点不同：
--   financed（已放款）→ sold（已售出）→ repaid（已回款）→ released（已解除担保）
--   例如：已放款+已售出但未回款（结算窗口内）是常见中间态，
--   若压成单一状态就无法表达"在途回款"这类业务事实。
CREATE TABLE IF NOT EXISTS collateral_ledger (
    id              BIGSERIAL PRIMARY KEY,
    vin             VARCHAR(32) NOT NULL UNIQUE,   -- 车架号：台账主键（一台一行）
    dealer_id       BIGINT      NOT NULL,           -- 归属经销商

    -- ---- 四个独立状态（各自时点不同，不压成单一状态机）----
    financed        BOOLEAN     NOT NULL DEFAULT FALSE,  -- 已放款（垫款 PAID 时置真）
    financed_at     TIMESTAMPTZ,                        -- 放款确认时间（证据链起点）
    advance_no      VARCHAR(32),                        -- 关联垫款单号（证据链锚点）
    sold            BOOLEAN     NOT NULL DEFAULT FALSE,  -- 已售出（标记售出时置真）
    sold_at         TIMESTAMPTZ,                        -- 售出时间
    sold_flagged_by VARCHAR(64),                        -- 标记售出的操作人
    repaid          BOOLEAN     NOT NULL DEFAULT FALSE,  -- 已回款（还款核销时置真）
    repaid_at       TIMESTAMPTZ,                        -- 回款时间
    repaid_amount   BIGINT,                             -- 累计回款额（日元）
    released        BOOLEAN     NOT NULL DEFAULT FALSE,  -- 已解除担保（结清/处置后置真）
    released_at     TIMESTAMPTZ,                        -- 解除担保时间
    release_reason  VARCHAR(24),                        -- 解除原因：SETTLED / DISPOSED / WRITTEN_OFF

    -- ---- 担保物属性 ----
    security_type   VARCHAR(24),                        -- 担保方式（沿用 SecurityType 枚举）
    security_status VARCHAR(16) NOT NULL DEFAULT 'NONE', -- 担保状态（沿用 SecurityStatus 枚举）

    -- ---- 库存释放规则：回款必须落到指定账户或用于还款 ----
    -- 经销商卖出一台车后，系统自动扣减担保物清单；
    -- 回款须进入指定账户（经销商结算账户引用码）或直接用于还款。
    -- account_ref 存的是引用码不是明文账号（与 ADR-007 账户纪律一致）。
    required_account_ref  VARCHAR(32),                 -- 要求的回款账户引用码
    account_checked       BOOLEAN     NOT NULL DEFAULT FALSE, -- 回款账户是否已核验
    account_checked_at    TIMESTAMPTZ,
    account_checked_by    VARCHAR(64),

    -- ---- 盘点核查痕迹（实地盘点是日本渠道的主要监管手段）----
    last_audit_date       DATE,                         -- 最近盘点日
    audit_mismatch_count  INTEGER    NOT NULL DEFAULT 0, -- 盘点差异次数
    last_audit_by         VARCHAR(64),

    -- ---- 处置痕迹（违约处置闭环的证据链）----
    disposal_case_no  VARCHAR(32),                      -- 关联处置工单号
    disposed_at       TIMESTAMPTZ,                      -- 处置完成时间

    -- ---- 逾期未处置登记提醒 ----
    registration_due  BOOLEAN     NOT NULL DEFAULT FALSE, -- 存在待处理的登记/抹消事项
    registration_note VARCHAR(500),                      -- 待办说明（如"抹消登记待共同申请"）

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_cl_dealer ON collateral_ledger (dealer_id);
-- 库存释放规则要能按"待回款"筛：已售未回款 = 结算窗口内的风险敞口
CREATE INDEX IF NOT EXISTS idx_cl_sold_unpaid ON collateral_ledger (sold, repaid)
    WHERE sold = TRUE AND repaid = FALSE;
-- 待办清单：登记到期 / 抹消待办
CREATE INDEX IF NOT EXISTS idx_cl_due ON collateral_ledger (registration_due)
    WHERE registration_due = TRUE;

COMMENT ON TABLE collateral_ledger IS '担保物台账：按 VIN 一台一行，四个状态独立标记（已放款/已售出/已回款/已解除担保）';
COMMENT ON COLUMN collateral_ledger.vin IS '车架号：台账唯一主键，一台一行';
COMMENT ON COLUMN collateral_ledger.financed IS '已放款：垫款 PAID（银行回调确认）时置真';
COMMENT ON COLUMN collateral_ledger.sold IS '已售出：标记售出时置真，同时触发库存释放规则';
COMMENT ON COLUMN collateral_ledger.repaid IS '已回款：还款核销时置真';
COMMENT ON COLUMN collateral_ledger.released IS '已解除担保：结清或处置完成后置真';
COMMENT ON COLUMN collateral_ledger.required_account_ref IS '要求的回款账户引用码（不存明文账号）';
COMMENT ON COLUMN collateral_ledger.registration_due IS '存在待处理的登记/抹消事项（需人工处置）';

-- ===========================================================================
-- ② collateral_registration_batches：登记批次（一批车一次法务局登记）
-- ===========================================================================
-- 【2026-10-26 依实际业务反馈调整】原先设计为"一台车一笔登记"，
-- 但日本实务是**批量登记**：审核人员与经销商签一笔让渡担保合同，
-- 向东京法务局动产登记课做一次登记，取得**一个登记编号**，
-- 再把该编号与合同文件管理到这一批车上。
-- 即：**登记编号与担保合同都是批次级的**，故改为批次 + 明细两层。
--
-- 批次表登记：登记编号（字符串）/ 登记机关 / 登记日 / 失效日 /
--              担保合同附件 / 登记事项证明书附件 / 抹消状态
-- 明细表登记：批次 ↔ 具体 VIN 的对应关系（同一批次可含多台车）
--
CREATE TABLE IF NOT EXISTS collateral_registration_batches (
    id              BIGSERIAL PRIMARY KEY,
    batch_no        VARCHAR(32) UNIQUE NOT NULL,   -- 内部批次号（系统生成，非法务局编号）
    -- 法务省登记编号：**字符串**（不设数值约束——不同登记种类编号形态不同，
    -- 且可能含连字符/前缀字符），库内唯一
    registration_no VARCHAR(64) UNIQUE,              -- 东京法务局动产登记课的登记编号
    registration_type VARCHAR(24) NOT NULL DEFAULT 'ASSIGNMENT',
        -- 担保方式：ASSIGNMENT 让渡担保 / RETENTION 所有权保留 /
        --           MANUFACTURER_REPURCHASE 厂家回购 / OTHER / NONE
    perfection_method VARCHAR(24) NOT NULL DEFAULT 'REGISTRATION',
        -- 对抗要件方式（决定优先顺位）：
        --   REGISTRATION 登记（法务省）— 公示性最高，新法下优先于占有改定
        --   POSSESSION_TRANSFER 引渡し（实际交付占有）
        --   CONSTRUCTIVE_TRANSFER 占有改定（原始占有人代为占有）
        --   RETENTION 所有権留保（买卖价金债权担保，2025 新法无需对抗要件）
        -- 注意：2025 年新法施行后，**占有改定被劣后于登记等公示性高的方式**
        --（同法第36条）。故须单列此字段，将来算优先顺位时用得上。
    registry_office VARCHAR(128),                    -- 登记机关（如"東京法務局 動産登記課"）
    registered_at   TIMESTAMPTZ,                     -- 登记日
    expires_at      TIMESTAMPTZ,                     -- 登记失效日（让渡担保登记 5 年、不可续展）
    -- ---- 担保合同（批次级，S3 存证）----
    -- 审核人员与经销商签署的让渡担保合同正本：担保权成立的**实体依据**，
    -- 而登记编号只是**公示依据**，两者缺一不可。
    contract_attachment_id BIGINT REFERENCES attachments (id),
    contract_no      VARCHAR(64),                    -- 合同编号
    contract_signed_at TIMESTAMPTZ,                  -- 签署日期
    -- ---- 登记事项证明书（批次级，謄本）----
    certificate_attachment_id BIGINT REFERENCES attachments (id),
    certificate_no   VARCHAR(64),                    -- 证明书编号（纸质凭证自身的编号）
    certificate_uploaded_at TIMESTAMPTZ,
    -- ---- 抹消登记（债务还清后办理，双方共同申请）----
    cancellation_status VARCHAR(16) NOT NULL DEFAULT 'NOT_REQUIRED',
        -- NOT_REQUIRED 不需抹消 / REQUESTED 待申请（已发起待办）/ APPLIED 已提交抹消申请 /
        -- DONE 抹消完成 / CANCELLED 免于抹消（如厂家回购结清、无需登记）
    cancellation_no VARCHAR(64),                     -- 抹消登记编号
    cancellation_applied_at TIMESTAMPTZ,             -- 提交抹消申请时间
    cancellation_completed_at TIMESTAMPTZ,           -- 抹消完成时间
    cancellation_certificate_attachment_id BIGINT REFERENCES attachments (id), -- 抹消凭证（謄本）
    cancellation_requested_by VARCHAR(64),           -- 发起抹消待办的操作人
    cancellation_note VARCHAR(500),                  -- 待办说明/备注
    -- ---- 其他法律事项 ----
    priority_rank   INTEGER,                         -- 顺位（1 为优先；新法按对抗要件具备日定序）
    co_signed_by    VARCHAR(128),                    -- 共同申请对方（登记义务人＝经销商）
    vehicle_count   INTEGER    NOT NULL DEFAULT 0,   -- 本批次车数（冗余计数，便于列表展示）
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_cancel_status CHECK (cancellation_status IN
        ('NOT_REQUIRED', 'REQUESTED', 'APPLIED', 'DONE', 'CANCELLED'))
);

CREATE INDEX IF NOT EXISTS idx_crb_regno ON collateral_registration_batches (registration_no)
    WHERE registration_no IS NOT NULL;
-- 登记到期扫描：5 年期限不可续展，到期须重新设定
CREATE INDEX IF NOT EXISTS idx_crb_expires ON collateral_registration_batches (expires_at)
    WHERE expires_at IS NOT NULL AND cancellation_status NOT IN ('DONE', 'CANCELLED');
-- 抹消待办清单
CREATE INDEX IF NOT EXISTS idx_crb_cancel_todo ON collateral_registration_batches (cancellation_status)
    WHERE cancellation_status IN ('REQUESTED', 'APPLIED');

COMMENT ON TABLE collateral_registration_batches IS '担保登记批次：一批车一次法务局登记，共享一个登记编号与一份担保合同';
COMMENT ON COLUMN collateral_registration_batches.registration_no IS '法务省登记编号（字符串；东京法务局动产登记课出具）';
COMMENT ON COLUMN collateral_registration_batches.contract_attachment_id IS '让渡担保合同附件（S3）——担保权成立的实体依据';
COMMENT ON COLUMN collateral_registration_batches.certificate_attachment_id IS '登记事项证明书附件（謄本）——担保权成立的公示依据';
COMMENT ON COLUMN collateral_registration_batches.expires_at IS '登记失效日：让渡担保登记 5 年期限且不可续展，到期须重新设定';
COMMENT ON COLUMN collateral_registration_batches.cancellation_status IS '抹消状态：REQUESTED=待共同申请（双方申请制，不可单方抹消）';

-- ===========================================================================
-- ②b collateral_registration_items：批次 ↔ 车辆明细
-- ===========================================================================
-- 同一批登记覆盖多台车，每台车对应台账一行。
CREATE TABLE IF NOT EXISTS collateral_registration_items (
    id              BIGSERIAL PRIMARY KEY,
    batch_id       BIGINT      NOT NULL REFERENCES collateral_registration_batches (id),
    ledger_id      BIGINT      NOT NULL REFERENCES collateral_ledger (id),
    vin            VARCHAR(32) NOT NULL,              -- 冗余 VIN：便于按 VIN 直查批次
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_cri_batch_vin UNIQUE (batch_id, vin)
);

CREATE INDEX IF NOT EXISTS idx_cri_batch ON collateral_registration_items (batch_id);
CREATE INDEX IF NOT EXISTS idx_cri_ledger ON collateral_registration_items (ledger_id);
CREATE INDEX IF NOT EXISTS idx_cri_vin ON collateral_registration_items (vin);

COMMENT ON TABLE collateral_registration_items IS '登记批次 ↔ 车辆明细：同一次登记覆盖多台车';
COMMENT ON COLUMN collateral_registration_items.ledger_id IS '对应担保物台账行';

-- ===========================================================================
-- ③ collateral_evidence_links：证据链
-- ===========================================================================
-- 把合同、登记、放款、盘点、回款、处置按 VIN 串成一条可追溯链。
-- 2025 年新法施行前，日本让渡担保的效力与顺位长期依赖判例与实务，
-- 内部证据留存因此是核心风险控制手段——本表即该留存的落点。
CREATE TABLE IF NOT EXISTS collateral_evidence_links (
    id              BIGSERIAL PRIMARY KEY,
    ledger_id       BIGINT      NOT NULL REFERENCES collateral_ledger (id),
    vin             VARCHAR(32) NOT NULL,
    evidence_type   VARCHAR(24) NOT NULL,
        -- 证据类型：
        --   CONTRACT 合同（让渡担保契约/所有权保留契约/采购合同）
        --   REGISTRATION_CERT 登记事项证明书（謄本）
        --   CANCELLATION_CERT 抹消凭证
        --   ADVANCE 放款凭证
        --   INVENTORY_AUDIT 盘点记录（现场照片/扫描清单）
        --   REPAYMENT 回款凭证
        --   DISPOSAL 处置凭证（收车/变现/损失认定）
        --   INSURANCE 保险单
        --   REPURCHASE 厂家回购承诺/兑现凭证
    -- 证据来源：既可指内部业务单据，也可指附件表
    ref_type        VARCHAR(16) NOT NULL DEFAULT 'INTERNAL',
        -- INTERNAL 内部业务单据（ref_id 存单号）/ ATTACHMENT 附件（ref_id 存 attachments.id）
    ref_id          VARCHAR(64) NOT NULL,
    attachment_id   BIGINT REFERENCES attachments (id),  -- 冗余直连附件（便于按附件反查证据链）
    event_at        TIMESTAMPTZ,                        -- 事件发生时间
    note            VARCHAR(500),                       -- 备注
    recorded_by     VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_cel_ledger ON collateral_evidence_links (ledger_id);
CREATE INDEX IF NOT EXISTS idx_cel_vin ON collateral_evidence_links (vin);
CREATE INDEX IF NOT EXISTS idx_cel_type ON collateral_evidence_links (evidence_type);
CREATE INDEX IF NOT EXISTS idx_cel_ref ON collateral_evidence_links (ref_type, ref_id);

COMMENT ON TABLE collateral_evidence_links IS '担保物证据链：合同·登记·放款·盘点·回款·处置按 VIN 串成可追溯链';
COMMENT ON COLUMN collateral_evidence_links.evidence_type IS '证据类型：CONTRACT/REGISTRATION_CERT/CANCELLATION_CERT/ADVANCE/INVENTORY_AUDIT/REPAYMENT/DISPOSAL/INSURANCE/REPURCHASE';
COMMENT ON COLUMN collateral_evidence_links.ref_type IS '证据来源：INTERNAL=内部业务单据（ref_id 存单号）/ ATTACHMENT=附件（ref_id 存 attachments.id）';

-- ===========================================================================
-- 回款落点：repayments 补"回款账户"与"用于还款"标记
-- ===========================================================================
-- 库存释放规则要求回款进入指定账户或直接用于还款。
-- 现有 repayments 表没有记录钱从哪来、去了哪——补两列，
-- 让"回款是否落到指定账户/是否专用于还款"可查、可审。
ALTER TABLE repayments ADD COLUMN IF NOT EXISTS account_ref VARCHAR(32);
ALTER TABLE repayments ADD COLUMN IF NOT EXISTS designated_use BOOLEAN NOT NULL DEFAULT FALSE;
-- designated_use=true 表示该笔回款被指定专用于偿还本台垫款（而非经销商他用）

COMMENT ON COLUMN repayments.account_ref IS '回款账户引用码（对应经销商结算账户，用于库存释放规则核验）';
COMMENT ON COLUMN repayments.designated_use IS '是否被指定专用于偿还本台垫款（库存释放规则要求）';
