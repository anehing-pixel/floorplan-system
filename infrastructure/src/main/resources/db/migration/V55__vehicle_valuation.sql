-- ===========================================================================
-- V55：多源审慎估值主表 + 外部源输入表（W-U2 / R3）
-- ===========================================================================
--
-- 【规格源】列与索引逐条取 D04 §3.12；枚举取值取 D00 §8.5；
--   状态机取 D03 §3.7；两层约束（Java 枚举 + DB CHECK）取 D04 §6（R13.4）。
--
-- 【为什么主表把四个来源各存一列，同时又有一张 valuation_inputs】
-- 两套并存不是冗余，是两种用途：
--   1. `auction_won_price / appraised_value / market_price / manual_value` 四列
--      是**这一行结论的直接输入**，读一次就够，供估值查询端点与闸门直接取；
--   2. `valuation_inputs` 是**全部输入的审计明细**（D03 §2.4「保留全部输入用于审计」），
--      多行、带 provider/as_of/weight/rank，审慎值必须能由它复算。
--   只留 (2)：每次读闸门都要聚合子表，估值这条热路径（建账、放款、重估作业）
--   全表扫子行；只留 (1)：多源加权、按报价日取数、provider 区分就无法表达，
--   审慎值算完即丢过程，审计问"这个 1,120,000 怎么来的"答不出来（A6 验收要复算）。
--
-- 【为什么没有 dealer_id】
-- D04 §3.12 不列。经销商归属以 `vehicle_units.dealer_id` 为唯一权威（V1:31 vin 是主键），
-- 在这里再存一份 = 车辆易主后两处不一致，而估值行恰恰会在易主后继续被引用。
-- 需要按经销商筛估值时走 `JOIN vehicle_units ON vin`（重估作业就是这么写的）。
--
-- 【金额：整数日元 BIGINT】AGENTS.md §6.3 铁律，禁 double/float。
--   四列来源价与 prudent_value 同标度，比较与取小（MIN_OF_SOURCES）不做任何换算。
--
-- 【日期：valuation_date / valid_until 用 DATE】
-- D04 §3.12 定为 DATE（业务日口径，与 interest_ledger.biz_date 一致）。
-- 注意这决定了到期判定是"日粒度"：估值在有效期内当天不随时钟过期。
-- 与 V59 的作业扫描条件必须配套（那里写为什么不能在索引谓词里用 now()）。
--
-- 【枚举列全部带 CHECK】
-- D04 §6 的双层约束要求枚举列在库层也有 CHECK（反面样例是
-- release_orders.confirmation_authority 那种无约束字符串——任意值都能落库）。
-- 本表是新表、无存量行，CHECK 取值集就是 D00 §8.5 的全集，不存在
-- "存量值违反新约束导致迁移回滚"的风险（V19 的教训），所以写成硬约束。
-- ===========================================================================

