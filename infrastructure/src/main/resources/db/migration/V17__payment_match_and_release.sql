-- ===========================================================================
-- V17：回款匹配 / 自动解押 / 额度回补流水（PAYMENT agent，T2-3 后半段）
-- ===========================================================================
--
-- 【这张迁移在闭环里的位置】
--   V15  payment_transactions  = 流水层（钱有没有到，客观事实）—— 采集侧已完成
--   V16  loans                 = 债务层（这笔钱该核到哪笔债）—— loan agent 已交付
--   V17  本迁移                = 匹配层 + 释放层的留痕（钱对应哪台车 / 解押 / 回补）
--
-- 采集侧只负责"把事实记下来"，不解释事实；匹配与释放是另一条链路，结果单独落表。
-- 这样银行文件格式变化不会污染匹配规则，匹配规则变化也不会倒逼改采集。
--
-- 【为什么三张表分开，而不是在 payment_transactions 加几个字段】
--   1. 一笔流水可能匹配 0~N 笔借据（口座振替/自动払出し 场景多台车打包回款），
--      在流水行上放单个 matched_vin 表达不了"一对多"；
--   2. 解押与额度回补是两个**有失败可能**的动作，必须各自留痕、各自幂等，
--      不能把"释放失败"伪装成"没匹配上"；
--   3. 流水是银行给定的事实（V15 刻意不提供 update），匹配与释放是我方认定，
--      两者生命周期不同，混在一行会让"事实不可改"的纪律破功。
--
-- 【幂等锚点】
--   payment_matches.uk_payment_match_txn (account_ref, transaction_id)
--       —— 对齐 V15 的 uk_payment_txn：同一笔银行流水只允许匹配一次。
--   lien_releases.uk_lien_release_match(match_id)
--   credit_restores.uk_credit_restore_match(match_id)
--       —— 一次匹配最多一次解押、一次回补。设计稿 §4.4：重复触发不得重复扣减额度。
--   batch_no 永不作为幂等键（同一天同一账户可能有 96 个批次）。
--
-- 【金额与时间口径】照抄 V15：金额一律 BIGINT（整数日元，无小数）；
-- 业务时间一律 TIMESTAMPTZ（带时区，日本场景 +09:00），日期类字段用 DATE。
-- 所有建表/建索引都带 IF NOT EXISTS，重复执行安全。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- ① payment_matches：流水 → VIN → 借据 的匹配结论（append 一次，幂等）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payment_matches (
    id                  BIGSERIAL PRIMARY KEY,

    -- ---- 指向 V15 流水（幂等锚点，与 uk_payment_txn 同口径）----
    transaction_id      VARCHAR(64)  NOT NULL,   -- payment_transactions.transaction_id
    account_ref         VARCHAR(32)  NOT NULL,   -- payment_transactions.account_ref

    -- ---- 匹配结论：VIN + 借据（对齐 V16 契约字段）----
    vin                 VARCHAR(32),             -- 命中 VIN；无法匹配时 NULL
    loan_id             BIGINT,                  -- loans.id（V16 契约）
    advance_id          BIGINT,                  -- loans.advance_id（V16 契约，可能为 NULL）
    dealer_id           BIGINT,                  -- loans.dealer_id（V16 契约，回补额度的归属）
    credit_line_id      BIGINT,                  -- loans.credit_line_id，回补的目标额度

    match_status        VARCHAR(16)  NOT NULL DEFAULT 'UNMATCHED',
        -- UNMATCHED 无法自动匹配（挂起待人工）/ AUTO_MATCHED 自动匹配
        -- / MANUAL_MATCHED 人工绑定 / IGNORED 判定非本业务 / SPLIT 拆分多车
        -- 取值与 payment_transactions.match_status 一致
    match_rule          VARCHAR(32),             -- 命中规则编号：VIN_MATCH / ORDER_NO_MATCH / AMOUNT_MATCH / MANUAL
    match_score         NUMERIC(6,2),            -- 置信度（0-100），设计稿附录 A
    matched_amount      BIGINT       NOT NULL DEFAULT 0,  -- 本条结论认定的入金金额（整数日元）
    matched_at          TIMESTAMPTZ,             -- 匹配时间
    matched_by          VARCHAR(64),             -- 'system' 或自然人（人工绑定时必须自然人）
    decision_reason     VARCHAR(500),            -- 判定依据/挂起原因，事后可解释"为什么没自动绑"
    candidate_summary   TEXT,                    -- 多候选时的候选清单（VIN/分数），供人工裁决

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_payment_match_txn UNIQUE (account_ref, transaction_id)
);

