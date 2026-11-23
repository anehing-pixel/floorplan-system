-- ===========================================================================
-- V65：监管方（custodians）能力扩展 + WMS 入库确认表（W-U4，R6.1 / R6.2）
-- ===========================================================================
--
-- 【规格来源】D04 §3.15（custodians 扩展）+ §3.16（wms_inbound_confirms）；
--   端点与报文校验口径 D07 §7.5 / §3.3；用例 D06 §6.1 UC-R6-1/UC-R6-2。
--
-- 【为什么 custodians 到这一波才动】
-- V19 建表时 Q1「接收方是谁」未定，四类 custodian_type 全部建模但没有一个真实使用方；
-- V24 只补了「按释放项指定接收方」的注释，仍无能力字段。
-- 本轮（USED 域 R6.1）是这张表**第一次有读写方**：
--   读 = 放车放证通道路由（D09 §5.5 按 channel_capability 选 PORTAL/API/MANUAL）
--   写 = 监管方维护端点（D07 §7.5 `/api/custodians`，`CUSTODIAN_MAINTAIN`）
-- 因此本迁移的列不是"预留"，每个新列在下面都写明了消费它的判定分支。
--
-- 【与并行包的依赖处理】
-- D04 §2 标注 V65 依赖 V50（W-U1 的 vehicle_units USED 列）。**本文件不引用 V50 的任何新列**，
-- 因此并行同基线（各自从 base-* 切出、只含本包迁移）时本迁移可独立执行；
-- 合回主干后 V50 先于 V65 也无顺序冲突。跨上下文对象（integration.partners、
-- integration.inbound_messages）一律只做**逻辑引用**，不建外键（D04 §1.1 外键行），
-- 否则 V65 会被 W-U3 的 V70–V74 号段卡住启动顺序。
--
-- 【幂等与存量安全（D04 §12）】
-- 全部 DDL 走 IF NOT EXISTS / 存在性判定；无 DROP / TRUNCATE / DELETE；
-- 新加 CHECK 一律 NOT VALID + VALIDATE 两步，且取值集合**包含存量可能出现的全部值**，
-- 避免重演「CHECK 不含存量值 → 整个迁移回滚」的 V19 事故形状。
--
-- 【本迁移刻意不建的东西】
-- `public.enum_confirmation_authority` 字典表**不在这里建，也不在 V67 建**：
-- D04 §6 步骤 1 要求它，但 §2.1 把 W-U4 的 public 新建表定死为 5 张
-- （V65(1)+V66(3)+V69(1)，`FLOORPLAN_PUBLIC_TABLES` 分支门槛 72 就是按这个数算的）。
-- 多建一张会让 `agent_env.sh check` 的表数判据失真。权威取值改由 D00 §8.3 的
-- Java 枚举 + `release_receipts.chk_receipt_confirmation_authority`（V67）双层约束承担。
-- 该冲突已登记为待收口裁决项（见 docs/AGENT_TASK-W-U4-实现说明.md 的矛盾清单）。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- ① custodians 能力列（D04 §3.15）
-- ---------------------------------------------------------------------------
-- `custodian_code` / `contact_name` / `contact_phone` / `status` / `api_endpoint`
-- / `api_secret_ref` 在 V19 已存在，IF NOT EXISTS 命中即跳过，不重复加列（D04 §1.2 避讳：
-- 同义列产两份 = 取数必然分叉）。
ALTER TABLE custodians
    ADD COLUMN IF NOT EXISTS channel_capability VARCHAR(64),
    ADD COLUMN IF NOT EXISTS service_window     VARCHAR(64),
    ADD COLUMN IF NOT EXISTS reconcile_cycle    VARCHAR(16),
    ADD COLUMN IF NOT EXISTS country            VARCHAR(8),
        -- ★ D04 §3.15 只给列名未给宽度；按 ISO 3166-1 alpha-2 + 余量取 VARCHAR(8)，
        --   与 dealers 现有国家/地区列宽度量级一致，属实现假设，登记待收口确认。
    ADD COLUMN IF NOT EXISTS partner_code       VARCHAR(64);

COMMENT ON COLUMN custodians.channel_capability IS
    '可用通道 CSV，取值集 {API,PORTAL,MQ,MANUAL}（D00 §8.3 ReleaseChannel 口径）。通道路由的唯一输入：'
    '为空 = 该监管方只能人工转交（MANUAL），D09 §5.5 据此降级，不猜默认值';
