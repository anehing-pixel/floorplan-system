-- ===========================================================================
-- V56：行情快照表（W-U2 / R3.3，I-12）
-- ===========================================================================
--
-- 【规格源】D04 §3.13；枚举 RepairHistoryFlag 取 D00 §8.5；双层约束取 D04 §6。
--
-- 【这张表存的是"一组车的行情"，不是"一台车的估值"】
-- 一行 = 一个车型归一化键（model_key：车型/年式/里程带/修復歴）在某个口径日的行情结论。
-- vehicle_valuations 是单车结论，它通过 market_snapshot_id 指回来，
-- 于是"这台车估 1,120,000"能被还原成"当时那批同型车行情是多少、样本几条、趋势如何"。
-- 没有这张表，估值就是无源之水：审计问"依据哪份行情"只能回答"某天的一个数"。
--
-- 【为什么按 model_key 而不是按 vin 存】
-- 行情是群体统计量。按 VIN 存 = 给每台车复制一份相同统计，
-- 既放大存储，又会让"同型车行情不一致"这种脏数据看起来合法。
-- model_key 是归一化后的键，写入方（I-12 行情源，W-U3）与读取方（估值域）只认这一个键，
-- 归一化规则本身属外部接口约定，不在本包内自造（见实现说明 §2 依赖缺口）。
--
-- 【为什么 trend_30d 用 NUMERIC(6,3) 而不用百分比布尔】
-- 趋势是连续量，作业与报表要在同一列上排序；用 NUMERIC 表达，
-- 符号约定（正=上涨）写进列注释，避免两处相反解释。
-- 30 日窗口固定，窗口长度若将来可配置须换列名（否则旧行的"trend_30d"含义会变）。
--
-- 【sample_count 与 market_price 同时 NOT NULL/可空的取舍】
-- D04 §3.13 未标 market_price 非空。这里 market_price 允许 NULL、
-- sample_count 允许 0：源报"这批车型当期无成交"是合法事实，
-- 必须能落库；若强制非空，写入方就会填 0 或 1 个假价来过关，
-- 而估值域读到的就是被污染的行，且没有任何线索能发现。
-- ===========================================================================

CREATE TABLE IF NOT EXISTS valuation_market_snapshots (
    id                BIGSERIAL     PRIMARY KEY,
    -- 业务键：MKT-{model_key}-{yyyymmdd}（D00 §13.1 式）
    snapshot_key      VARCHAR(128)  NOT NULL UNIQUE,
    model_key         VARCHAR(128)  NOT NULL,      -- 车型/年式/里程带/修復歴 归一化键
    brand             VARCHAR(64),
    model             VARCHAR(128),
    model_year_from   INT,                         -- 年式区间（含）；两列必须成对出现，见 CHECK
    model_year_to     INT,
    mileage_band      VARCHAR(16),                 -- 里程带（如 3-5万km）：字符串而不是两个整数列，
                                                   -- 因为带边界由行情源定义，我方不重定义分组
    repair_history    VARCHAR(16),                 -- RepairHistoryFlag
    market_price      BIGINT,                      -- 相场（整数日元）；NULL = 当期无成交
    sample_count      INT          NOT NULL DEFAULT 0 CHECK (sample_count >= 0),
    trend_30d         NUMERIC(6,3) CHECK (trend_30d IS NULL OR (trend_30d BETWEEN -999.999 AND 999.999)),
                                                   -- 30 日趋势，正=上涨；标度已由 NUMERIC(6,3) 限死，
                                                   -- 这条 CHECK 的作用只是让"越界"显式报错而不是静默截断
    provider          VARCHAR(64),
    as_of_date        DATE         NOT NULL,       -- 行情口径日（业务日，非落库时间戳）
    source_message_id BIGINT,                      -- I-12 入站溯源（integration schema=跨上下文，逻辑引用无 FK）
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_snapshot_repair CHECK (repair_history IS NULL
        OR repair_history IN ('NONE','MINOR','MAJOR','UNKNOWN')),
    -- 年式区间有序且成对：只填 from 不填 to 会让"这行覆盖哪些年式"无法判定，
    -- 归一化匹配时会把该行当成无上限区间，行情被错误地套用到新车上。
    CONSTRAINT ck_snapshot_year_pair CHECK ((model_year_from IS NULL) = (model_year_to IS NULL)),
    CONSTRAINT ck_snapshot_year_order CHECK (model_year_from IS NULL
        OR model_year_to IS NULL OR model_year_from <= model_year_to)
);

-- 业务去重：同一键、同一口径日、同一 provider 只能有一条行情结论。
-- 重复插两条 = 估值域取哪一条取决于扫描顺序，审慎值随之漂移且无痕迹。
-- ⚠ 同 V55 uk_val_input 的 NULL 边界：provider 可空时 Postgres 视 NULL 互不相等，
--   能插两条 (model_key, as_of_date, NULL)。登记为待签字（实现说明 §3-2），
--   本包不擅自把它改成 NOT NULL——那会要求 I-12 侧必须先给出 provider 口径。
CREATE UNIQUE INDEX IF NOT EXISTS uk_snapshot_key_date_provider
    ON valuation_market_snapshots (model_key, as_of_date, provider);
-- 估值时取"该键最近一期"的读取路径
CREATE INDEX IF NOT EXISTS idx_snapshot_model_date
    ON valuation_market_snapshots (model_key, as_of_date DESC);
-- 无成交（market_price IS NULL）批次的巡检：行情覆盖率的治理入口
CREATE INDEX IF NOT EXISTS idx_snapshot_no_price
    ON valuation_market_snapshots (as_of_date DESC) WHERE market_price IS NULL;

COMMENT ON TABLE valuation_market_snapshots IS
    '行情快照（W-U2 / R3.3, I-12）：按车型归一化键群体统计的相场，只追加不修改。 单车估值通过 vehicle_valuations.market_snapshot_id 引用它，估值因此可复算。';
COMMENT ON COLUMN valuation_market_snapshots.market_price IS
    '相场（整数日元）。NULL = 当期无成交，是合法事实；不得用 0 代替，0 会被审慎值当成"分文不值"。';
COMMENT ON COLUMN valuation_market_snapshots.repair_history IS
    '修復歴维度。UNKNOWN 表示该批样本未采集修復歴：按 D00 §8.5， UNKNOWN 不得用于放款（取最审慎档），故引用本行的估值必须走降级判定。';
COMMENT ON COLUMN valuation_market_snapshots.trend_30d IS
    '30 日趋势（正=上涨）。窗口固定 30 日；改窗口须换列名，不能让历史行改含义。';
