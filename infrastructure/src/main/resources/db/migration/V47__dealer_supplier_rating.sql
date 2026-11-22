-- =====================================================================
-- V47__dealer_supplier_rating.sql
-- 用途：R1.4 上游（拍卖场 / 批发商）准入与评级的落库前提——suppliers 扩展列。
-- 归属包：W-U1。定源：D04 §5.2（suppliers 行）+ D06 §1.3（上游闸门）+ D04 §7（COMMENT ON 覆盖清单）。
-- 本文件新建 public 表数 = 0（D04 §2.1 口径一致），只做加列 + 约束 + 注释覆盖。
-- 关键取舍：USED 域「评级真正影响放款判定」不落在 LEGACY 路径上
--   （D06 §1.3 明文：不改 PurchaseOrderApplicationService，LEGACY 行为与断言零改动），
--   本迁移只提供"评级是否进闸门"的开关列，实际降档在 USED 放款闸门 G8（W-U2 实现）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. suppliers 扩展列（D04 §5.2 逐列照抄）
-- ---------------------------------------------------------------------
ALTER TABLE suppliers ADD COLUMN IF NOT EXISTS auction_house_code         VARCHAR(32);
ALTER TABLE suppliers ADD COLUMN IF NOT EXISTS member_no                  VARCHAR(64);
ALTER TABLE suppliers ADD COLUMN IF NOT EXISTS settlement_account_id      BIGINT;
ALTER TABLE suppliers ADD COLUMN IF NOT EXISTS supplier_grade             VARCHAR(16);
ALTER TABLE suppliers ADD COLUMN IF NOT EXISTS admission_status           VARCHAR(16);
ALTER TABLE suppliers ADD COLUMN IF NOT EXISTS guarantee_ability_effective BOOLEAN DEFAULT FALSE;

-- 准入态取值集与 D04 §3.4 auction_houses.admission_status 同源（同一个业务概念只一套值）。
-- 允许 NULL：存量 suppliers 从未做过准入判定，NULL=未评估，闸门按 fail-closed 拒。
ALTER TABLE suppliers
    DROP CONSTRAINT IF EXISTS ck_suppliers_admission_status;

ALTER TABLE suppliers
    ADD CONSTRAINT ck_suppliers_admission_status CHECK (admission_status IS NULL
        OR admission_status IN ('ADMITTED', 'SUSPENDED', 'BLOCKED'));

COMMENT ON COLUMN suppliers.auction_house_code IS '该上游对应的拍卖场代码（逻辑引用 auction_houses，V60 / W-U4，本包不建物理 FK）；
    与 dealer_auction_memberships.auction_house_code 同一取值域，两侧口径必须一起定源。';
COMMENT ON COLUMN suppliers.member_no IS '上游在拍卖场的出品店会员号（落札数据里的 seller_member_no 勾稽用，D06 §2.3）。本列是事实记录，不参与准入判定。';
COMMENT ON COLUMN suppliers.settlement_account_id IS '结算账户（逻辑引用 supplier_bank_accounts.id）。不建 FK 的原因：对存量表加 FK 会触发全表校验并长期限制账户行的删除重建，
    而账户生命周期由 V7 既有流程管理；引用完整性交给对账作业。';
COMMENT ON COLUMN suppliers.supplier_grade IS '上游评级代码（R1.4）。取值集与评级→闸门映射表 [待定源 D-04] 未签，
    故本列不建 CHECK、不在本包写任何"某等级对应降多少档"的推导（那是 D08 §4.1 dealer_grade_in 的口径）。';
COMMENT ON COLUMN suppliers.admission_status IS 'R1.4 准入态 ADMITTED/SUSPENDED/BLOCKED（D06 §1.3 上游闸门第一道）。
    写入口 = SupplierAdmissionApplicationService（web(core)，权限点 SUPPLIER_MAINTAIN），状态经领域方法流转，禁止直接 UPDATE 本列。';
COMMENT ON COLUMN suppliers.guarantee_ability_effective IS 'USED 域"评级是否进入放款闸门"的开关，默认 FALSE=不影响放款（与 LEGACY 现状等价，不改变任何既有行为）。
    置为 TRUE 需业务签署 D-04 后由 UC-R1-5 写入，生效路径是 USED 闸门 G8 调 UpstreamGradePolicy.apply(...)（W-U2），
    **不是**改 PurchaseOrderApplicationService 的 LEGACY 判定（AGENTS §8 红线，D06 §1.3 明文）。';

-- ---------------------------------------------------------------------
-- 2. COMMENT ON 覆盖旧口径（D04 §7 第 6 行；不改已执行的 V7，只加限定语）
-- ---------------------------------------------------------------------
-- D04 §12.8：COMMENT ON 只用于加限定语，原注释内容必须保留（审计轨迹），故下面显式复述 V7 原文再追加。
COMMENT ON COLUMN suppliers.guarantee_ability IS
    -- 下面首句是 V7:47 原文逐字保留（D04 §12.8），其后才是本包追加的 USED 限定语
    '回购/担保能力：STRONG强 / LIMITED有限 / NONE无——不可按上游类型一刀切。USED 域限定（D04 §7，ADR-USED-FLOORPLAN-001）：USED 域不适用厂商回购标尺，
    本列在 USED 域仅用于拍卖场上游评级并进入闸门（R1.4）；是否真正影响放款由
    guarantee_ability_effective 开关决定，LEGACY 域原口径与既有断言不变。';
