-- =====================================================================
-- V50__vehicle_used_attributes.sql
-- 用途：R2.1–R2.4 USED 车辆主数据属性 + R3.1 金额基准字段定义（D04 §5.1 全清单）。
-- 归属包：W-U1。新建 public 表数 = 0（只做 vehicle_units 加列 + 约束 + 注释 + 回填）。
--
-- 【本文件的两条硬约束】
-- 1) L4 LEGACY 建账回归不破：既有新车建账的默认行为一条都不改。
--    所有新列可空、CHECK 一律允许 NULL（存量行不得违反新约束，D04 §5.1 + §12.3），
--    且**不加 NOT NULL**——"新写入必填"靠三层保证：CHECK 允许 NULL + 应用层必填 + 冒烟断言 400。
-- 2) 里程的唯一落点是既有列 vehicle_units.mileage（V1:38 INT）。
--    本文件**不建也不引用** vehicle_units.mileage_km（D04 §5.1：该名字只属于 auction_lots 与报文字段）。
--
-- 撤销项照抄：§5.1 原稿的 vehicle_units.authoritative_source_type 已由 D13 §3 G13 撤销，
-- 本文件不建该列（权属权威源唯一 = collateral_registration_batches，ADR-Q27；门禁只读批次表）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. R2.1 / R2.3 车辆身份与车况属性
-- ---------------------------------------------------------------------
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS registration_plate          VARCHAR(16);
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS first_registered_on         DATE;
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS inspection_expiry_date      DATE;
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS inspection_remaining_months INT;
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS mileage_source              VARCHAR(24);
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS mileage_unit                VARCHAR(8) DEFAULT 'KM';
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS mileage_captured_on         DATE;
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS repair_history              VARCHAR(16);
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS accident_history_flag       BOOLEAN;
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS auction_grade               NUMERIC(3,1);
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS repair_history_desc         VARCHAR(500);
-- 来源引用（逻辑引用，见表末注释：跨包号段不建物理 FK）
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS auction_lot_id              BIGINT;
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS inspection_certificate_id   BIGINT;
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS warehouse_custodian_code    VARCHAR(32);
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS bin_location                VARCHAR(32);
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS sale_channel                VARCHAR(24);
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS wms_confirmed_at            TIMESTAMPTZ;

-- ---------------------------------------------------------------------
-- 2. R3.1 金额基准字段**定义**（只建列，不写任何估值/LTV 算法）
-- ---------------------------------------------------------------------
-- 算法归 W-U2 的 R3（vehicle_valuations V60 + ltv_policies V64，均不在本包号段）。
-- 本包负责把"金额基准从 invoice_amount 切到审慎值"这一事实用列表达出来，
-- 使 W-U2 接线时不再回头改主数据表（并行开发的接口约定）。
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS valuation_id           BIGINT;
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS prudent_value          BIGINT;
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS ltv_ratio_snapshot     NUMERIC(5,4);
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS ltv_policy_code        VARCHAR(32);
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS ltv_policy_version     INT;
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS topup_required_amount  BIGINT DEFAULT 0;

-- ---------------------------------------------------------------------
-- 3. 枚举双层约束（R13.4）：全部允许 NULL，兼容存量行
-- ---------------------------------------------------------------------
-- ⚠ `vehicle_type` / `purchase_channel` 的 CHECK **不在本文件写**：
--   D04 §2 批次计划把「`vehicle_type` 与 `purchase_channel` 的 CHECK 扩展」明确分给 V40。
--   两处各写一遍会形成同一约束两个定义点，取值集调整时只会改到一半——按号段归属放，
--   本文件只补 V50 自己新增列的 CHECK。
--
-- mileage_source 取值集 = D00 §8.1 `MileageSource` 定源（5 值，逐字照抄）。
-- ⚠ D06 §2.4 的优先级链里写了 WMS_SNAPSHOT / AUCTION_TICKET / REGISTRY / MANUAL 四个名字，
--   它们**不在 D00 §8.1 定源集合内**（同名概念的定源写法是 WMS_INBOUND / AUCTION_REPORT / DEALER_DECLARED）——
--   已作为文档冲突上报，本 CHECK 以 D00 §8.1 为准，不自造新枚举值。
ALTER TABLE vehicle_units
    DROP CONSTRAINT IF EXISTS ck_vu_mileage_source;

ALTER TABLE vehicle_units
    ADD CONSTRAINT ck_vu_mileage_source CHECK (mileage_source IS NULL
        OR mileage_source IN ('AUCTION_REPORT', 'WMS_INBOUND', 'OWN_AUDIT', 'DEALER_DECLARED', 'UNKNOWN'));

ALTER TABLE vehicle_units
    DROP CONSTRAINT IF EXISTS ck_vu_repair_history;

