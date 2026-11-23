-- ===========================================================================
-- V61：落札主表 + 落札诸费用表（W-U2 / R4.1、R4.5）
-- ===========================================================================
--
-- 【规格源】D04 §3.5（列级）+ D04 §3.6（费用表）+ D03 §3.1（状态机闭集）。
--
-- 【为什么 lot_key 与 (拍场,场次,出品番号) 两个都要】
-- `lot_key = AUC:{house}-{yyyymmdd}-{lot_no}`（D00 §13.1）是给外部与端点用的稳定标识，
-- 拍卖行的落札番号（`bid_no`）在争议与再落札时会变，
-- 而场次内的出品番号（`lot_no`）在跨场次比对时才是同一含义；
-- 三者职责不同，不能用一个代替另一个：
--   lot_key   —— 对外标识、端点路径参数、幂等重放；
--   uk_lot_session —— 同一场次同一出品号只能一行（数据完整性）；
--   bid_no    —— 与拍卖行对账的业务号（对方只给这个号）。
-- 曾考虑只留 lot_key，把场次解析留在字符串里，但那会让"按场次批量入站"
-- 无法用 SQL 直接圈定范围（作业要么全表扫要么在应用层解析 key），故显式建列。
--
-- 【total_cost 为什么是冗余列】
-- 它等于 auction_lot_costs 的合计。存冗余是为了对账时不必每次聚合子表，
-- 代价是两处可能不一致 —— 所以一致性由作业/写入方维护，
-- 且本表加了 CHECK 保证它与费用合计同奇偶方向（非负），
-- 真正的"等于合计"校验放在结算守恒（AUC_005，V62）里，
-- 因为落札瞬间费用可能还没录全，此刻强等于会把正常流程挡死。
--
-- 【source_message_id 是证据链列】
-- D04 §3.5 标 ★：落札行必须能回答"这条数据是谁给的"。
-- 人工补录（import-legacy）的行该列为 NULL —— 这不是缺陷而是事实，
-- 所以端点必须显式标记来源，D07 §7.4 的 import-legacy 就是为此存在。
-- ===========================================================================

CREATE TABLE IF NOT EXISTS auction_lots (
    id                    BIGSERIAL    PRIMARY KEY,
    lot_key               VARCHAR(128) NOT NULL UNIQUE,      -- AUC:{house}-{yyyymmdd}-{lot_no}
    auction_house_code    VARCHAR(32)  NOT NULL,             -- 逻辑引用 auction_houses.auction_house_code
    session_date          DATE         NOT NULL,             -- 開催日
    session_no            VARCHAR(16),                       -- 開催回
    lot_no                VARCHAR(32)  NOT NULL,             -- 出品番号
    bid_no                VARCHAR(32)  NOT NULL UNIQUE,      -- 落札番号（与 lot_no 不同物，D04 §3.5）
    vin                   VARCHAR(32),                       -- 与 vehicle_units.vin 勾稽；可空：入站先于建账
    registration_plate    VARCHAR(16),                       -- 登録番号（不叫 registration_no：D00 §2.1 列名避讳）
    first_registered_on   DATE,
    mileage_km            INT          CHECK (mileage_km IS NULL OR mileage_km >= 0),
                                                            -- 拍卖票原样：可空 = 票面没给里程，
                                                            -- 与 0（票面给 0）是两种事实，不许混
    auction_grade         NUMERIC(3,1) CHECK (auction_grade IS NULL OR (auction_grade >= 0 AND auction_grade <= 10)),
    interior_grade        VARCHAR(4),
    exterior_grade        VARCHAR(4),
    repair_history        VARCHAR(16),                       -- RepairHistoryFlag
    won_price             BIGINT       NOT NULL CHECK (won_price >= 0),   -- 落札価（整数日元）
    total_cost            BIGINT       CHECK (total_cost IS NULL OR total_cost >= 0),
                                                            -- 落札価+诸费用合计（冗余，见头部注释）
    seller_member_no      VARCHAR(64),
    buyer_member_no       VARCHAR(64),
    settlement_due_on     DATE,                              -- 決済期限：入站时可能未知，待拍场确认
    status                VARCHAR(24)  NOT NULL DEFAULT 'RECEIVED',
    settlement_id         BIGINT,                            -- 逻辑引用 auction_settlements
                                                            -- （本包 V62 建表：不建 FK 的唯一原因是迁移应用顺序
                                                            --  V61 先于 V62，FK 在此处会失败）
    purchase_order_id     BIGINT       REFERENCES purchase_orders(id),
                                                            -- 与采购单映射。V7 的 purchase_orders 已在基线内，
                                                            -- 且两侧同属 core 上下文（D04 §1：同上下文建 FK）
    vehicle_unit_vin      VARCHAR(32),                       -- 建账后回填（与 vin 可同值，语义不同：
                                                            -- 前者是拍卖票原样，后者是已建账的确认）
    source_message_id     BIGINT,                            -- ★ 证据链：integration.inbound_messages.id
                                                            -- （跨 schema=跨上下文，D04 §1 用逻辑引用；
                                                            -- 且 integration schema 属 W-U3 的 V70–V74，基线不含）
    received_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    verified_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version               INT          NOT NULL DEFAULT 0,
    CONSTRAINT ck_lot_status CHECK (status IN
        ('RECEIVED','VERIFIED','DISPUTED','VOID','SETTLEMENT_DUE','PAID','DELIVERED','CLOSED')),
    CONSTRAINT ck_lot_repair CHECK (repair_history IS NULL
        OR repair_history IN ('NONE','MINOR','MAJOR','UNKNOWN')),
    -- 状态与时间戳配套：VERIFIED 却没 verified_at，等于"核查通过"这件事没有证据。
    -- DISPUTED→VERIFIED（差异工单关闭）也要重写 verified_at，本 CHECK 只钉"有状态必有时间"。
    CONSTRAINT ck_lot_verified CHECK (status <> 'VERIFIED' OR verified_at IS NOT NULL),
    -- 已结算的行必须有结算单：否则结算守恒（AUC_005）无法把这行纳入校验集合，
    -- 会出现"钱付了但没人对账"的行。
    CONSTRAINT ck_lot_settlement CHECK (status <> 'PAID' OR settlement_id IS NOT NULL),
    -- 建账确认必须 VIN 一致：vehicle_unit_vin 是"已建账"的标记，
    -- 与拍卖票上的 vin 不同时说明建账建到了另一台车上。
    CONSTRAINT ck_lot_unit_vin CHECK (vehicle_unit_vin IS NULL OR vin IS NULL
        OR vehicle_unit_vin = vin)
);