COMMENT ON COLUMN custodians.service_window IS
    '服务时段，形如 "0900-1700 JST"。巡检与下发前的软校验输入（时段外只排队不即时重发），非硬闸门';
COMMENT ON COLUMN custodians.reconcile_cycle IS
    '对账周期 DAILY / PER_SNAPSHOT / WEEKLY。决定快照比对的 biz_date 取法（D06 §6.3 UC-R6-4）；NULL = 不参与账实对账';
COMMENT ON COLUMN custodians.country IS
    'ISO 3166-1 alpha-2（JP 等）。用于区分在日/离岸监管方，报表与法规适用性统计输入（D10 §7）';
COMMENT ON COLUMN custodians.partner_code IS
    '逻辑引用 integration.partners.partner_code（V71，W-U3 号段）。打通签名链路：'
    'HMAC 验签与出站端点都按本列取密钥引用，不在 custodians 上另存 secret';

-- ---------------------------------------------------------------------------
-- ② 枚举列的 DB 层约束（D00 §8.3 双层约束 ②；D04 §6 通则：必须含存量值）
-- ---------------------------------------------------------------------------
-- custodian_type 现 4 值（V19）→ USED 域 6 值（D00 §8.3 加 WMS / AUCTION_VAULT）。
-- 存量行必然落在前 4 值内，因此 VALIDATE 不会因存量失败。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_custodian_type') THEN
        ALTER TABLE custodians
            ADD CONSTRAINT chk_custodian_type
            CHECK (custodian_type IN ('DEALER_WAREHOUSE', 'CERTIFICATE_DEPOSITORY',
                                      'THIRD_PARTY_WAREHOUSE', 'ON_SITE_SUPERVISOR',
                                      'WMS', 'AUCTION_VAULT')) NOT VALID;
    END IF;
END $$;

-- 先验证再留痕：NOT VALID 只是不被存量阻塞，不等于放弃校验，主干回归时补 VALIDATE。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_custodian_type') THEN
        ALTER TABLE custodians VALIDATE CONSTRAINT chk_custodian_type;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_custodian_reconcile_cycle') THEN
        ALTER TABLE custodians
            ADD CONSTRAINT chk_custodian_reconcile_cycle
            CHECK (reconcile_cycle IS NULL
                   OR reconcile_cycle IN ('DAILY', 'PER_SNAPSHOT', 'WEEKLY')) NOT VALID;
    END IF;
END $$;

-- channel_capability 是 CSV，不用 IN()，用正则限定"每项都必须在四值内"，
-- 否则 'API,PORTAL,' 或 'FTP' 这类脏值会一路流到路由分支被当成 MANUAL。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_custodian_channel_capability') THEN
        ALTER TABLE custodians
            ADD CONSTRAINT chk_custodian_channel_capability
            CHECK (channel_capability IS NULL
                   OR channel_capability ~ '^(API|PORTAL|MQ|MANUAL)(,(API|PORTAL|MQ|MANUAL))*$') NOT VALID;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- ③ COMMENT ON 覆盖旧口径（D04 §7 第 1 行；只加限定语，不删原内容，§12 第 8 条）
-- ---------------------------------------------------------------------------
COMMENT ON COLUMN custodians.custodian_type IS
    '接收方类型。V24 原裁决保留：THIRD_PARTY_WAREHOUSE / ON_SITE_SUPERVISOR 在 '
    'vehicle_type=''NEW_STOCK'' 域默认不可选（须业务书面启用）；'
    '在 vehicle_type=''USED'' 域为**主用**类型，另增 WMS / AUCTION_VAULT 两值。'
    '见 ADR-USED-FLOORPLAN-001 反转 #2；判定在代码侧按产品域分叉（CustodianType#isPrimaryUsed），'
    '不改 LEGACY 判定（D09 §6）';

COMMENT ON TABLE custodians IS
    '监管方 / 接收方配置。V19 建表时 Q1 未定、四类全建模且无使用方；'
    'USED 域 R6.1 起本表首次有读写方（通道能力、对账周期、partner 绑定）';

