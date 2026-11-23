-- ===========================================================================
-- V57：LTV 政策表 + 分档带表（W-U2 / R3.2、R3.4）
-- ===========================================================================
--
-- 【规格源】列与约束逐条取 D04 §3.14；不变式 L1–L5 取 D08 §4.3；
--   D13 §4.4 WU2-10/12 规定「每行可 DDL 表达的 CHECK 必须写进 V57，不许只留在单测里」。
--
-- 【这里存的是"判断"，不是"事实"】
-- V55/V56 存市场报了什么价；本表存"什么样的车按几成垫"。判断必须版本化，
-- 且生效动作要与维护动作分人，否则政策可以自己改、自己生效。
--
-- 【分档维度为什么是 7 个而不是按价格区间】
-- D04 §3.14 给的维度是 車検残/修復歴/評価点/年式/里程/车型热度/经销商评级 —— 与
-- D08 §4.1 的取带输入一致（里程与修復歴 UNKNOWN 走最保守档也要求这 7 个维度可表达）。
-- 按价格分档是 LEGACY 的思路，USED 的定价因子是车况，不是车价。
--
-- 【为什么带集合独立成表而不是 JSON 列】
-- D04 §6 要求每行可表达的 CHECK 进 DDL（区间方向、比率范围、priority>0）。
-- JSON 列把这些校验全部推到应用层，而政策数据恰恰是会被人工改的那一类。
--
-- 【命中语义与 priority】
-- D00 基线：「按 priority 升序首个命中，无兜底」；D08 §4.4 另给方案①（互斥带唯一命中）。
-- WU2-09 已裁定：代码里 priority 与 price_lower 混用属缺陷 → 本表不建价格区间列，
-- priority 就是唯一排序键，命中顺序 = priority 升序，"首个命中"在应用层实现，
-- 方案①需要的"任意输入恰好命中一带"无法用 DDL 表达（D04 §3.14 明说做不到），
-- 因此按 D04 给的三件套落地：边界样本单测 + 保存时嵌套带断言 + 生效前校验作业。
-- ===========================================================================

CREATE TABLE IF NOT EXISTS ltv_policies (
    id             BIGSERIAL    PRIMARY KEY,
    policy_code    VARCHAR(32)  NOT NULL,
    version        INT          NOT NULL DEFAULT 1 CHECK (version >= 1),
    policy_name    VARCHAR(128) NOT NULL,
    -- 状态三值（D04 §3.14）。D04 §3.14 无 PENDING：政策生效是一步动作，
    -- "待生效"这个中间态由 approve 端点内部校验二人分离后直接 ACTIVE。
    status         VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    effective_from DATE         NOT NULL,
    effective_to   DATE,                         -- NULL = 长期有效
    -- 兜底比率 [D-05]。D04 §3.14 明确「必须 < 1.0」并标待定源，
    -- 故本列允许 NULL（未签字时不落数），但一旦填写就必须 <1.0。
    default_ratio  NUMERIC(5,4) CHECK (default_ratio IS NULL OR (default_ratio > 0 AND default_ratio < 1)),
    created_by     VARCHAR(64),
    approved_by    VARCHAR(64),
    approved_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 同一政策码同一版本只能一行：重复提交会插出同号双行，
    -- "取最新 ACTIVE"就会同时命中两行而结果取决于扫描顺序。
    CONSTRAINT uk_ltv_policy UNIQUE (policy_code, version),
    -- L5（D08 §4.3）：起草人与生效人不得同人。数据库层钉死，与 V55 覆盖二人 SOD 同一手法。
    -- 只判"两个名字必须不同"这个最低限度事实：角色层面的分离由 RoleSeparationPolicy 判。
    CONSTRAINT ck_ltv_policy_sod CHECK (approved_by IS NULL OR created_by IS NULL
                                        OR approved_by <> created_by),
    -- 生效/失效区间有序：反向区间的政策永远命中不到，而它看起来是完整的。
    CONSTRAINT ck_ltv_policy_effective CHECK (effective_to IS NULL OR effective_from <= effective_to),
    CONSTRAINT ck_ltv_policy_status CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
    -- 生效中的政策必须有生效痕迹，否则"谁让它生效的"无法回答。
    CONSTRAINT ck_ltv_policy_approved CHECK (status <> 'ACTIVE' OR (approved_by IS NOT NULL
                                        AND approved_at IS NOT NULL))
);