CREATE TABLE IF NOT EXISTS vehicle_valuations (
    id                    BIGSERIAL    PRIMARY KEY,
    -- 业务键：VAL-{vin}-{yyyymmdd}-{seq}（D00 §13.1 的估值键式）。
    -- 用字符串键而不是裸 id 对外暴露，是为了幂等重放时能给前端一个可回查的标识。
    valuation_key         VARCHAR(96)  NOT NULL UNIQUE,
    vin                 VARCHAR(32)  NOT NULL,          -- 与 vehicle_units.vin 同宽同型（V1:31）
    purpose               VARCHAR(24)  NOT NULL,        -- ValuationPurpose（D00 §8.5 四值）
    -- 四个来源列：事实，不是判断。NULL = 这次估值没有这个源，
    -- 与 0（源报了 0）是两种事实，不许用 0 代替"没报"（否则 MIN_OF_SOURCES 会把
    -- 未接入的源当成"市场认为这台车一文不值"，审慎值直接归零）。
    auction_won_price     BIGINT       CHECK (auction_won_price IS NULL OR auction_won_price >= 0),
    appraised_value       BIGINT       CHECK (appraised_value IS NULL OR appraised_value >= 0),
    market_price          BIGINT       CHECK (market_price  IS NULL OR market_price  >= 0),
    manual_value          BIGINT       CHECK (manual_value  IS NULL OR manual_value  >= 0),
    -- 审慎值 = 放款基准的唯一来源（D04 §3.12 原话）。NOT NULL 是有意的：
    -- 允许 NULL 就等于允许"没有基准也能进入放款链路"，那正是 R3.1 要消灭的
    -- `invoice_amount × 0.90` 兜底路径的另一种形态。
    prudent_value         BIGINT       NOT NULL CHECK (prudent_value >= 0),
    prudent_rule          VARCHAR(24)  NOT NULL,        -- PrudentValueRule 快照（政策后续变更不影响历史）
    primary_source        VARCHAR(24)  NOT NULL,        -- ValuationSource：结果来自哪个源
    auction_lot_id        BIGINT,                       -- 溯源落札（逻辑引用 auction_lots，同包内 V61 建表，
                                                       -- 故此处不建 FK：V55 先于 V61，FK 在 V55 里建会失败）
    market_snapshot_id    BIGINT,                       -- 溯源行情（逻辑引用 V56；同上，跨迁移顺序不能在 V55 建 FK）
    appraisal_provider    VARCHAR(64),
    appraisal_no          VARCHAR(64),
    report_path           VARCHAR(512),                 -- 评估报告附件（本地路径）
    report_digest         VARCHAR(64),                  -- 摘要：路径会被运维挪动，摘要才是不可变证据
    valuation_date        DATE         NOT NULL,
    valid_until           DATE         NOT NULL,        -- 有效期 [D-05] 默认 +45 日；数值不硬编码（见实现说明 §4）
    status                VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    overridden_by         VARCHAR(64),                  -- 人工覆盖二人（U-07）：操作人
    co_signed_by          VARCHAR(64),                  -- 二人：复核人
    source_message_id     BIGINT,                       -- 外部行情入站溯源（integration schema，跨上下文=逻辑引用，无 FK）
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by            VARCHAR(64),
    updated_by            VARCHAR(64),
    version               INT          NOT NULL DEFAULT 0,   -- 乐观锁：状态迁移会改写本行，快照内容不改写

    -- ---- 双层约束：枚举全集（D00 §8.5 / D03 §3.7 / D04 §6）----
    CONSTRAINT ck_val_purpose CHECK (purpose IN
        ('ADVANCE_BASE','PERIODIC_RECHECK','EVENT_RECHECK','DISPOSAL_PRICING')),
    CONSTRAINT ck_val_status CHECK (status IN ('DRAFT','ACTIVE','EXPIRED','SUPERSEDED')),
    CONSTRAINT ck_val_rule CHECK (prudent_rule IN ('MIN_OF_SOURCES','WEIGHTED')),
    CONSTRAINT ck_val_source CHECK (primary_source IN
        ('AUCTION_WON_PRICE','THIRD_PARTY_APPRAISAL','MARKET_MATRIX','MANUAL_OVERRIDE','INTERNAL_DEGRADED')),

    -- ---- 覆盖二人 SOD 的库层表达（D08 §3.4「覆盖未经第二人复核，不得作为放款基准」）----
    -- 判定"能不能作放款基准"在应用层（UsedValuationGuard），库里只钉两件事：
    --   1. 填了操作人就必须填复核人，且不能是同一个人 —— 挡住"单人把基准抬高"；
    --   2. 覆盖值必须真的存在 —— 挡住"标了覆盖却没数"（下游会退回系统审慎值，
    --      表面上成功、实际覆盖没生效，这是最难发现的一类错）。
    CONSTRAINT ck_val_override_pair CHECK (overridden_by IS NULL
        OR (co_signed_by IS NOT NULL AND co_signed_by <> overridden_by AND manual_value IS NOT NULL)),
    -- 被放款引用后不可回退（D03 §3.7 I4）在库里只能表达一半：
    -- ACTIVE 之后的行不许"退回 DRAFT"由应用层状态机保证；
    -- 这里守的是另一头：DRAFT 不许有生效时间戳痕迹。
    CONSTRAINT ck_val_draft_clean CHECK (status <> 'DRAFT' OR valid_until >= valuation_date)
);

-- 同一 VIN 同时最多一个 ACTIVE（D03 §3.7 / D04 §3.12 / D00 §8.5 三处同口径）
CREATE UNIQUE INDEX IF NOT EXISTS uk_val_active ON vehicle_valuations (vin) WHERE status = 'ACTIVE';
-- 估值查询端点 GET /api/valuations/active?vin= 的读取路径（点查走 uk_val_active，
-- 历史列表走这条：按日期倒序分页）
CREATE INDEX IF NOT EXISTS idx_val_vin_date ON vehicle_valuations (vin, valuation_date DESC);
-- 人工覆盖的治理队列：覆盖了但还没第二人签（D08 §3.4 要求可枚举）
CREATE INDEX IF NOT EXISTS idx_val_override_unsigned
    ON vehicle_valuations (overridden_by)
    WHERE overridden_by IS NOT NULL AND co_signed_by IS NULL;

COMMENT ON TABLE vehicle_valuations IS
    '车辆估值主表（W-U2 / R3）。一行 = 一次估值结论：来源事实 + 审慎值 + 有效期。 不可变：重估写新行、旧行置 SUPERSEDED，绝不改写历史行（P20 重估不回溯的存储保证）。';
COMMENT ON COLUMN vehicle_valuations.purpose IS
    '估值用途：ADVANCE_BASE 放款基准 / PERIODIC_RECHECK 定期重估 / EVENT_RECHECK 事件触发重估 / DISPOSAL_PRICING 处置定价（R3.5：处置定价从此允许用估值，现状代码注释是禁止的）。 D00 §8.5 是取值权威源；D08 §3.1 写的三值是同一枚举的子集表述，已按 D00 落四值。';
