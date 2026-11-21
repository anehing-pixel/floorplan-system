-- 库存融资核心系统 · V2 schema
-- 补齐业务流程文档"流程缺口对照"(P0/P1) 对应的数据表。
-- 金额一律 BIGINT（整数日元）；比率 NUMERIC；时间 TIMESTAMPTZ。

-- ============================================================
-- P0-③ 重复融资拦截：权属 / 抵押 / 查封登记核对
-- 日本本地多数无接口，走人工录入 financing_lien_checks；
-- 建账前若该车存在 encumbered=true 记录，则拒绝重复融资 (VEHICLE_005)。
-- ============================================================
CREATE TABLE financing_lien_checks (
    id            BIGSERIAL PRIMARY KEY,
    vin           VARCHAR(32) NOT NULL,
    check_source  VARCHAR(24) NOT NULL,            -- MANUAL / OEM_PORTAL / REGISTRY_API
    encumbered    BOOLEAN     NOT NULL DEFAULT FALSE, -- 是否在他处抵押 / 查封
    checked_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    note          VARCHAR(255),
    operator      VARCHAR(64)
);
CREATE INDEX idx_lien_vin ON financing_lien_checks (vin);

-- ============================================================
-- P0-① 库龄递减还款：核销流水
-- 计划本身在 V1 的 aging_plans；此处记录每期实际核销（部分还款本金）。
-- ============================================================
CREATE TABLE curtailment_payments (
    id              BIGSERIAL PRIMARY KEY,
    vin             VARCHAR(32) NOT NULL,
    period_no       INT         NOT NULL,          -- 第几期
    amount          BIGINT      NOT NULL,           -- 整数日元（部分还款本金）
    paid_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    idempotency_key VARCHAR(64) UNIQUE,
    operator        VARCHAR(64)
);
CREATE INDEX idx_curtail_vin ON curtailment_payments (vin);

-- ============================================================
-- P0-② 违约处置闭环（脱信销售 / 逾期 / 盘点缺失）
-- ============================================================
CREATE TABLE disposal_cases (
    id              BIGSERIAL PRIMARY KEY,
    case_no         VARCHAR(32) UNIQUE NOT NULL,
    vin             VARCHAR(32) NOT NULL,
    dealer_id       BIGINT,
    credit_line_id  BIGINT,
    reason          VARCHAR(64),                    -- OOT / OVERDUE / AUDIT_MISSING
    status          VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    -- OPEN → REPOSSESSED → LIQUIDATING → CLOSED_LOSS / CLOSED_RECOVERED
    repossessed_at  TIMESTAMPTZ,
    proceeds        BIGINT      NOT NULL DEFAULT 0,  -- 变现所得
    loss_amount     BIGINT      NOT NULL DEFAULT 0,  -- 认定损失
    opened_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at       TIMESTAMPTZ
);
CREATE INDEX idx_disposal_vin    ON disposal_cases (vin);
CREATE INDEX idx_disposal_status ON disposal_cases (status);

-- ============================================================
-- P1 车辆保险（损毁 / 盗抢）
-- ============================================================
CREATE TABLE insurance_policies (
    id             BIGSERIAL PRIMARY KEY,
    policy_no      VARCHAR(64) UNIQUE NOT NULL,
    vin            VARCHAR(32) NOT NULL,
    insurer        VARCHAR(128),
    insured_amount BIGINT      NOT NULL DEFAULT 0,
    start_date     DATE,
    end_date       DATE,
    status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE / CLAIMED / LAPSED
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ins_vin ON insurance_policies (vin);

-- ============================================================
-- P1 厂家回购（日本新车渠道核心信用增强：メーカー買取約束）
-- ============================================================
CREATE TABLE repurchase_requests (
    id                BIGSERIAL PRIMARY KEY,
    request_no        VARCHAR(32) UNIQUE NOT NULL,
    vin               VARCHAR(32) NOT NULL,
    dealer_id         BIGINT,
    credit_line_id    BIGINT,
    reason            VARCHAR(128),
    status            VARCHAR(16) NOT NULL DEFAULT 'REQUESTED', -- REQUESTED / FULFILLED / REJECTED
    repurchase_amount BIGINT      NOT NULL DEFAULT 0,
    fulfilled_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_repurchase_vin ON repurchase_requests (vin);

-- ============================================================
-- P1 结清证明（出具给车商的书面凭证）
-- ============================================================
CREATE TABLE payoff_certificates (
    id           BIGSERIAL PRIMARY KEY,
    cert_no      VARCHAR(32) UNIQUE NOT NULL,
    vin          VARCHAR(32) NOT NULL,
    principal    BIGINT      NOT NULL DEFAULT 0,
    interest     BIGINT      NOT NULL DEFAULT 0,
    total_amount BIGINT      NOT NULL DEFAULT 0,
    issued_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    operator     VARCHAR(64)
);
CREATE INDEX idx_payoff_vin ON payoff_certificates (vin);

-- ============================================================
-- P1 展期续贷（延长结算窗口）
-- ============================================================
CREATE TABLE extension_requests (
    id           BIGSERIAL PRIMARY KEY,
    request_no   VARCHAR(32) UNIQUE NOT NULL,
    vin          VARCHAR(32) NOT NULL,
    ext_days     INT         NOT NULL,
    reason       VARCHAR(128),
    status       VARCHAR(16) NOT NULL DEFAULT 'APPROVED', -- APPROVED / REJECTED
    original_due DATE,
    new_due      DATE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ext_vin ON extension_requests (vin);

-- ============================================================
-- P1 额度年审（冻结 → 复核 → 解冻恢复）
-- ============================================================
CREATE TABLE annual_reviews (
    id             BIGSERIAL PRIMARY KEY,
    review_no      VARCHAR(32) UNIQUE NOT NULL,
    dealer_id      BIGINT      NOT NULL,
    credit_line_id BIGINT,
    review_cycle   VARCHAR(16),                   -- ANNUAL / HALF_YEARLY
    status         VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING / IN_PROGRESS / PASSED / REJECTED
    frozen_amount  BIGINT      NOT NULL DEFAULT 0,
    result_note    VARCHAR(255),
    initiated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ
);
CREATE INDEX idx_review_dealer ON annual_reviews (dealer_id);

-- ============================================================
-- 按季度归档（预设）：interest_ledger 增长快，
-- 早于本季度首日的流水由归档作业复制到历史表后删除。
-- ============================================================
CREATE TABLE interest_ledger_archive (
    LIKE interest_ledger INCLUDING ALL
);

-- P1 提前部分还款：repayments 增加类型列区分整台结清 / 部分还款
ALTER TABLE repayments ADD COLUMN repayment_type VARCHAR(16) NOT NULL DEFAULT 'FULL';