-- L1 不变式的存储保证：同一政策码同一时刻至多一个生效版本
CREATE UNIQUE INDEX IF NOT EXISTS uk_policy_active ON ltv_policies (policy_code) WHERE status = 'ACTIVE';
-- 命中查询的读取路径（取 ACTIVE 版本；部分索引不受 RETIRED 增长影响）
CREATE INDEX IF NOT EXISTS idx_ltv_policy_code ON ltv_policies (policy_code, version DESC);

COMMENT ON TABLE ltv_policies IS
    'LTV 政策主表（W-U2 / R3.2、R3.4）。版本化：ACTIVE 行的带集合被引用后不可就地改写， 改带必须插新版本并置旧行 RETIRED（D08 §4.3 L4）。';
COMMENT ON COLUMN ltv_policies.default_ratio IS
    '兜底比率 [待定源 D-05]，CHECK 强制 <1.0。本包 seed 为 NULL： 未签字前不预置数值，也不参与命中判定（L3：无兜底档）。';
COMMENT ON COLUMN ltv_policies.version IS '政策代际（业务版本号），与行乐观锁无关。';

-- ===========================================================================
-- 分档带表：一行 = 一个车况组合档
-- ===========================================================================
CREATE TABLE IF NOT EXISTS ltv_policy_bands (
    id                          BIGSERIAL     PRIMARY KEY,
    policy_id                   BIGINT        NOT NULL REFERENCES ltv_policies(id) ON DELETE CASCADE,
    priority                    INT           NOT NULL,      -- 命中顺序，小者优先
    age_band_min_months         INT CHECK (age_band_min_months IS NULL OR age_band_min_months >= 0),
    age_band_max_months         INT CHECK (age_band_max_months IS NULL OR age_band_max_months >= 0),
    mileage_band_min_km         INT CHECK (mileage_band_min_km IS NULL OR mileage_band_min_km >= 0),
    mileage_band_max_km         INT CHECK (mileage_band_max_km IS NULL OR mileage_band_max_km >= 0),
    repair_history_in           VARCHAR(64),                 -- CSV 集合，如 NONE,MINOR
    grade_min                   NUMERIC(3,1),
    grade_max                   NUMERIC(3,1),
    inspection_remaining_min    INT CHECK (inspection_remaining_min IS NULL OR inspection_remaining_min >= 0),
    model_heat                  VARCHAR(16),                 -- {HIGH,NORMAL,LOW,UNKNOWN}
    dealer_grade_in             VARCHAR(32),                 -- CSV 集合
    max_ratio                   NUMERIC(5,4)  NOT NULL CHECK (max_ratio > 0 AND max_ratio <= 1),
    rate_addon_bps              INT,                         -- 利率加点（基点）
    breach_threshold            NUMERIC(5,2),                -- 该档残值率阈值
    note                        VARCHAR(255),
    created_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- D04 §3.14：以下四对区间的方向约束「必须写进 V57，别只留在单测里」。
    -- 理由（WU2-12 把 D04 的 MUST 降级为单测断言是错误的）：约束在库里，
    -- 运维直插、导入脚本、将来的政策迁移工具都会被挡住；只在单测里，只有那条测试跑过才有保障，
    -- 而政策数据恰恰是会被人工改的那一类。
    -- 区间方向取「下界严格小于上界」：相等意味着零宽区间，永不含输入，
    -- 与「维度未填（NULL = 不设限）」在数据上长得一样 —— 这是 WU2-01
    -- 「resolve() 恒抛 LTV_002」那条缺陷在数据层的根源，必须在库里就挡住。
    CONSTRAINT ck_band_age CHECK (age_band_max_months IS NULL OR age_band_min_months IS NULL
                                  OR age_band_min_months < age_band_max_months),
    CONSTRAINT ck_band_mileage CHECK (mileage_band_max_km IS NULL OR mileage_band_min_km IS NULL
                                  OR mileage_band_min_km < mileage_band_max_km),
    CONSTRAINT ck_band_grade CHECK (grade_max IS NULL OR grade_min IS NULL
                                  OR grade_min < grade_max),
    CONSTRAINT ck_band_heat CHECK (model_heat IS NULL
                                  OR model_heat IN ('HIGH','NORMAL','LOW','UNKNOWN')),
    -- 比率区间（D04 §3.14 原式）：>0 排除 0 成。0 成会让 FLOOR(基准×0)=0 混进闸门，
    -- 表现为"额度存在但一分都垫不出"的静默失败；"这一档不给垫款"应该报 LTV_003 而不是给 0。
    -- <=1 排除 100% 以上（D08 §4.3 L2）。
    CONSTRAINT ck_band_priority CHECK (priority > 0),
    CONSTRAINT ck_band_breach CHECK (breach_threshold IS NULL OR (breach_threshold > 0 AND breach_threshold <= 100)),
    CONSTRAINT ck_band_rate_addon CHECK (rate_addon_bps IS NULL OR rate_addon_bps >= 0)
);

