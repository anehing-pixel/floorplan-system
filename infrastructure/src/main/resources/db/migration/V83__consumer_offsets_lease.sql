-- ===========================================================================
-- V83：事件消费游标的多实例租约（多实例前置）
-- ===========================================================================
--
-- 【问题】
-- consumer_offsets 按 consumer 一行。原实现里两个同名消费者进程（多副本部署）
-- 会同时读同一游标、同时处理同一批事件：轻则重复消费（业务幂等能兜住），
-- 重则两个进程互相覆盖 last_event_id 造成漏读。单实例部署发现不了。
--
-- 【做法】
-- 不新增表，也不改消费语义；在游标行上加「租约」：
--   lease_owner  当前持有处理权的实例 ID
--   lease_until  租约到期时间
-- EventDispatcher 每轮 poll 先原子认领/续租：
--   INSERT ... ON CONFLICT DO UPDATE ... WHERE 无主 / 自己持有 / 已过期
-- 认领成功才 drain；认领失败直接跳过本轮。持有者每秒续租一次（poll fixedDelay=1s）。
--
-- 【为什么不用 pg_advisory_lock】
-- advisory lock 是会话级/事务级锁：连接池归还连接时容易把锁状态带到下一请求，
-- 释放时机依赖连接生命周期。租约是数据行上的显式状态，可见、可排查、可跨连接池，
-- 且实例崩溃后到期自动失效（无需人工清理）。
--
-- 【语义边界】
-- 这是 at-least-once 的租约，不是 exactly-once：持锁进程若在 drain 中途崩溃，
-- 租约到期后另一实例会从同一 last_event_id 重读；重复消费由
-- event_consumptions(event_id, consumer) + 消费方业务唯一键兜住。
-- 因此租约长度取 5 分钟（远大于单批 100 条本地事件的正常处理时间），
-- 到期接管只会发生在真故障时。
-- ===========================================================================

ALTER TABLE consumer_offsets ADD COLUMN IF NOT EXISTS lease_owner VARCHAR(128);
ALTER TABLE consumer_offsets ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ;

COMMENT ON COLUMN consumer_offsets.lease_owner IS
    '当前持有该消费者处理权的实例 ID（hostname-pid-uuid）；为空=无人持锁，可立即认领';
COMMENT ON COLUMN consumer_offsets.lease_until IS
    '租约到期时间；持有者每轮 poll 续租，进程崩溃后到期可由其他实例接管（at-least-once）';

-- 到期接管扫描：按 lease_until 找可抢的消费者（行数极少，但仍建索引避免全表扫描）
CREATE INDEX IF NOT EXISTS idx_consumer_offsets_lease ON consumer_offsets (lease_until);
