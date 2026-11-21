-- ===========================================================================
-- V15：银行回款流水采集（BANK_FETCH）
-- ===========================================================================
--
-- 【为什么必须有这张表】
-- repayments 是「核销记录」——我方认定收到了多少钱，是主观的；
-- payment_transactions 是「银行流水」——银行收到的钱，是客观事实。
--
--   流水   = 事实（钱到了，包含与本次业务无关的入账）
--   核销   = 认定（这笔钱对应哪台车、抵多少本金和利息）
--
-- 没有流水表，就无法回答"钱到底有没有到指定账户"，
-- 也无法在争议时自证。现有 repayments.account_ref 是核销时人工填的引用码，
-- 没有与银行流水勾稽，等于"自己说自己收到了"。
--
-- 【为什么幂等锚点是 (account_ref, transaction_id)，不是 batch_no】
-- 银行流水重复拉取是常态：当日多次同步、文件重发、断点续拉。
-- 同一账户一天可能拉 96 次（每 15 分钟一次），batch_no 每次都不同——
-- 用它判重等于没有判重。
-- 而 (账户引用码, 银行流水号) 才是这笔钱在银行侧的唯一身份，
-- 重复拉取时它不变，所以它才是幂等锚点。
--
-- 这条唯一约束不是"约束"，是**资损防线**：
-- 少了它，流水翻倍 → 匹配金额虚高 → 系统以为收了双倍的钱 → 超额释放额度。
-- ===========================================================================

CREATE TABLE IF NOT EXISTS payment_transactions (
    id              BIGSERIAL PRIMARY KEY,

    -- ---- 银行侧事实 ----
    transaction_id  VARCHAR(64)  NOT NULL,          -- 银行流水号（与银行对账的唯一锚点）
    account_ref     VARCHAR(32)  NOT NULL,          -- 入账账户引用码（不存明文账号，见 ADR-009 §2.1）
    value_date      DATE         NOT NULL,          -- 起息日（钱真正到账那天，不是交易日）
    amount          BIGINT       NOT NULL,          -- 入账金额（整数日元；正数=入金，负数=冲正）
    payer_name      VARCHAR(256),                   -- 付款人名称
    payer_account   VARCHAR(128),                   -- 付款人账户（银行可能脱敏或不提供）
    reference       VARCHAR(512),                   -- 摘要/备注 —— **VIN 常留在这里**，是匹配的第一线索

    -- ---- 匹配结果（由 PAYMENT_MATCH 写入，采集阶段一律 UNMATCHED）----
    match_status    VARCHAR(16)  NOT NULL DEFAULT 'UNMATCHED',
        -- UNMATCHED 未匹配 / AUTO_MATCHED 自动匹配 / MANUAL_MATCHED 人工绑定
        -- / IGNORED 判定为非本业务 / SPLIT 拆分到多台车
    matched_vin     VARCHAR(32),
    matched_advance_id BIGINT,                      -- 匹配到的垫款（比 repayments.id 更贴近"债"本身）
    matched_at      TIMESTAMPTZ,
    matched_by      VARCHAR(64),
    match_rule      VARCHAR(32),                    -- 命中的规则编号，事后可解释"为什么这么匹配"
    match_score     NUMERIC(6,2),

    -- ---- 采集元信息 ----
    source          VARCHAR(16)  NOT NULL DEFAULT 'BANK_FILE',
        -- BANK_API 银企直连 / BANK_FILE 文件（MT940/CSV）/ MANUAL_UPLOAD 人工上传 / DEMO 演示
    batch_no        VARCHAR(64),                    -- 采集批次号（仅用于追踪"这批是哪次拉进来的"）
    raw_reference   TEXT,                           -- 原始报文行（争议时自证；不解析也能回看）
    collected_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- 幂等锚点：同一账户同一流水号只允许入一次
    CONSTRAINT uk_payment_txn UNIQUE (account_ref, transaction_id)
);

-- 未匹配清单是贷后跟进的直接对象，按 (状态, 起息日) 查
CREATE INDEX IF NOT EXISTS idx_ptx_match ON payment_transactions (match_status, value_date);
CREATE INDEX IF NOT EXISTS idx_ptx_vin   ON payment_transactions (matched_vin);
CREATE INDEX IF NOT EXISTS idx_ptx_batch ON payment_transactions (batch_no);
-- 按账户+起息日取区间流水（对账用）
CREATE INDEX IF NOT EXISTS idx_ptx_account_date ON payment_transactions (account_ref, value_date);

COMMENT ON TABLE  payment_transactions IS '银行回款流水（客观事实）。幂等锚点 uk_payment_txn，batch_no 不是幂等键';
COMMENT ON COLUMN payment_transactions.value_date IS '起息日：钱到账那天。营业日/交易日在此不适用——钱是哪天到的就记哪天';
COMMENT ON COLUMN payment_transactions.raw_reference IS '原始报文行。解析失败或金额争议时，靠它证明"银行到底说了什么"';

-- ===========================================================================
-- 采集批次：每次拉取一行，是「运行日志」，不是「幂等依据」
-- ===========================================================================
-- 【为什么单独建表，而不复用 batch_runs】
-- batch_runs 的主键是 (job_name, biz_date)，同一天只能跑一次 —— 那是"日终作业"的语义。
-- BANK_FETCH 是每 15 分钟一次的**高频作业**，同一 biz_date 要跑几十次，
-- 用 batch_runs 会被幂等机制直接挡住（第二次就跑不了）。
-- 所以高频作业另建批次表，每次一行，不设唯一约束。
--
-- 【为什么允许同一账户同一天多批次】
-- 银行入账是实时的，钱可能上午到、下午到；每次拉取窗口内只处理新流水。
-- 多批次是正常现象，不是重复执行。
-- ===========================================================================

CREATE TABLE IF NOT EXISTS bank_fetch_batches (
    id                BIGSERIAL PRIMARY KEY,
    batch_no          VARCHAR(64) NOT NULL UNIQUE,   -- 每次采集唯一（BF-账户-日期-随机6位）
    account_ref       VARCHAR(32) NOT NULL,
    value_date_from   DATE        NOT NULL,          -- 本次拉取的窗口起（含）
    value_date_to     DATE        NOT NULL,          -- 本次拉取的窗口止（含）
    source            VARCHAR(16) NOT NULL,          -- BANK_API / BANK_FILE / MANUAL_UPLOAD / DEMO
    status            VARCHAR(16) NOT NULL,          -- RUNNING / SUCCESS / FAILED

    fetched_count     INT NOT NULL DEFAULT 0,        -- 银行返回条数
    inserted_count    INT NOT NULL DEFAULT 0,        -- 实际新入账条数
    duplicated_count  INT NOT NULL DEFAULT 0,        -- 被唯一约束挡下的条数（重复拉取是常态）

    file_fingerprint  VARCHAR(128),                  -- 文件 SHA-256 前 32 位：同一文件重发时日志里一眼可见
    error_detail      TEXT,
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_bfb_account_date ON bank_fetch_batches (account_ref, value_date_to DESC);
CREATE INDEX IF NOT EXISTS idx_bfb_status       ON bank_fetch_batches (status, started_at DESC);

COMMENT ON TABLE bank_fetch_batches IS '银行流水采集批次日志。只用于追踪与重放，不承担幂等——幂等在 payment_transactions.uk_payment_txn';