-- 同一政策内 priority 唯一：重复 priority 会让"首个命中"变成扫描顺序决定，
-- 而两条同优先级的带可以给出完全不同的成数。
CREATE UNIQUE INDEX IF NOT EXISTS uk_ltv_band_priority ON ltv_policy_bands (policy_id, priority);
-- 带集合的整批读取路径（政策详情、生效前校验作业、simulation 端点）
CREATE INDEX IF NOT EXISTS idx_ltv_band_policy ON ltv_policy_bands (policy_id, priority);

COMMENT ON TABLE ltv_policy_bands IS
    'LTV 分档带表（W-U2 / R3.2）。命中语义：按 priority 升序取首个命中，无兜底档（L3）。 维度列为 NULL 表示"该维不设限"，不是"该维为 0"。';
COMMENT ON COLUMN ltv_policy_bands.priority IS '命中顺序，小者优先（D04 §3.14）。不参与展示之外的任何判定。';
COMMENT ON COLUMN ltv_policy_bands.repair_history_in IS
    '修復歴取值集合（CSV，如 NONE,MINOR）。UNKNOWN 不写入任何带的集合内： 按 D00 §8.5 它必须走最保守档，由 LtvTierResolver 用保守兜底逻辑处理而非靠 CSV 命中。';

-- ===========================================================================
-- 【本迁移不插任何数据行 —— 这是有意的】
-- D08 §4.3 的示例带集合违反它自己给出的不变式 L3（含兜底档）与 G5
-- （低价档成数高于高价档会让 max_advance 随基准升高而下降），WU2-02/03 已判为
-- 「示例带违反自家 L1/L3，不得当正式参数落库」。Flyway 已应用的迁移不可修改，
-- 把已知错误的示例种进库，将来只能再加迁移覆盖 = 双重污染。
-- 正式带集合的数值属 [待定源 D-06]，因此本包 seed 为 0 行。
-- 必须让验收方知道的直接后果：
--   1) D06 §6.3.1 的 V-11「带集合非空」前置校验在本包交付时点**不会通过**；
--   2) LtvTierResolver 在空带集合下必须抛 LTV_003（无适用档），不得返回默认成数，
--      也不得回退到 0.90 发票公式（L4 已裁定：不许回退）。
-- 这两条都不是缺陷，是"数值未签字"的正确表现。详见实现说明 §4。
-- ===========================================================================
