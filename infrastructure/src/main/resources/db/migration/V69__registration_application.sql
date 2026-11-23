-- ===========================================================================
-- V69：登记委托表 registration_applications（W-U4，R5.6 / R7.2）
-- ===========================================================================
--
-- 【规格来源】D04 §3.11（列级规格）+ §2 号段表（V69 依赖 V52）；
--   状态机 D03 §3.8（DRAFT→SUBMITTED→ACCEPTED_BY_AGENT→COMPLETED / REJECTED）；
--   端点 D07 §7.3 后两行（POST /api/registration-applications、POST .../{no}/confirm）；
--   入站 D07 §3.6（I-08 登记结果回执）；红线 D10 §4 D-2、S-4（二人）、
--   「内部 confirm 不得伪造 COMPLETED」（D07 §7.3 第 8 行）。
--
-- 【为什么登记要单独一张表，而不是在 lien_releases 上加两列】
-- V17 的 `lien_releases.cancellation_status` 只回答"我方要不要抹消、抹消了没有"，
-- 它是**解押动作的附属状态**；登记委托是一次**对外委托**：有代办机构、有受理号、
-- 有预计完成日、有结果证据文件，且**委托人与结果确认人必须换人**（SOD）。
-- 这些是"一件事务的完整生命周期"，塞进解押表就变成半张表——
-- 而且抹消只是三种委托之一（另两类：移転登録、抵当権设定）。
--
-- 【为什么这张表归 W-U4 而不是 W-U1（D13 §3 G15）】
-- 登记确认与放证链共用同一批证据（receipt → 债权消灭 → 抹消委托 → 入站结果），
-- 拆两个包会让"确认放证"与"确认登记"的换人判定跨包，无法在同一用例事务里校验 SOD。
--
-- 【依赖处理】D04 §2 标注 V69 依赖 V52（inspection_certificates，W-U1）。
-- 并行同基线（本分支只有 V65–V69）时 V52 不存在，因此 `certificate_id` 的外键
-- 用**存在性判定**包裹：有表则补 FK（NOT VALID，存量不阻塞），无表则退化为逻辑引用
-- （D04 §1.1 跨上下文允许），合入主干后由收口方回跑同一判定补上——
-- 本迁移可重复执行，补 FK 不需要新号段。
-- ===========================================================================

CREATE TABLE IF NOT EXISTS registration_applications (
    id                  BIGSERIAL PRIMARY KEY,

    -- ---- 委托身份 ----
    apply_no            VARCHAR(48)  NOT NULL,          -- REG-{TYPE}-{YYYY}-{6位}，D00 §13.1 编号形态
    apply_type          VARCHAR(24)  NOT NULL,
        -- TITLE_TRANSFER 移転登録 / MORTGAGE_SETUP 抵当権设定 / MORTGAGE_DISCHARGE 抵当権抹消
    vin                 VARCHAR(32)  NOT NULL,
    certificate_id      BIGINT,                         -- 逻辑引用 inspection_certificates.id（V52）
    release_order_no    VARCHAR(32),                    -- 抹消类委托的来源指令（逻辑引用 release_orders.order_no）
    disposal_case_no    VARCHAR(32),                    -- 处置接管引发的登记（逻辑引用 disposal_cases）

    -- ---- 受托方 ----
    agent_partner_code  VARCHAR(64),                    -- 登记代办（逻辑引用 integration.partners.partner_code，V71）

    -- ---- 时间与预期 ----
    applied_on          DATE,
    expected_by         DATE,
    completed_on        TIMESTAMPTZ,

    -- ---- 状态与结果 ----
    status              VARCHAR(24)  NOT NULL DEFAULT 'DRAFT',
    registry_result     VARCHAR(500),                   -- 法务局结论摘要（明细在 receipt_path）
    receipt_path        VARCHAR(512),                   -- 结果证明文件（本地路径，不引入对象存储 U-R12）
        -- ★ D04 §3.11 只写了列名没写类型；这里按同域同类列的实际口径取
        --   release_receipts.photo_paths / inspection_certificates.evidence_path（V19/V52 均为
        --   VARCHAR(512)），非自造宽度，登记为实现假设待收口确认。

    -- ---- 二人签认（SOD D-2 / D10 S-4）----
    applicant_user      VARCHAR(64)  NOT NULL,          -- 委托人（令牌自然人，不得从请求体取）
    co_signed_by        VARCHAR(64),                    -- 结果确认人：必须与委托人不同

    -- ---- 证据溯源 ----
    source_message_id   BIGINT,                         -- 推进到 COMPLETED 的那条 I-08 入站报文（V72，逻辑引用）
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_registration_apply_no UNIQUE (apply_no),

    CONSTRAINT chk_reg_apply_type CHECK (apply_type IN
        ('TITLE_TRANSFER', 'MORTGAGE_SETUP', 'MORTGAGE_DISCHARGE')),

    CONSTRAINT chk_reg_status CHECK (status IN
        ('DRAFT', 'SUBMITTED', 'ACCEPTED_BY_AGENT', 'COMPLETED', 'REJECTED')),

    -- ★ 「内部 confirm 不得伪造 COMPLETED」（D07 §7.3 第 8 行）的库层落点：
    --   COMPLETED 必须带 I-08 入站报文引用。与 V67 的 PARTNER_RECEIPT↔inbound_message_id
    --   是同一条防线的两处表现（登记结果与对方回执都是我方的**外部事实**，不得自证）。
    CONSTRAINT chk_reg_completed_needs_inbox CHECK (status <> 'COMPLETED' OR source_message_id IS NOT NULL),

    -- 委托与结果确认换人（D10 S-4：同一逻辑操作的 actor 与 co_signed_by 不得相同）。
    -- DRAFT 阶段 co_signed_by 尚未产生，允许 NULL。
    CONSTRAINT chk_reg_two_person CHECK (co_signed_by IS NULL OR co_signed_by <> applicant_user),

    -- 完成态必须有完成时点与结果证据；REJECTED 必须有理由（差异解释义务，同 V66 差异表口径）
    CONSTRAINT chk_reg_completed_evidence CHECK (
        status <> 'COMPLETED'
        OR (completed_on IS NOT NULL AND registry_result IS NOT NULL
            AND btrim(registry_result) <> '' AND receipt_path IS NOT NULL)),
    CONSTRAINT chk_reg_rejected_reason CHECK (
        status <> 'REJECTED' OR (registry_result IS NOT NULL AND btrim(registry_result) <> ''))
);

