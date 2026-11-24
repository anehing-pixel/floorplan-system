-- ===========================================================================
-- V80：PAYMENT_MATCHED 消费侧的额度回补幂等台账（credit-service 写入）
-- ===========================================================================
--
-- 【为什么必须有一张自己的表】
-- PAYMENT_MATCHED 的消费方是 credit-service（docs/接口契约-事件与Feign.md §2）。
-- V17 的 credit_restores 是 bank-service 的「回补请求」台账，主键与幂等键都是
-- payment_matches.id；同一个业务事实被重新发布成新 outbox 行时 event_id 会变，
-- V17 的 match_id 也随之变成另一个值，挡不住重复回补。
--
-- 契约 §3.2 要求业务层按 paymentRef 去重：
--   框架层 event_consumptions(event_id, consumer) 只能挡「同一事件行重复消费」，
--   挡不住「同一笔回款被重新发布成新事件」。
-- 本表的 uk_credit_line_restore_payment_ref 就是那层业务唯一键，也是 credit-service
-- 行锁事务里的「认领位」：先 INSERT ... ON CONFLICT DO NOTHING 认领 paymentRef，
-- 再在同一个本地事务里改 credit_lines；事务回滚时认领行一起回滚，重试可以重新认领。
--
-- 【金额口径】
-- 回补额由生产方在事件里显式给出（restoreAmount），消费方**不按比例推算**
-- （ADR Q24：部分还款不回补额度）。applied_amount 是本事务实际释放成功的金额：
-- 正常情况下等于 restore_amount；若 used_amount 已被别的路径释放过，applied_amount
-- 会小于 restore_amount —— 这个差额必须可查，不能静默吞掉。
-- ===========================================================================

CREATE TABLE IF NOT EXISTS credit_line_restorations (
    id                  BIGSERIAL PRIMARY KEY,

    -- 契约 §2.2 的业务幂等键：payment_transactions.transaction_id
    payment_ref         VARCHAR(128) NOT NULL,
    -- 触发本次回补的事件 id（排障用；不是业务幂等键）
    event_id            BIGINT,
    trace_id            VARCHAR(128),

    credit_line_id      BIGINT       NOT NULL,
    dealer_id           BIGINT,
    restore_amount      BIGINT       NOT NULL CHECK (restore_amount > 0),
    applied_amount      BIGINT       NOT NULL DEFAULT 0 CHECK (applied_amount >= 0),
    available_before    BIGINT       NOT NULL,
    available_after     BIGINT       NOT NULL,

    status              VARCHAR(16)  NOT NULL DEFAULT 'RESTORED',
        -- RESTORED 已回补（含 applied_amount < restore_amount 的部分回补，差异见 note）
    note                VARCHAR(500),

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- L2 业务幂等：同一笔银行回款只允许回补一次
    CONSTRAINT uk_credit_line_restore_payment_ref UNIQUE (payment_ref),
    -- 实际释放不得超过认领额；超过说明金额口径被改坏了，库层直接拒
    CONSTRAINT ck_credit_line_restore_applied CHECK (applied_amount <= restore_amount)
);

CREATE INDEX IF NOT EXISTS idx_clr_credit_line ON credit_line_restorations (credit_line_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_clr_trace ON credit_line_restorations (trace_id);

COMMENT ON TABLE credit_line_restorations IS
    'PAYMENT_MATCHED 消费侧的额度回补台账。payment_ref 唯一约束是框架 event_consumptions 之外的第二层（L2）幂等';
COMMENT ON COLUMN credit_line_restorations.payment_ref IS
    '契约 §2.2 的 paymentRef = payment_transactions.transaction_id；同一笔回款重复发布事件时必须撞这个键';
COMMENT ON COLUMN credit_line_restorations.applied_amount IS
    '本次事务实际释放的额度。正常等于 restore_amount；小于它说明 used_amount 已被其他路径释放过（差额必须可查）';
COMMENT ON COLUMN credit_line_restorations.restore_amount IS
    '事件显式给出的回补额（放款时占用的本金），消费方不按比例推算（ADR Q24）';
