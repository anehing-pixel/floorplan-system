-- ===========================================================================
-- V26：事件消费幂等表 + 消费游标表（W0-FOUNDATION 地基，所有服务共用）
-- ===========================================================================
--
-- 【为什么 V21 的 outbox_events 不够】
-- V21 只解决"事件不丢"（业务写与事件写同一本地事务）。它没有解决两件事：
--   1) 重复投递——dispatcher 是"读一条、处理一条、推游标"，若在"业务已处理成功、游标未推进"
--      这个窗口里进程被杀，下一轮会把同一事件再投一次。金融动作（建借据、回补额度）
--      重复执行就是资损，必须有一张表记住"某个消费者处理过某个事件"。
--   2) 断点续读——没有游标，每个消费者每轮都要全表扫历史事件，既慢又无法界定"从哪读起"。
--
-- 【两张表的分工】
--   event_consumptions：幂等表，一个 (event_id, consumer) 一行，记录处理结果与重试次数。
--   consumer_offsets ：游标表，一个 consumer 一行，记录已读到的最大 event_id。
--
-- 【为什么不用唯一索引兜 outbox 的发布去重】
-- V21 只有普通索引 idx_outbox_agg，且已落库不可修改（checksum）。发布去重由
-- JdbcOutboxPublisher 的 INSERT ... SELECT ... WHERE NOT EXISTS 在应用侧完成。
-- 注意这意味着"绕过应用直接 INSERT"不受去重保护——测试脚本插数据时必须自己保证唯一。
-- ===========================================================================

-- 消费幂等表：同一事件 + 同一消费者只会有一行，重复投递由主键冲突挡下
CREATE TABLE IF NOT EXISTS event_consumptions (
    event_id   BIGINT      NOT NULL,             -- 关联 outbox_events.id
    consumer   VARCHAR(64) NOT NULL,             -- 消费方标识，约定用服务名，如 'loan-service'
    status     VARCHAR(16) NOT NULL DEFAULT 'PENDING',
        -- PENDING 已登记未处理 / DONE 处理成功 / FAILED 处理失败待重试 / DEAD 重试超限（死信）
    attempts   INT         NOT NULL DEFAULT 0,   -- 已尝试次数；达到上限（3）即置 DEAD 并跳过
    last_error TEXT,                             -- 最后一次失败的异常信息，排障用
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);

-- 死信排查：按消费者捞出重试超限的事件（W3 重放功能落地前的唯一入口）
CREATE INDEX IF NOT EXISTS idx_event_consumptions_dead
    ON event_consumptions (consumer, status) WHERE status IN ('FAILED', 'DEAD');

-- 消费游标表：每个消费者一行，只记录"已经读到哪个 event_id"
CREATE TABLE IF NOT EXISTS consumer_offsets (
    consumer      VARCHAR(64) PRIMARY KEY,       -- 与 event_consumptions.consumer 同一取值口径
    last_event_id BIGINT NOT NULL DEFAULT 0,     -- 已处理（成功或死信跳过）的最大 outbox_events.id
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE event_consumptions IS '事件消费幂等表：(event_id,consumer) 主键挡重复投递，attempts 驱动重试/死信';
COMMENT ON TABLE consumer_offsets   IS '事件消费游标表：每消费者一行，记录已读到的最大 outbox_events.id';
COMMENT ON COLUMN event_consumptions.status IS 'PENDING 未处理 / DONE 成功 / FAILED 失败待重试 / DEAD 重试超限';
COMMENT ON COLUMN consumer_offsets.last_event_id IS '游标：失败不前进（下轮重试），成功或死信跳过才前进';
