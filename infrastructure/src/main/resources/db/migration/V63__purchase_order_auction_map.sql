-- ===========================================================================
-- V63：采购单与落札的映射（W-U2 / R4.3）
-- ===========================================================================
--
-- 【规格源】D04 §5.2 的 purchase_orders 行：
--   auction_lot_id BIGINT / lot_no VARCHAR(32) / bid_no VARCHAR(32)
--   / order_no_semantics VARCHAR(16){ISSUED_BY_OEM, AUCTION_TICKET}
--   —— 原文括号里点名「把语义变更显式化，避免"同列两义"」，这就是本迁移的主题。
--
-- 【问题出在 order_no 这一列】
-- V7:178 把 order_no 定成 `VARCHAR(64) UNIQUE NOT NULL`，注释与 V7:12 的设计意图都是
-- 「上游给的那张单的号」，用来防"同一张单重复导入"。LEGACY 的上游是 OEM／批发商，
-- 这个号是**发注番号**。USED 的拍卖渠道里，同一列装的是**拍卖票据号**：
--   - 来源不同（拍场系统 vs 厂商 EDI）；
--   - 唯一性含义不同（票据号在场次内唯一，跨场次可能重复）；
--   - 幂等含义不同（票据号重复导入是同一台车的重复建账，发注号重复是整张单重复）。
-- 两义共用一列 = 「同一张表里同样的数据看起来是两件事」，
-- 这正是 AGENTS.md §8 禁止的状态，也是本迁移要显式化的东西。
--
-- 【为什么不直接改成两个列（auction_ticket_no + oem_order_no）】
-- 那要改既有列语义（V7 的 order_no 不动），且所有既有取数与冒烟都读 order_no，
-- 拆列会波及 LEGACY 与别的包，超出本包范围。
-- D04 §5.2 选的做法是保留单列 + 加一列语义标签，本迁移照此实现。
--
-- 【为什么不建反向的 auction_lots.purchase_order_id 之外的关联表】
-- auction_lots 已经有 purchase_order_id（V61），两边都能查。
-- 建中间表会引入"两处都要维护"的一对多，而落札与采购单在本域就是一对一
-- （一台车一张采购单），多余。
-- ===========================================================================

ALTER TABLE purchase_orders ADD COLUMN IF NOT EXISTS auction_lot_id BIGINT;
ALTER TABLE purchase_orders ADD COLUMN IF NOT EXISTS lot_no VARCHAR(32);
ALTER TABLE purchase_orders ADD COLUMN IF NOT EXISTS bid_no VARCHAR(32);
ALTER TABLE purchase_orders ADD COLUMN IF NOT EXISTS order_no_semantics VARCHAR(16);

COMMENT ON COLUMN purchase_orders.auction_lot_id IS
    '逻辑引用 auction_lots.id。不建 FK 的两个理由： ① 目标架构里落札归 core、采购单也归 core 但拍卖入站链路（I-01/I-02）经 W-U3 的 integration schema， 入站重放时两侧写入顺序不保证（先 PO 后 lot 的路径真实存在），FK 会把这种合法顺序判成错误； ② D04 §1 约定跨上下文用逻辑引用，与现状 release_orders.redemption_id 一致。 两侧一致性由 R4.3 的映射校验（lot_key 与 order_no 勾稽）在应用层保证。';
COMMENT ON COLUMN purchase_orders.lot_no IS '出品番号（拍卖票原样）。与 auction_lot_id 冗余存，为了按票面号对账时不必回查落札表。';
COMMENT ON COLUMN purchase_orders.bid_no IS '落札番号。对拍场争议时只有这个号被承认，故必须留在采购单上。';
COMMENT ON COLUMN purchase_orders.order_no_semantics IS
    'order_no 的语义标签：ISSUED_BY_OEM = 厂商/批发发注番号（LEGACY 口径）； AUCTION_TICKET = 拍卖票据号（USED 口径）。 ★ 本列不填 = 语义未声明。新写入必须显式声明：建账与入站两条路径都写它， 判据在应用层（PurchaseOrderSemantics），历史行留 NULL 不回填 —— 回填就是伪造来源 （同一手法见 D04 §5.1 对 purchase_channel 的处理：全 OEM 属既有事实，不回填）。';

-- 部分索引：只收 USED 口径的采购单（结算与拍场对账按这条路径扫，不掺 LEGACY 行）
CREATE INDEX IF NOT EXISTS idx_po_auction ON purchase_orders (auction_lot_id, order_no)
    WHERE order_no_semantics = 'AUCTION_TICKET';
-- 落札番号查采购单（争议处理时拍场只给 bid_no，必须先定位到 PO）
CREATE INDEX IF NOT EXISTS idx_po_bid_no ON purchase_orders (bid_no) WHERE bid_no IS NOT NULL;

-- ===========================================================================
-- 【COMMENT ON 覆盖旧口径：本迁移只覆盖 purchase_orders 自己那一条】
-- D04 §7 的覆盖清单里与 V63 相关的只有"order_no 同列两义"这一处；
-- 清单其余各行（custodians.custodian_type、release_orders.certificate_no、
-- release_receipts.confirmation_authority、vehicle_units.invoice_amount、
-- vehicle_units.residual_value_estimated、suppliers.guarantee_ability、
-- repurchase_requests、financing_lien_checks.check_source）
-- 全部落在 V46/V47/V50（W-U1）与 V65–V77（W-U4/W-U5）的号段内，
-- 不由本包代做 —— 代做会与其他包在合并时产生同列注释的双写冲突。
-- ===========================================================================
COMMENT ON COLUMN purchase_orders.order_no IS
    '上游单据号，UNIQUE NOT NULL（V7:178 原约束不变）。 ★ USED 改造后本列语义随 order_no_semantics 分叉： ISSUED_BY_OEM = 发注番号；AUCTION_TICKET = 拍卖票据号。 任何按 order_no 判唯一的逻辑都必须先看语义标签，跨语义比对（拿票据号当发注号去重）是缺陷。';
