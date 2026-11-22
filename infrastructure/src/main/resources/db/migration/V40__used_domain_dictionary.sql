-- =====================================================================
-- V40__used_domain_dictionary.sql
-- 用途：USED 域字典基座——分流键语义 + vehicle_type / purchase_channel 的 CHECK 扩展。
-- 归属包：W-U1。新建 public 表数 = 0（D04 §2.1 未把本文件计为建表文件；
--   §2.1 的「V40 若落枚举字典表则 +1」条件项本轮**不触发**，理由见 §3）。
-- 定源：D00 §1.2 P-2（分流键）+ §8.1（枚举字典）+ D04 §2 / §5.1 / §6 + D13 §3 G11。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 分流键：为什么是 vehicle_type，不是 product_line
-- ---------------------------------------------------------------------
-- D13 §3 G11 实测结论：USED/LEGACY 的分流键是 `vehicle_units.vehicle_type`；
-- `product_line` 只是 bank-service 内部枚举（ReleaseStatus 同款"同名不同义"坑），
-- **数据库里从来没有 product_line 列**（本轮全量 grep 迁移目录，零命中）。
-- 因此本迁移不新建 product_line 列——造它就等于凭空加第二把分流键，
-- 两把键迟早分叉（D00 §1.2 P-2 明令分流键只有一把）。
COMMENT ON COLUMN vehicle_units.vehicle_type IS
    '业务线分流键（D00 §1.2 P-2 定源）：NEW_STOCK=新车渠道（LEGACY，存量与在途需求全部走原方法，行为零改动）；'
    || 'USED=二手车库存垫资。USED 用例的第一道分支必须是本列=''USED''，LEGACY 分支不得被改动或反转。'
    || '分流键只有本列这一把：release-service 的 product_line 是服务内部分流枚举，**不是**库内列，也不得用来判定 USED/LEGACY（D13 §3 G11）。';

-- ---------------------------------------------------------------------
-- 2. CHECK 扩展（D04 §2 把这两条明确分给 V40）
-- ---------------------------------------------------------------------
-- 现状实测：V1:40 vehicle_type VARCHAR(32) **无 CHECK**、V1:42 purchase_channel VARCHAR(16) **无 CHECK**，
-- 所以这里是"新增约束"而不是"放宽约束"。
-- 两条都必须允许 NULL：ADR007 实查现状 vehicle_type 全 NULL、purchase_channel 全 OEM；
-- 不含 NULL 的 CHECK 会让存量行违反约束、Flyway 回滚整个迁移（AGENTS §12 反模式第 8 条）。
-- "新写入必填"不靠 DDL（DDL 无法只对新增行加 NOT NULL），靠三层：
--   CHECK 允许 NULL + 应用层入参校验（VALID_001）+ 冒烟断言 400（D04 §5.1 的取舍，需评审特别确认）。
ALTER TABLE vehicle_units
    DROP CONSTRAINT IF EXISTS ck_vu_vehicle_type;

ALTER TABLE vehicle_units
    ADD CONSTRAINT ck_vu_vehicle_type CHECK (vehicle_type IS NULL
        OR vehicle_type IN ('NEW_STOCK', 'USED'));

ALTER TABLE vehicle_units
    DROP CONSTRAINT IF EXISTS ck_vu_purchase_channel;

ALTER TABLE vehicle_units
    ADD CONSTRAINT ck_vu_purchase_channel CHECK (purchase_channel IS NULL
        OR purchase_channel IN ('OEM', 'AUCTION', 'WHOLESALE', 'TRADE_IN', 'OTHER'));

COMMENT ON COLUMN vehicle_units.purchase_channel IS
    '采购渠道（D00 §8.1 PurchaseChannel 五值）：OEM / AUCTION / WHOLESALE / TRADE_IN / OTHER。'
    || 'USED 域导入层做**强枚举校验**，OTHER 必须带备注（R2.1 验收点）；'
    || '本列同时是 LEGACY 放行校验 supplierGuaranteeAbility 的路由依据（V22 口径），二者含义一致。';

-- ---------------------------------------------------------------------
-- 3. 枚举字典表：本轮**不建**（条件项，非遗漏）
-- ---------------------------------------------------------------------
-- D04 §2 的 V40 行把字典表写作「可选」，§2.1 又把「V40 若落枚举字典表」列为 +1 条件项；
-- D04 §11（参数落 DB 还是落 yml）本身挂 [待定源 D-06]，字典表要不要建是同一个选型问题。
-- 本轮 R13.4「枚举双层约束」用 **Java 枚举 + 列 CHECK** 达成（见 V45–V54 各 CHECK），
-- 不建字典表，理由两条：
--   ① 建表选型未签 → 建了就是替业务决定"配置载体"，且会抬高本包表数门槛（73→74/75）造成 check 误报；
--   ② 字典表若无人读写，正是 AGENTS §12「表建了但自检体系不知道」的反面样例。
-- 若后续裁决改为字典表承载，需新迁移 + 同步 D04 §2.1 与 D12 §3 卡面门槛，本包不自决。