ALTER TABLE vehicle_units
    ADD CONSTRAINT ck_vu_repair_history CHECK (repair_history IS NULL
        OR repair_history IN ('NONE', 'MINOR', 'MAJOR', 'UNKNOWN'));

-- sale_channel 取值集 = D00 §8.1 `SaleChannel` 定源（3 值）。
ALTER TABLE vehicle_units
    DROP CONSTRAINT IF EXISTS ck_vu_sale_channel;

ALTER TABLE vehicle_units
    ADD CONSTRAINT ck_vu_sale_channel CHECK (sale_channel IS NULL
        OR sale_channel IN ('RETAIL', 'WHOLESALE_RESELL', 'AUCTION_RELIST'));

-- 里程 UNKNOWN 的表达 = mileage_source='UNKNOWN' 且 mileage IS NULL（D04 §5.1）。
-- 目的：堵死"来源说不清但先填个数"这种把估值建立在可疑里程上的写法。
-- IS DISTINCT FROM 而不是 <>：mileage_source 为 NULL（LEGACY 存量）时本条必须为真。
ALTER TABLE vehicle_units
    DROP CONSTRAINT IF EXISTS ck_vu_mileage_unknown;

ALTER TABLE vehicle_units
    ADD CONSTRAINT ck_vu_mileage_unknown CHECK (mileage_source IS DISTINCT FROM 'UNKNOWN'
        OR mileage IS NULL);

-- ---------------------------------------------------------------------
-- 4. 注释覆盖与新增说明（D04 §7 中与 vehicle_units 相关的两行）
-- ---------------------------------------------------------------------
COMMENT ON COLUMN vehicle_units.invoice_amount IS
    '发票金额（JPY 整数最小单位）。'                                                            -- V1/V3 原语义保留
    || 'USED 域限定（D04 §7）：USED 域**不得**作为放款基准，基准见 prudent_value / valuation_id。'
    || 'LEGACY（vehicle_type=NEW_STOCK）域原口径不变。';

COMMENT ON COLUMN vehicle_units.residual_value_estimated IS
    '预估残值，仅用于预警，不作处置定价依据。'                                                    -- 原注释要点保留
    || 'USED 域覆盖（D04 §7）：USED 域可作为处置定价依据（R3.5）；LEGACY 域原口径不变。';

COMMENT ON COLUMN vehicle_units.mileage IS
    '主数据里程，**唯一权威**（V1:38 既有列，本迁移不加新列）。与 auction_lots.mileage_km（票面）、'
    || 'vehicle_condition_reports.odometer_km / release_receipts.odometer_km（实车快照）是三个不同事实，'
    || 'R2.3 勾稽=拿后两者与本列比，差异一律建工单。'
    || '是否覆盖本列按 D06 §2.4 的来源优先级判：低优先来源（票面／申报）与主数据不一致时'
    ||'**只建工单不覆盖**（D04 §5.1 的"按主数据不覆盖"讲的是这一类）；'
    || '高优先实物观测（WMS_INBOUND／OWN_AUDIT）胜出时采纳其值并同步改 mileage_source'
    || '（D06 §2.3 的"里程来源改 WMS_INBOUND"讲的是这一类）。'
    || '两条文档字面互斥，此为本包采纳的合并口径（已登记待收口方确认）；无论走哪条都写审计。';
COMMENT ON COLUMN vehicle_units.mileage_source IS '里程来源（MileageSource，D00 §8）。NULL 与 UNKNOWN 是两种事实：'
    || 'NULL=从未采集（LEGACY 存量），UNKNOWN=采集过但不可信（此时 mileage 必须为 NULL）。'
    || '两者在 D08 §4.1 取带时行为一致（都按最保守档），但不得在数据层合并成一类。';
COMMENT ON COLUMN vehicle_units.mileage_unit IS '里程单位，本期恒 KM（报文字段亦叫 mileage_km，不做换算）';
-- 車検残存（需求书 R2.2）：本列是**录入值**，不由车検満了日推导。
-- 推导需要两个口径：起算日（今天／建账日／入库日）与取整方向（满月截断还是向上取整），
-- 两者都没有定源（D04 §5.1 只给列名与类型）。若在应用层写死一个算法，
-- 就等于替业务定了"残存 1 个月"到底是 29 天还是 31 天，且换证实时时会静默改判。
-- 因此本列的写入口只做"照抄上游给的月数"，缺值落 NULL（＝未采集），不反推。
COMMENT ON COLUMN vehicle_units.inspection_remaining_months IS
    '車検残存月数（录入值，来自車検証／落札票面）。'
    || '**不由 inspection_expiry_date 推导**：起算日与取整方向均未定源（见左注）。NULL＝未采集。';
