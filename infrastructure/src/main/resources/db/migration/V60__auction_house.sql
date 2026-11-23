-- ===========================================================================
-- V60：拍卖场主表（W-U2 / R4.1）
-- ===========================================================================
--
-- 【规格源】D04 §3.4。三重身份是本表的存在理由：
--   `partner_code`（协同身份）、`settlement_account_ref`（付款对象）、
--   `admission_status`（准入）三列分别建模 —— 需求书 §2.4 明确要求显式区分，
--   而现状只把拍卖场当"付款对象"（supplier），导致"能不能和这家结算"与
--   "是不是我们的协同伙伴"与"现在是否准入"三件事挤在一个字段里。
--
-- 【为什么 settlement_cycle_days 是拍场属性而不是落札行属性】
-- 账期是对手方条款。放在落札行会出现两个后果：
--   1) 拍场改账期要回写历史落札行，历史就不可信；
--   2) 同一拍场不同笔用了不同账期，逾期判定（SETTLEMENT_DUE）出现两套口径。
-- 逐笔冻结的结算事实（含实际付款引用）在 auction_settlements（V62），各司其职。
--
-- 【admission_status 为什么不复用既有 dealers.status】
-- D10 §5 给拍卖场的 partner scope 是独立主体（`AUCTION`），与经销商准入是两套判定：
-- 经销商准入看资质与评分，拍场准入看会籍与结算能力。合并成一列会让"停用经销商"
-- 顺带停掉它作为拍场的结算通道（或反之），而两者在业务上没有联动关系。
--
-- 【[D-04] / [D-08] 待定源标注】
-- admission_status 的取值集属 D-04（拍场准入口径，未签字）；
-- settlement_cycle_days 的默认值属 D-08（账期天数，未签字）。
-- 因此本表**不预置任何数值、不写 DEFAULT**：列建出来，值由拍场登记端点写入，
-- 未签字前不允许任何"看起来合理"的默认值进库。
-- ===========================================================================

CREATE TABLE IF NOT EXISTS auction_houses (
    id                      BIGSERIAL    PRIMARY KEY,
    auction_house_code      VARCHAR(32)  NOT NULL UNIQUE,   -- USS / TAA / ...（D00 号段的拍场码）
    name                    VARCHAR(128) NOT NULL,
    country                 VARCHAR(8)   NOT NULL DEFAULT 'JP',
    admission_status        VARCHAR(16)  NOT NULL,          -- {ADMITTED,SUSPENDED,BLOCKED} [D-04]
    settlement_cycle_days   INT,                            -- 落札后结算期限（日）[D-08 未签字 ⟹ 不预置默认值]
    settlement_account_ref  VARCHAR(64),                    -- 逻辑引用 supplier_bank_accounts（跨上下文，无 FK）
    partner_code            VARCHAR(64),                    -- 逻辑引用 integration.partners（schema 属 W-U3/V70–V74，
                                                            -- 本分支不含该 schema ⟹ 只能逻辑引用）
    capability_json         TEXT,                           -- 接口能力描述（API/文件/门户），D-04 调研回收后回填
    guarantee_ability       VARCHAR(16),                    -- 保证能力：USED 域不用于回购判定，仅保留 LEGACY 兼容
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by              VARCHAR(64),
    updated_by              VARCHAR(64),
    version                 INT          NOT NULL DEFAULT 0,
    note                    VARCHAR(255),
    -- 准入态必须闭集：D04 §6 的枚举双层约束。写成无约束字符串就是
    -- release_orders.confirmation_authority 那个反面样例的复刻（任意值都能落库）。
    -- ⚠ [D-04] 未签字，本 CHECK 是 D04 §3.4 明确给出的三个取值，不含推测值；
    --   若 D-04 回收后增删取值，改这里属于口径变更，不是实现细节。
    CONSTRAINT ck_house_admission CHECK (admission_status IN ('ADMITTED','SUSPENDED','BLOCKED')),
    -- 账期一旦填写必须为正：0 或负会让 settlement_due_on = 落札日甚至早于落札日，
    -- 于是每笔落札当天就判逾期，SettlementDueAlertJob 天天误报。
    CONSTRAINT ck_house_cycle CHECK (settlement_cycle_days IS NULL OR settlement_cycle_days > 0),
    -- 停用的拍场必须留下停用时间，否则"还没准入"与"曾经准入后停用"在数据上不可区分。
    -- 本表用 updated_at + status 表达，不另建 closed_at：D04 §3.4 未列该列，
    -- 而"何时停用"在 audit_events 里有独立留痕（AGENTS.md §6.3），此处不重复建列。
    CONSTRAINT ck_house_country_len CHECK (char_length(country) BETWEEN 2 AND 8)
);

-- 准入巡检：按状态列出全部拍场（准入审查与结算通道开关的运维入口）
CREATE INDEX IF NOT EXISTS idx_house_admission ON auction_houses (admission_status, auction_house_code);

COMMENT ON TABLE auction_houses IS
    '拍卖场主表（W-U2 / R4.1）。三重身份显式分列：partner_code 协同 / settlement_account_ref 付款对象 / admission_status 准入。落札事实与逐笔结算事实在 auction_lots / auction_settlements。';
COMMENT ON COLUMN auction_houses.auction_house_code IS
    '拍场码，业务主键。auction_lots.auction_house_code 与 lot_key 都由它派生， 因此这个码改名 = 历史落札行的勾稽链断裂；状态机与端点都不允许改它（只增不改）。';
COMMENT ON COLUMN auction_houses.guarantee_ability IS
    'LEGACY 兼容列。USED 域的回购判定不读本列（D04 §3.4 明确），读本列会误启用回购。';
COMMENT ON COLUMN auction_houses.capability_json IS
    '接口能力原文（API/文件/门户）。字段集由对方接口决定，故不建结构化列： 为未知字段改迁移的成本远高于原文存一份。';