-- ---------------------------------------------------------------------------
-- ④ wms_inbound_confirms：WMS 入库确认（D04 §3.16，R6.2 / I-04）
-- ---------------------------------------------------------------------------
-- 【为什么入库事实要单独成表，而不是只更新 vehicle_units】
-- 入库确认是**外部机构给的事实**（D04 §3.16），必须能回答"哪家仓、哪个入库号、
-- 什么时间、谁推的、原文在哪"。vehicle_units 只有"当前库位"一列，
-- 覆盖写就把事实洗掉了；退库再入库也需要历史行做账实比对的分母。
--
-- 【幂等键】UNIQUE (warehouse_custodian_code, vin, inbound_no)
-- = D07 §3.3 与 D00 §10.2 的 `INB:{wh}:{vin}:{inbound_no}`。
-- 三列缺一不可：只按 vin 判重会让同一台车的第二次真实入库被吞（D02 R-3 同形状）。
CREATE TABLE IF NOT EXISTS wms_inbound_confirms (
    id                        BIGSERIAL PRIMARY KEY,
    inbound_no                VARCHAR(64)  NOT NULL,      -- 仓侧入库单号
    warehouse_custodian_code  VARCHAR(32)  NOT NULL,      -- 逻辑引用 custodians.custodian_code
    vin                       VARCHAR(32)  NOT NULL,
    bin_location              VARCHAR(32),                -- 库位（覆盖 vehicle_units.bin_location 的来源事实）
    odometer_km               INTEGER,                    -- 入库里程（与 auction_lots.mileage_km 勾稽，D04 §5.1）
    condition_summary         VARCHAR(500),
    photo_paths               TEXT,                       -- 本地相对路径 JSON 数组（≥4 方位照，D07 §3.3）
    inspection_certificate_no VARCHAR(64),                -- 不符只报 CERT_003，不阻断入库（入库与质押是两条线）
    sealed_flag               BOOLEAN,                    -- 封签 [待定源 D-09]：允许 NULL = 未采集
    key_handed_over           BOOLEAN,                    -- 钥匙交付 [待定源 D-09]：同上
    confirmed_at              TIMESTAMPTZ  NOT NULL,      -- 仓侧确认时间（起息锚点，不得晚于 now()+5min）
    partner_code              VARCHAR(64)  NOT NULL,      -- 推送方（事实的 actor_type=PARTNER 主体）
    source_message_id         BIGINT       NOT NULL,      -- 逻辑引用 integration.inbound_messages.id（V72）
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_wms_inbound UNIQUE (warehouse_custodian_code, vin, inbound_no),
    CONSTRAINT chk_wms_inbound_odometer CHECK (odometer_km IS NULL OR odometer_km >= 0)
);

CREATE INDEX IF NOT EXISTS idx_wms_inbound_vin    ON wms_inbound_confirms (vin, confirmed_at DESC);
CREATE INDEX IF NOT EXISTS idx_wms_inbound_wh     ON wms_inbound_confirms (warehouse_custodian_code, confirmed_at DESC);
CREATE INDEX IF NOT EXISTS idx_wms_inbound_msg    ON wms_inbound_confirms (source_message_id);

COMMENT ON TABLE  wms_inbound_confirms IS
    'WMS 入库确认事实（I-04）。只追加；vehicle_units 的库位/里程是它的投影，不是相反';
COMMENT ON COLUMN wms_inbound_confirms.source_message_id IS
    '产生本行的入站报文（逻辑引用 integration.inbound_messages.id，V72 = W-U3 号段）。'
    '不建外键：跨 schema 且号段在别的包；NOT NULL 是"外部事实必须有原文可回溯"的硬约束（D02 INV-5）';
COMMENT ON COLUMN wms_inbound_confirms.confirmed_at IS
    '仓侧确认时间 = IN_STOCK 起息前置（D06 §6.2）。晚于 now()+5min 在应用层拒（WMS_001），'
    '本列不加 CHECK——CHECK 里不能用 now() 这种非不可变函数，会把存量行变成随时间漂移的判定';
COMMENT ON COLUMN wms_inbound_confirms.inspection_certificate_no IS
    '入库时申报的車検証号。与 inspection_certificates.certificate_no（V52）不符 → 建 CERT_003 差异并出工单，'
    '**不阻断入库**（D07 §3.3：入库与质押是两条线）';

-- 对账周期与通道的可用性检查索引：巡检按 reconcile_cycle 捞仓，按 partner_code 做数据归属隔离（D10 §5）
CREATE INDEX IF NOT EXISTS idx_custodian_partner ON custodians (partner_code)
    WHERE partner_code IS NOT NULL;
