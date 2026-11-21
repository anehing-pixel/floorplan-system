-- ===========================================================================
-- V21：Transactional Outbox 本地消息表（Wave 0 地基，所有服务共用）
-- ===========================================================================
--
-- 【为什么要有这张表】
-- 放款后的事后动作（借据生成、额度回补、DPD 更新、解押指令）允许秒级延迟，
-- 但不能丢。用"业务数据 + outbox 事件"同一个本地事务写入，再由定时任务投递，
-- 消费端靠唯一键幂等——这就是 outbox 模式，避免 Seata 全局事务的成本。
--
-- 【幂等锚点】
-- (event_type, aggregate_key) + status 控制重投。消费端处理时按 aggregate_key 去重。
-- 同一聚合的同类事件若已存在 PENDING/SENT，不重复插入。
-- ===========================================================================

CREATE TABLE IF NOT EXISTS outbox_events (
    id             BIGSERIAL PRIMARY KEY,
    event_type     VARCHAR(64)  NOT NULL,     -- LOAN_CREATED / PAYMENT_MATCHED / COLLATERAL_CHANGED
    aggregate_key  VARCHAR(128) NOT NULL,     -- 聚合键：vin 或借据号，消费端幂等锚点
    payload        TEXT         NOT NULL,     -- JSON，必含 vin + traceId
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
        -- PENDING 待投递 / SENT 已投递 / FAILED 投递失败待重试 / DEAD 死信
    attempts       INT          NOT NULL DEFAULT 0,
    last_error     TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at        TIMESTAMPTZ
);

-- 投递器扫描：按状态 + 创建时间取待投递
CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox_events (status, created_at)
    WHERE status IN ('PENDING', 'FAILED');
-- 幂等查询：同一聚合同类事件是否已存在
CREATE INDEX IF NOT EXISTS idx_outbox_agg ON outbox_events (event_type, aggregate_key);

COMMENT ON TABLE outbox_events IS '本地消息表（outbox模式）。业务写与事件写同一本地事务，定时投递，消费端幂等';
COMMENT ON COLUMN outbox_events.aggregate_key IS '幂等锚点：通常是vin或借据号，消费端据此去重';