COMMENT ON COLUMN vehicle_units.mileage_captured_on IS '采集时点（同优先级取最新者，D06 §2.4）';
COMMENT ON COLUMN vehicle_units.repair_history IS '修復歴枚举 NONE/MINOR/MAJOR/UNKNOWN；取严规则 MAJOR 优先、UNKNOWN 不优于已知（D03 §2.3）';
COMMENT ON COLUMN vehicle_units.repair_history_desc IS '修復歴摘要文本（R2.1「摘要文本」）；只是说明，判定读 repair_history';
COMMENT ON COLUMN vehicle_units.auction_grade IS '拍卖场評価点（NUMERIC(3,1)，4.5 之类）。本列是事实记录，不含任何分档推导';
COMMENT ON COLUMN vehicle_units.valuation_id IS 'USED 域金额基准来源记录（逻辑引用 vehicle_valuations，V60 / W-U2）。'
    || '取代 invoice_amount 推导放款基准——但**取数算法不在本包**，本包只建列并保证列语义唯一。';
COMMENT ON COLUMN vehicle_units.prudent_value IS '审慎值（放款时点快照，不随后续重估变化，JPY 整数）';
COMMENT ON COLUMN vehicle_units.ltv_ratio_snapshot IS '⚠ 展示投影，非权威（D04 §5.1）：advances.ltv_ratio_snapshot（V58）才是判定/对账/D11 断言的读取源，'
    || '因为 ADR-010 允许同 VIN 多笔垫款、车辆侧这一份会被第二笔覆盖。本列只用于"最近一笔"的列表展示，**不得进任何谓词**。';
COMMENT ON COLUMN vehicle_units.ltv_policy_code IS '当时命中的 LTV 政策代码（可复现"当时用哪版政策"）';
COMMENT ON COLUMN vehicle_units.inspection_certificate_id IS '一车一证指向的当前有効車検証（inspection_certificates.id，V52）。'
    || '物理 FK 在 V53 加（V50 先跑、V52 后跑，建表顺序决定了本文件不能引用）';
COMMENT ON COLUMN vehicle_units.warehouse_custodian_code IS '实控位置：监管仓代码（逻辑引用 custodians，V65 / W-U4）';
COMMENT ON COLUMN vehicle_units.wms_confirmed_at IS 'WMS 入库确认时间（IN_STOCK 前置证据，D02 环节⑥；写入口 R11 入站，属 W-U6）';
COMMENT ON COLUMN vehicle_units.topup_required_amount IS 'LTV 突破时的追加还款额（JPY 整数，由 W-U2 的 LTV 计算写入）';

-- ---------------------------------------------------------------------
-- 5. 回填（D04 §10 第 1 行；迁移中唯一允许的 UPDATE 类操作）
-- ---------------------------------------------------------------------
-- vehicle_type 现状全 NULL（ADR007 实查）→ 回填 NEW_STOCK。
-- **只回填，不改变任何业务行为**：LEGACY 分支逻辑与既有断言零改动，
-- 回填只是把"事实上是新车"的行标成显式 NEW_STOCK，让 USED 分支的 vehicle_type='USED'
-- 判定（D00 §1.2 P-2）不必再写 vehicle_type IS OR 'NEW_STOCK' 的双条件。
-- 不动 version / updated_at：回填不是业务变更，抬高 version 会让在途乐观锁误判为并发冲突。
WITH backfilled AS (
    UPDATE vehicle_units
       SET vehicle_type = 'NEW_STOCK'
     WHERE vehicle_type IS NULL
    RETURNING vin
),
counts AS (
    SELECT count(*) AS n FROM backfilled
)
INSERT INTO audit_events (actor, actor_role, action, target_type, target_id,
                          before_snapshot, after_snapshot, trace_id)
SELECT 'system:migration', NULL, 'DATA_BACKFILL', 'VEHICLE_UNIT', 'V50:vehicle_type',
       'vehicle_type IS NULL',
       'backfilled to NEW_STOCK, rows=' || n,
       'migration:V50'
  FROM counts
 WHERE n > 0;

-- purchase_channel 全 OEM 属既有事实，**不回填**（D04 §10 第 2 行：避免伪造来源）。
COMMENT ON COLUMN vehicle_units.vehicle_type IS
    'NEW_STOCK=新车渠道（LEGACY，ADR-USED-FLOORPLAN-001 改名后不再用 NEW），USED=二手车。'
    || '所有 USED 用例的第一道分支就是本列=''USED''（D00 §1.2 P-2），LEGACY 走原方法不改。'
    || 'V50 已把存量 NULL 回填为 NEW_STOCK；新写入由应用层必填（CHECK 允许 NULL 是为兼容存量）。';