COMMENT ON COLUMN vehicle_valuations.prudent_value IS
    '审慎值（整数日元）= 放款金额基准的唯一来源。与 base_amount_source=PRUDENT 配对写入 advances。';
COMMENT ON COLUMN vehicle_valuations.primary_source IS
    '结论主源。INTERNAL_DEGRADED（外部不可得的兜底）不得作为放款基准： 结构保证在 ValuationSource 的 advanced=false + UsedValuationGuard 闸门，不靠调用方自觉。';
COMMENT ON COLUMN vehicle_valuations.valid_until IS
    '有效截止日（DATE，日粒度）。到期作业扫描走 V59 的 idx_val_expiry， 不在库里存"当前是否有效"——那是派生态，存了就会与作业结果打架。';
COMMENT ON COLUMN vehicle_valuations.version IS '行乐观锁；业务版本号在 ltv_policies，两者无关。';

-- ===========================================================================
-- 估值输入明细表（D04 §3.12 附表）：全部输入留档，审慎值可由它复算
-- ===========================================================================
CREATE TABLE IF NOT EXISTS valuation_inputs (
    id             BIGSERIAL    PRIMARY KEY,
    valuation_id   BIGINT       NOT NULL REFERENCES vehicle_valuations(id) ON DELETE CASCADE,
    source         VARCHAR(24)  NOT NULL,      -- ValuationSource
    value          BIGINT       NOT NULL,      -- 该源报的价（整数日元）
    ref            VARCHAR(128),               -- 源侧引用号
    provider       VARCHAR(64),                -- 供应商/机构
    as_of          DATE         NOT NULL,      -- 报价口径日，可与落库日不同
    weight         NUMERIC(4,3) NULL,          -- 加权规则用；MIN_OF_SOURCES 行留 NULL
    rank           INT          NULL,          -- 同一源多行时的次序（D04 §3.12）
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_input_source CHECK (source IN
        ('AUCTION_WON_PRICE','THIRD_PARTY_APPRAISAL','MARKET_MATRIX','MANUAL_OVERRIDE','INTERNAL_DEGRADED')),
    -- 权重 0.000–1.000：NUMERIC(4,3) 的标度本身容不下 >9.999，
    -- 但"随便填个大数当权重"会静默改变审慎值，所以写成显式区间。
    CONSTRAINT ck_input_weight CHECK (weight IS NULL OR (weight >= 0 AND weight <= 1)),
    -- 单行输入不许为负：源报价为负属于数据污染，应进差异工单而不是进审慎值。
    CONSTRAINT ck_input_value CHECK (value >= 0)
);

-- 同一估值内 (source, provider, as_of) 唯一：重复插入同价无害，但价格不同时
-- 审慎值会随"最后写入"漂移，而漂移是不可见的（结果仍是合法数字），必须库层钉死。
-- ⚠ 已知边界：provider 可为 NULL，Postgres 里 NULL 彼此不等，
--   所以 (source, NULL, as_of) 能插两行——这不是漏写，是 D04 §3.12 未定 provider 非空。
--   处置：应用层写 MANUAL_* 类源时统一填 ''，其余源必填 provider；
--   彻底解法（provider NOT NULL）列为待签字项（实现说明 §3-2）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_val_input ON valuation_inputs (valuation_id, source, provider, as_of);
CREATE INDEX IF NOT EXISTS idx_val_input_val ON valuation_inputs (valuation_id);
CREATE INDEX IF NOT EXISTS idx_val_input_source_date ON valuation_inputs (source, as_of DESC);

COMMENT ON TABLE valuation_inputs IS
    '估值输入明细（W-U2）：多源报价的原始证据，与主表同事务写入（D08 §3.2）。 ON DELETE CASCADE 只在估值被物理删除时生效，而 P20 下估值不物理删； 留 CASCADE 是为了测试夹具能整批清场，不给回归留孤儿行。';

-- ===========================================================================
-- 【本迁移与两份文档的三处不一致（详见实现说明 §3）】
-- 1. ACTIVE 唯一性的维度：D04 §3.12 / D03 §3.7 / D00 §8.5 三处一致为「同 VIN 一个 ACTIVE」，
--    本迁移照此建 uk_val_active(vin)；D08 §3.3 写的是「同 VIN 同 purpose 各一条 ACTIVE」。
--    两者不等价：按 VIN 唯一时，一台车同时存在放款基准与处置定价估值会插失败。
--    本包不擅自放宽（放宽 = 改三处一致的定义），已在 V55 之外把该冲突列为待签字。
-- 2. status 无 CANCELLED：D03 §3.7 的状态机是 DRAFT→ACTIVE→{EXPIRED|SUPERSEDED}，
--    本迁移的 CHECK 取值集据此；若业务要"作废"，须先补 D03 §3.7 再补 CHECK。
-- 3. purpose 取值：D00 §8.5 四值（本迁移采用），D08 §3.1 三值（REVALUATION/DISPOSAL_FLOOR 不在 D00 内）。
--    已按「命名与枚举以 D00 为权威」处理，D08 侧待回灌。
-- ===========================================================================
