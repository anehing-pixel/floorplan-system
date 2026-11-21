-- 库存融资核心系统 · 初始 schema（对齐《库存融资核心系统-数据库设计文档.md》）
-- 金额一律 BIGINT（整数日元）；比率 NUMERIC；时间 TIMESTAMPTZ。

CREATE TABLE dealers (
    id             BIGSERIAL PRIMARY KEY,
    dealer_code    VARCHAR(32)  UNIQUE NOT NULL,
    name           VARCHAR(255) NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    risk_grade     VARCHAR(8),
    business_years INT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE credit_lines (
    id             BIGSERIAL PRIMARY KEY,
    dealer_id      BIGINT  NOT NULL REFERENCES dealers (id),
    approved_limit BIGINT  NOT NULL,
    used_amount    BIGINT  NOT NULL DEFAULT 0,
    frozen_amount  BIGINT  NOT NULL DEFAULT 0,
    status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version        INT     NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_credit_within_limit CHECK (used_amount + frozen_amount <= approved_limit)
);
CREATE INDEX idx_credit_lines_dealer ON credit_lines (dealer_id);

-- 单台台账：以 VIN 为业务主键
CREATE TABLE vehicle_units (
    vin                  VARCHAR(32) PRIMARY KEY,
    dealer_id            BIGINT NOT NULL,
    credit_line_id       BIGINT,
    advance_id           BIGINT,
    brand                VARCHAR(64),
    model                VARCHAR(64),
    model_year           INT,
    mileage              INT,
    color                VARCHAR(32),
    vehicle_type         VARCHAR(32),
    supplier_name        VARCHAR(128),
    purchase_channel     VARCHAR(16),
    invoice_amount       BIGINT NOT NULL DEFAULT 0,
    advance_amount       BIGINT NOT NULL DEFAULT 0,
    advance_ratio        NUMERIC(5, 4),
    advanced_at          DATE,
    principal_balance    BIGINT NOT NULL DEFAULT 0,
    paid_principal       BIGINT NOT NULL DEFAULT 0,
    annual_rate          NUMERIC(7, 4),
    rate_type            VARCHAR(16),
    accrued_interest     BIGINT NOT NULL DEFAULT 0,
    last_interest_date   DATE,
    stock_in_date        DATE,
    sold_at              DATE,
    settle_due_date      DATE,
    settled_at           DATE,
    aging_days           INT NOT NULL DEFAULT 0,
    turnover_days        INT,
    status               VARCHAR(24) NOT NULL,
    security_type        VARCHAR(24),
    security_status      VARCHAR(16),
    overdue_days         INT NOT NULL DEFAULT 0,
    risk_level           VARCHAR(8),
    oot_suspected        BOOLEAN NOT NULL DEFAULT FALSE,
    last_audit_date      DATE,
    audit_mismatch_count INT NOT NULL DEFAULT 0,
    version              INT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_vu_dealer      ON vehicle_units (dealer_id);
CREATE INDEX idx_vu_status      ON vehicle_units (status);
CREATE INDEX idx_vu_settle_due  ON vehicle_units (settle_due_date);
CREATE INDEX idx_vu_interest    ON vehicle_units (last_interest_date);
CREATE INDEX idx_vu_aging       ON vehicle_units (aging_days);

CREATE TABLE advances (
    id              BIGSERIAL PRIMARY KEY,
    advance_no      VARCHAR(32) UNIQUE NOT NULL,
    vin             VARCHAR(32) NOT NULL,
    credit_line_id  BIGINT      NOT NULL,
    amount          BIGINT      NOT NULL,
    paid_at         TIMESTAMPTZ,
    status          VARCHAR(16) NOT NULL,
    payment_ref     VARCHAR(64),
    idempotency_key VARCHAR(64) UNIQUE NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_advances_vin ON advances (vin);

CREATE TABLE repayments (
    id              BIGSERIAL PRIMARY KEY,
    repayment_no    VARCHAR(32) UNIQUE NOT NULL,
    vin             VARCHAR(32) NOT NULL,
    principal       BIGINT NOT NULL,
    interest        BIGINT NOT NULL,
    total_amount    BIGINT NOT NULL,
    settled_at      TIMESTAMPTZ,
    operator_id     BIGINT,
    idempotency_key VARCHAR(64) UNIQUE NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_repayments_vin ON repayments (vin);

-- 计息流水：唯一约束是批处理幂等的基石
CREATE TABLE interest_ledger (
    id                BIGSERIAL PRIMARY KEY,
    vin               VARCHAR(32) NOT NULL,
    biz_date          DATE        NOT NULL,
    principal_balance BIGINT      NOT NULL,
    daily_interest    BIGINT      NOT NULL,
    rate_snapshot     NUMERIC(7, 4),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_interest_vin_date UNIQUE (vin, biz_date)
);

CREATE TABLE aging_plans (
    id                 BIGSERIAL PRIMARY KEY,
    vin                VARCHAR(32) UNIQUE NOT NULL,
    rule_version       VARCHAR(16),
    first_trigger_date DATE,
    period_days        INT,
    reduce_ratio       NUMERIC(5, 4),
    reduce_amount      BIGINT NOT NULL DEFAULT 0,
    paid_periods       INT    NOT NULL DEFAULT 0,
    next_due_date      DATE,
    status             VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
);
CREATE INDEX idx_aging_next_due ON aging_plans (next_due_date);

-- 担保登记（是否落地取决于新车未上牌担保路径确认结果）
CREATE TABLE security_records (
    id            BIGSERIAL PRIMARY KEY,
    vin           VARCHAR(32) NOT NULL,
    security_type VARCHAR(24) NOT NULL,
    reg_no        VARCHAR(64),
    owner_name    VARCHAR(128),
    set_date      DATE,
    release_date  DATE,
    doc_status    VARCHAR(16),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_security_vin ON security_records (vin);

-- 审计事件：append-only
CREATE TABLE audit_events (
    id              BIGSERIAL PRIMARY KEY,
    actor           VARCHAR(64),
    actor_role      VARCHAR(32),
    action          VARCHAR(32) NOT NULL,
    target_type     VARCHAR(32),
    target_id       VARCHAR(64),
    before_snapshot TEXT,
    after_snapshot  TEXT,
    trace_id        VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_target ON audit_events (target_type, target_id);

CREATE TABLE batch_runs (
    id           BIGSERIAL PRIMARY KEY,
    job_name     VARCHAR(64) NOT NULL,
    biz_date     DATE        NOT NULL,
    status       VARCHAR(16) NOT NULL,
    started_at   TIMESTAMPTZ,
    finished_at  TIMESTAMPTZ,
    error_detail TEXT,
    CONSTRAINT uq_batch_job_date UNIQUE (job_name, biz_date)
);

CREATE TABLE alerts (
    id         BIGSERIAL PRIMARY KEY,
    dealer_id  BIGINT,
    vin        VARCHAR(32),
    alert_type VARCHAR(32) NOT NULL,
    severity   VARCHAR(8)  NOT NULL,
    status     VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    detail     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_alerts_type_status ON alerts (alert_type, status);

CREATE TABLE holidays (
    biz_date        DATE PRIMARY KEY,
    is_business_day BOOLEAN NOT NULL,
    note            VARCHAR(128)
);