CREATE INDEX IF NOT EXISTS idx_reg_vin_status   ON registration_applications (vin, status);
CREATE INDEX IF NOT EXISTS idx_reg_order_no     ON registration_applications (release_order_no)
    WHERE release_order_no IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_reg_open_agent   ON registration_applications (agent_partner_code, expected_by)
    WHERE status IN ('SUBMITTED', 'ACCEPTED_BY_AGENT');
CREATE INDEX IF NOT EXISTS idx_reg_inbox        ON registration_applications (source_message_id)
    WHERE source_message_id IS NOT NULL;

COMMENT ON TABLE  registration_applications IS
    '登记委托（R5.6/R7.2）：移転登録・抵当権设定・抹消三类。委托与结果确认换人（SOD D-2），'
    'COMPLETED 只能由 I-08 入站结果推进（内部 confirm 到 ACCEPTED_BY_AGENT 为止）';
COMMENT ON COLUMN registration_applications.status IS
    'DRAFT 草拟 / SUBMITTED 已委托 / ACCEPTED_BY_AGENT 代办受理 / COMPLETED 登记完成（仅经入站）/ '
    'REJECTED 驳回。内部 confirm 端点最多推到 ACCEPTED_BY_AGENT，见 chk_reg_completed_needs_inbox';
COMMENT ON COLUMN registration_applications.certificate_id IS
    '目标車検証（逻辑引用 inspection_certificates.id，V52 = W-U1 号段）。'
    '外键由本迁移末尾的存在性判定补齐；本列可为 NULL —— 处置接管引发的登记可能尚无証書行';
COMMENT ON COLUMN registration_applications.source_message_id IS
    'I-08 登记结果回执的入站报文（逻辑引用 integration.inbound_messages.id，V72 = W-U3 号段）。'
    '「登记的权威来源必须是登记代办」这条法律事实的落点（D10 §5：LEGAL_AGENT 只能写结果，不能改委托内容）';
COMMENT ON COLUMN registration_applications.agent_partner_code IS
    '受托代办（逻辑引用 integration.partners）。为 NULL = 线下自办，此时 COMPLETED 的入站证据由'
    '内部渠道报文提供，判据不变（chk_reg_completed_needs_inbox 不区分代办类型）';

-- ---------------------------------------------------------------------------
-- 跨包外键的存在性补挂（见文件头【依赖处理】）
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF to_regclass('inspection_certificates') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_reg_certificate') THEN
        ALTER TABLE registration_applications
            ADD CONSTRAINT fk_reg_certificate
            FOREIGN KEY (certificate_id) REFERENCES inspection_certificates (id) NOT VALID;
    END IF;
END $$;