-- 同一拍场同一场次同一出品号只能一行（D04 §3.5 指定的 uk_lot_session）。
-- 缺它时场次批量入站的重放会插出重复落札，而两条都能各自进结算 = 重复付款。
CREATE UNIQUE INDEX IF NOT EXISTS uk_lot_session
    ON auction_lots (auction_house_code, session_date, lot_no);
-- 按 VIN 勾稽（建账前查"这台车是不是刚落过"、估值溯源 AUCTION_WON_PRICE）
CREATE INDEX IF NOT EXISTS idx_lot_vin ON auction_lots (vin);
-- 列表端点 GET /api/auction-lots?dealerId=&sessionDate=&status= 的读取路径
CREATE INDEX IF NOT EXISTS idx_lot_status_date ON auction_lots (status, session_date DESC);
-- 逾期预警作业（SettlementDueAlertJob）：只扫待结算的两态，历史行不进索引
CREATE INDEX IF NOT EXISTS idx_lot_settlement_due
    ON auction_lots (settlement_due_on) WHERE status IN ('VERIFIED','SETTLEMENT_DUE');
-- 按结算单反查落札明细（守恒校验与争议处理）
CREATE INDEX IF NOT EXISTS idx_lot_settlement ON auction_lots (settlement_id) WHERE settlement_id IS NOT NULL;

COMMENT ON TABLE auction_lots IS
    '落札主表（W-U2 / R4.1）。状态机闭集见 D03 §3.1： RECEIVED→VERIFIED→SETTLEMENT_DUE→PAID→DELIVERED→CLOSED，差异走 DISPUTED→{VERIFIED|VOID}； 非法迁移 RECEIVED→PAID（跳核查）、PAID→VERIFIED（回退）、CLOSED→* 由状态机方法拒绝。';
COMMENT ON COLUMN auction_lots.lot_key IS '对外稳定标识，D07 §7.4 端点路径参数 {lotKey} 就是它。';
COMMENT ON COLUMN auction_lots.bid_no IS
    '拍卖行给的落札番号，全局唯一。与 lot_no 不同物：lot_no 是出品番号（同一场次内唯一）， 落札失败/再落札时 lot_no 不变而 bid_no 变。';
COMMENT ON COLUMN auction_lots.total_cost IS
    '落札価 + 诸费用合计（整数日元，冗余）。与 auction_lot_costs 合计的一致性由写入方维护， 最终守恒在结算侧（AUC_005）校验，不在此处强等于 —— 落札瞬间费用可能尚未录全。';