CREATE INDEX IF NOT EXISTS idx_pm_status ON payment_matches (match_status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_pm_vin    ON payment_matches (vin);
CREATE INDEX IF NOT EXISTS idx_pm_loan   ON payment_matches (loan_id);

COMMENT ON TABLE  payment_matches IS '流水→VIN→借据的匹配结论。幂等锚点 uk_payment_match_txn，与 V15 流水一一对应';
COMMENT ON COLUMN payment_matches.transaction_id IS '对应 payment_transactions.transaction_id；与 account_ref 共同构成幂等键';
COMMENT ON COLUMN payment_matches.loan_id IS 'V16 契约 loans.id；一个 VIN 唯一指向一笔未结清借据';
COMMENT ON COLUMN payment_matches.advance_id IS 'V16 契约 loans.advance_id，可能为 NULL，解析时必须容忍缺失';
COMMENT ON COLUMN payment_matches.match_score IS '设计稿附录 A 置信度：VIN 命中 95 / 订单号 90 / 金额 60 / 近似 45 / 时间窗 10';
COMMENT ON COLUMN payment_matches.decision_reason IS '无法自动匹配时记录挂起原因；人工忽略时记录判定理由，本身是审计证据';
COMMENT ON COLUMN payment_matches.candidate_summary IS '多候选接近（差距<20）时留存候选清单，避免人工重新猜测';

-- ---------------------------------------------------------------------------
-- ② lien_releases：解押动作留痕（一次匹配最多一条；含抹消待办状态）
-- ---------------------------------------------------------------------------
-- 【为什么解押不等于抹消完成】设计稿 §3.7/§4.2：债务还清后担保实体权利消灭，
-- 但让渡担保的登记簿仍挂着，抹消需权利人与登记义务人共同申请法务局。
-- 系统只能把 cancellation_status 置 REQUESTED 生成待办，不能自动办结。
CREATE TABLE IF NOT EXISTS lien_releases (
    id                  BIGSERIAL PRIMARY KEY,
    match_id            BIGINT       NOT NULL,   -- payment_matches.id
    transaction_id      VARCHAR(64)  NOT NULL,   -- 冗余：排查时不必先查匹配表
    vin                 VARCHAR(32)  NOT NULL,
    loan_id             BIGINT,

    release_amount      BIGINT       NOT NULL DEFAULT 0,  -- 对应解除担保的本金部分
    status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
        -- PENDING 待解押 / RELEASED 已解押（实体权利消灭）/ FAILED 失败待重试
    cancellation_status VARCHAR(16)  NOT NULL DEFAULT 'NOT_REQUIRED',
        -- NOT_REQUIRED 无需抹消 / REQUESTED 已生成抹消待办 / DONE 抹消完成
    released_at         TIMESTAMPTZ,
    failed_reason       VARCHAR(500),
    operator            VARCHAR(64)  NOT NULL DEFAULT 'system',

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_lien_release_match UNIQUE (match_id)
);

CREATE INDEX IF NOT EXISTS idx_lr_status ON lien_releases (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_lr_vin    ON lien_releases (vin);

COMMENT ON TABLE  lien_releases IS '解押动作留痕。uk_lien_release_match 保证一次匹配只解押一次，重复触发不重复处置担保';
COMMENT ON COLUMN lien_releases.cancellation_status IS '抹消登记待办状态。日本实务需双方共同申请，系统只能置 REQUESTED，不能自动 DONE';
COMMENT ON COLUMN lien_releases.status IS 'RELEASED 指担保实体权利消灭，不等于登记簿已抹消（见 cancellation_status）';

-- ---------------------------------------------------------------------------
-- ③ credit_restores：额度回补流水（一次匹配最多一条；失败可审计重试）
-- ---------------------------------------------------------------------------
-- 【为什么额度回补单独留痕】设计稿 §3.8：额度只在"匹配成功"时释放，到账不释放。
-- 一旦误匹配即释放会立刻扩大敞口；因此回补必须能回答"哪笔流水、哪个经销商、
-- 回补多少、谁触发的"，失败时也不能静默——必须留一条 FAILED 供强制工单重跑。
CREATE TABLE IF NOT EXISTS credit_restores (
    id                  BIGSERIAL PRIMARY KEY,
    match_id            BIGINT       NOT NULL,   -- payment_matches.id
    transaction_id      VARCHAR(64)  NOT NULL,
    dealer_id           BIGINT       NOT NULL,   -- 回补归属经销商（V16 loans.dealer_id）
    credit_line_id      BIGINT,                  -- 回补目标额度（V16 loans.credit_line_id）

    restore_amount      BIGINT       NOT NULL DEFAULT 0,  -- 回补金额（整数日元）
    available_before    BIGINT,                  -- CreditLineClient 只读查询的回补前可用额度快照
    available_after     BIGINT,                  -- 回补后可用额度（真实写入方回填；存根实现留 NULL）
    status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
        -- PENDING 待回补 / RESTORED 已回补 / FAILED 失败待重试
    idempotency_key     VARCHAR(128) NOT NULL,   -- 通常为 transaction_id，与 match_id 协同防重复
    restored_at         TIMESTAMPTZ,
    failed_reason       VARCHAR(500),
    operator            VARCHAR(64)  NOT NULL DEFAULT 'system',

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_credit_restore_match UNIQUE (match_id)
);

CREATE INDEX IF NOT EXISTS idx_cr_status  ON credit_restores (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_cr_dealer  ON credit_restores (dealer_id, restored_at DESC);
CREATE INDEX IF NOT EXISTS idx_cr_idem    ON credit_restores (idempotency_key);

COMMENT ON TABLE  credit_restores IS '额度回补流水。uk_credit_restore_match 保证一次匹配只回补一次，防止误匹配/重复触发导致超额释放';
COMMENT ON COLUMN credit_restores.available_before IS 'CreditLineClient 只读查询快照。额度写入仍在授信侧行锁事务内，本表只留证据';
COMMENT ON COLUMN credit_restores.idempotency_key IS '回补幂等键，通常为 transaction_id；与 match_id 唯一约束双保险';
