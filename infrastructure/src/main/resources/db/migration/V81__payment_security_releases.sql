-- ===========================================================================
-- V81：PAYMENT_MATCHED 消费侧的解押释放台账（core / floorplan-core 写入）
-- ===========================================================================
--
-- 【这张表解决什么】
-- bank-service 匹配成功后发 PAYMENT_MATCHED（settled=true 才代表资金结清），
-- credit-service 消费事件做「额度回补」；core 拥有 vehicle_units.security_status 与
-- collateral_ledger，必须在自己的服务里消费同一事件做「解除担保」。
-- 禁止 bank 直写 core 的表（AGENTS §3.1；物理拆库即失效）。
--
-- 【L2 幂等，为什么不能只靠 event_consumptions】
-- 框架层 event_consumptions(event_id, consumer) 只挡「同一事件行重复消费」；
-- 同一次业务事实被重新发布成新 outbox 行时 event_id 会变，框架层放行。
-- 契约 §3.2 要求消费方按业务唯一键去重，这里用 payment_ref
-- （= payment_transactions.transaction_id）作为 L2 唯一键。
--
-- 【红线：只认资金结清事实】
-- 只有事件显式 settled=true 才允许解除担保；settled 缺失/false 一律跳过。
-- 部分回款不解除担保（ADR Q24 / D09 §8.3 的同族口径）。
--
-- 【解押 ≠ 抹消完成】
-- vehicle_units.security_status=RELEASED 表示「担保实体权利消灭」；
-- 登记簿上的抹消仍需权利人与登记义务人共同申请，本消费方只把
-- collateral_registration_batches.cancellation_status 推进到 REQUESTED，
-- 绝不写 DONE（全仓已有多处断言钉死这条）。
-- ===========================================================================

CREATE TABLE IF NOT EXISTS payment_security_releases (
    id                      BIGSERIAL PRIMARY KEY,

    -- 契约 §2.2 的业务幂等键：payment_transactions.transaction_id
    payment_ref             VARCHAR(128) NOT NULL,
    -- 触发本次释放的事件 id（排障用；不是业务幂等键）
    event_id                BIGINT,
    trace_id                VARCHAR(128),

    vin                     VARCHAR(32) NOT NULL,
    security_before         VARCHAR(16),
    security_after          VARCHAR(16),
    ledger_released         BOOLEAN      NOT NULL DEFAULT FALSE,
    cancellation_requested  BOOLEAN      NOT NULL DEFAULT FALSE,
    status                  VARCHAR(16)  NOT NULL DEFAULT 'RELEASED',
        -- RELEASED 已解除担保（含本就是 RELEASED 的幂等场景）
        -- NO_SECURITY 该车无担保记录，无需解除（资金已结清，不是失败）
        -- LEDGER_MISSING 担保物台账缺失：车辆侧能解则解，另写 HIGH 告警
    note                    VARCHAR(500),

    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- L2：同一笔银行回款只允许解除一次担保
    CONSTRAINT uk_payment_security_release_ref UNIQUE (payment_ref)
);

CREATE INDEX IF NOT EXISTS idx_psr_vin ON payment_security_releases (vin, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_psr_trace ON payment_security_releases (trace_id);

COMMENT ON TABLE payment_security_releases IS
    'PAYMENT_MATCHED 消费侧的解押释放台账。payment_ref 唯一约束是 event_consumptions 之外的第二层（L2）幂等';
COMMENT ON COLUMN payment_security_releases.payment_ref IS
    '契约 §2.2 的 paymentRef = payment_transactions.transaction_id；同一笔回款重复发布事件时必须撞这个键';
COMMENT ON COLUMN payment_security_releases.cancellation_requested IS
    '是否把关联登记批次的抹消状态推进到 REQUESTED；不等于抹消完成（DONE 只能由人工/外部证据推进）';