COMMENT ON COLUMN auction_lots.source_message_id IS
    '★ 证据链：来源入站报文。NULL = 人工补录（import-legacy），此类行必须在审计里可枚举。';

-- ===========================================================================
-- 落札诸费用表（R4.5）：一行 = 一笔费用
-- ===========================================================================
-- 【为什么单独一张而不是一个 fees JSON】
-- 费用要参与税额口径（tax_treatment）与结算分摊，两种口径都要能按行聚合；
-- 存 JSON 就等于放弃按行聚合，只能整体读进应用再算，
-- 而"哪些费用计入取得原价"这件事一旦要审计，就必须能按行追溯。
CREATE TABLE IF NOT EXISTS auction_lot_costs (
    id              BIGSERIAL    PRIMARY KEY,
    lot_id          BIGINT       NOT NULL REFERENCES auction_lots(id) ON DELETE CASCADE,
    cost_type       VARCHAR(24)  NOT NULL,
    amount          BIGINT       NOT NULL CHECK (amount >= 0),   -- 整数日元；负数费用应走冲抵行而非负金额
    tax_treatment   VARCHAR(16),                                 -- [D-02 未签字] 税额口径
    seq             INT          NOT NULL DEFAULT 0,             -- 同类型多笔时的序号（唯一键的一部分）
    note            VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 闭集（D04 §3.6）。不在集合内的值会让"费用分类合计"漏项而不报错，
    -- 结算守恒（AUC_005）就会出现"合计对不上但没人知道"的状态。
    CONSTRAINT ck_cost_type CHECK (cost_type IN
        ('FEE','COMMISSION','CONSUMPTION_TAX','TRANSPORT','HANDLING','OTHER')),
    -- ⚠ [D-02] 税额口径未签字。本 CHECK 是 D04 §3.6 给出的三个取值，不含推测值；
    -- 允许 NULL：口径未知时宁可留空，也不给一个会进成本分摊计算的默认口径。
    CONSTRAINT ck_cost_tax CHECK (tax_treatment IS NULL
        OR tax_treatment IN ('TAXABLE','NON_TAXABLE','MARGIN_TAX')),
    CONSTRAINT ck_cost_seq CHECK (seq >= 0)
);

-- (lot_id, cost_type, seq) 唯一（D04 §3.6）：同类型第 N 笔只能有一行，重放幂等靠它
CREATE UNIQUE INDEX IF NOT EXISTS uk_lot_cost ON auction_lot_costs (lot_id, cost_type, seq);
-- 按落札行取全部费用（合计与守恒校验）
CREATE INDEX IF NOT EXISTS idx_cost_lot ON auction_lot_costs (lot_id);

COMMENT ON TABLE auction_lot_costs IS
    '落札诸费用明细（W-U2 / R4.5）。取得原价构成（FEE/COMMISSION/TRANSPORT/HANDLING） 与税额口径（CONSUMPTION_TAX + tax_treatment）在此分行； 合计与 auction_lots.total_cost 勾稽。';
COMMENT ON COLUMN auction_lot_costs.tax_treatment IS
    '税额口径 [待定源 D-02]：TAXABLE / NON_TAXABLE / MARGIN_TAX。未签字前允许 NULL， NULL 表示口径未知，不参与含税分摊。费用明细与 total_cost 不一致时报 AUC_002（409，D07 §8）。';

-- ===========================================================================
-- 【规格缺口：入场价校验结论没有落库列】
-- D08 §4.4 要求落札时做入场价闸门（入场价高于审慎值 → 记 OVER_PRUDENT 并阻断后续放款基准），
-- 但 D04 §3.5 的 auction_lots 列清单里没有承载这个结论的列
-- （最接近的 settlement_due_on / status / total_cost 都不是判定结果列）。
-- 本包按"不猜口径"处理：
--   1. 不擅自加列（加一列 entry_check 就等于宣布闸门语义已定，而它没有）；
--   2. 闸门本身实现为可调用策略（AuctionEntryPricePolicy，domain 层），
--      结论进 audit_events（action=AUCTION_ENTRY_CHECK）并随响应返回，
--      不在 auction_lots 上留下"看起来有闸门列"的空列；
--   3. 由此的已知缺陷必须说清：不入库 ⟹ 事后无法用一条 SQL 圈出所有超价落札，
--      只能查审计表；跨场次统计（"这个月 OVER_PRUDENT 占比"）无法在库内聚合。
-- 处置方案（补列 vs 独立判定表）列为待签字，实现说明 §3-10。
-- ===========================================================================
