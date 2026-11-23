-- ===========================================================================
-- V59：估值到期扫描索引（W-U2）
-- ===========================================================================
--
-- 【为什么单独一个迁移放索引】
-- D04 §3.12 把 `idx_val_expiry (valid_until) WHERE status='ACTIVE'` 列在估值表的
-- 索引清单里，D04 §2 又给它单独一个号段（V59）。本迁移按 §2 执行，V55 只建表与
-- uk_val_active / idx_val_vin_date，到期扫描索引留到这里 —— 两个理由：
--   1) 号段是收口方做前后序核对的锚点，把 V59 的内容并进 V55 会让 V59 变空迁移，
--      而"不许建空迁移"是本包任务卡的硬约束（空迁移会让 flyway_schema_history
--      出现一条没有实际变更的记录，排查"某版本改了什么"时是噪声）；
--   2) 到期作业（ValuationExpiryJob）与建表是两件事，索引随作业语义调整，
--      单独一个文件便于改主意时只动一处。
--
-- 【为什么谓词里不能写"已过期"——这是本迁移最容易写错的地方】
-- 直觉上应该建 `WHERE status='ACTIVE' AND valid_until <= CURRENT_DATE`，
-- 让索引"只收要处理的行"，作业扫起来更省。这条 DDL 会直接失败：
--   ERROR: functions in index expression must be marked IMMUTABLE
-- 部分索引的谓词在写入时求值，Postgres 要求其中的函数是 IMMUTABLE；
-- now()/CURRENT_DATE 是 STABLE（同一行在不同时间求值结果不同），因此不允许出现在谓词里。
-- 若真按直觉写，Flyway 在 V59 中断，而中断点在一个"只是加索引"的文件上，
-- 排查方向很容易被带偏到环境/权限上去。
-- 所以 D04 §3.12 的写法（只按 status 收）不是偷懒，是唯一可行的写法：
-- 索引收全部 ACTIVE 行，"是否过期"由作业在扫描时按 valid_until 与当天比较。
-- ACTIVE 行本身受 uk_val_active 约束为"一车一条"，量级等于在库车辆数，
-- 索引不会因为历史行增长而膨胀（历史行是 EXPIRED/SUPERSEDED，进不来）。
-- ===========================================================================

CREATE INDEX IF NOT EXISTS idx_val_expiry ON vehicle_valuations (valid_until) WHERE status = 'ACTIVE';

COMMENT ON INDEX idx_val_expiry IS
    'W-U2 估值到期作业扫描索引（D08 §3.3 / D04 §3.12）。作业侧的读取谓词：status=''ACTIVE'' AND valid_until < CURRENT_DATE（日期比较在查询里做，不能放进索引谓词 —— STABLE 函数不允许出现在部分索引谓词中）。';

-- 覆盖索引：到期扫描后作业要立刻按 (vin, purpose) 定位并置 EXPIRED/SUPERSEDED，
-- 带 INCLUDE 让"取候选集"这一步是 index-only scan，不回表。
-- 历史行不进（谓词只收 ACTIVE），所以这个索引的体积跟随在库车数而不是累计估值数。
CREATE INDEX IF NOT EXISTS idx_val_expiry_covering
    ON vehicle_valuations (valid_until, vin)
    INCLUDE (purpose, status)
    WHERE status = 'ACTIVE';

-- ===========================================================================
-- 【本迁移有意不含的东西：vehicle_units.residual_value_source 的宽度问题】
-- D08 §9 要求 W-U2 把估值结论投影进 V11 的三个残值快照列，其中
-- `residual_value_source VARCHAR(16)`（V11:290，注释取值 ESTIMATED/MARKET/MANUAL）。
-- 而本域要写的来源名是 ValuationSource 枚举名，最长值 THIRD_PARTY_APPRAISAL = 21 字符、
-- AUCTION_WON_PRICE = 17 字符 —— **都超过 16**，投影 UPDATE 会在运行期报
-- value too long for type character varying(16)。
-- 两条修复路径都不属于本包可以自取的：
--   ① 改列宽 = 修改既有列，直接违反 D04 §5「扩展表只加不改不删」；
--   ② 加新列 = 与 W-U1 的 V50（vehicle_units USED 列，D04 §5.1）撞同一张表的同一语义，
--      属并行重叠区，不由本包单方面落。
-- 因此本轮**不写这三列**（代码里不产生对 residual_value_* 的写入），
-- 冲突登记为实现说明 §3-6 与回报第 ④ 节，等裁决后一次性接线。
-- 不做映射（例如把 THIRD_PARTY_APPRAISAL 缩写成 MARKET 塞进 16 字符）：
-- 那是把来源事实改写掉，投影列就从"证据"变成"猜测"，比不写更糟。
-- ===========================================================================
