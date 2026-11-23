-- ===========================================================================
-- V68：释放项按项接收方 + 回执 USED 证据列（W-U4，R7.2 / R7.4 / R7.7）
-- ===========================================================================
--
-- 【规格来源】D04 §5.2（release_order_items / release_receipts 两行）、§10（exception_type 回填）；
--   D03 §2.7 不变量 I1/I2 + §3.2 ItemStatus 取值；D09 §4 接收方解析四条优先级、
--   §7.3 回执最小字段集、§5.3 部分释放聚合「取最不利」；D07 §3.5（I-07 回执）、§7.6。
--
-- 【为什么接收方落在"项"上而不是主表】
-- 放车线与放证线分属不同机构（车在监管仓 / WMS，証書在保管方），
-- 一张指令同时放车放证是常态；主表只有一个 custodian_id 就表达不了
-- "车已从 WMS 放出、証書还在保管方手里"这种**真实的法律状态差异**（D09 §4）。
-- 主表列保留为默认值，项列覆盖；两者都为空 = 解析失败，直接 RELS_006，
-- **没有"随便找个监管方法放出去"的默认值**（D09 §4 四条优先级的第 4 条）。
--
-- 【ItemStatus 的一处文档内冲突（待收口裁决，已在实现说明登记）】
-- D03:321 定 `ItemStatus ∈ {PENDING, INSTRUCTED, RELEASED, NOT_RELEASED, REFUSED}`（5 值），
-- 但 D09:232（S7 对方受理）要求写 `release_order_items.item_status='ACKNOWLEDGED'`——
-- 该值不在 D03 集合内。本 CHECK 按**并集**落（6 值），否则 S7 的写入会被库层拒绝，
-- 表现为"回执进来了但项状态推不动"。收窄到 5 值需先改 D09，属文档裁决不属代码裁决。
--
-- 【与 V67 的号段兼容】
-- `release_receipts.inbound_message_id` 的**约束**（三值 CHECK、PARTNER_RECEIPT 必须有引用、
-- 一条报文只授权一张回执）按 D04 §6 落在 V67，因此列也在 V67 建。
-- D04 §5.2 又把该列的归属写在 V68 行里 ⇒ 这里保留同一条 ADD COLUMN IF NOT EXISTS
-- 以兼容两种读法：先跑 V67 则本句空转，单独跑本文件也不会缺列。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- ① release_order_items：按项接收方与项态（D04 §5.2）
-- ---------------------------------------------------------------------------
ALTER TABLE release_order_items
    ADD COLUMN IF NOT EXISTS receiver_custodian_code VARCHAR(32),
    ADD COLUMN IF NOT EXISTS receiver_partner_code   VARCHAR(64),
    ADD COLUMN IF NOT EXISTS item_status             VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS receipt_id              BIGINT,
    ADD COLUMN IF NOT EXISTS refuse_reason           VARCHAR(500);

COMMENT ON COLUMN release_order_items.receiver_custodian_code IS
    '本项实际接收方（逻辑引用 custodians.custodian_code）。解析优先级（D09 §4）：'
    '① 项显式指定 → ② inspection_certificates.holder_custodian_code（V52，放证线）'
    '→ ③ vehicle_units.warehouse_custodian_code（V50，放车线）→ ④ 全空 = RELS_006，不下发';
COMMENT ON COLUMN release_order_items.receiver_partner_code IS
    '接收方的 partner 身份（逻辑引用 integration.partners.partner_code，V71 = W-U3 号段）。'
    '出站指令按它拆分（D09 §4 出站拆分规则），审计主体 actor_type=PARTNER 也取它（D10 S-2）';
COMMENT ON COLUMN release_order_items.item_status IS
    '项态：PENDING 待处理 / INSTRUCTED 已随指令下发 / ACKNOWLEDGED 对方受理 / '
    'RELEASED 本项已释放 / NOT_RELEASED 本项未释放（部分释放的另一半事实）/ REFUSED 拒收。'
    '主表状态由本列聚合（D09 §5.3「取最不利」），不是相反';
COMMENT ON COLUMN release_order_items.receipt_id IS
    '推进本项的那条回执（逻辑引用 release_receipts.id，同上下文但跨服务写入方不同，故不建外键）。'
    '部分释放场景一个项可能被多笔回执推进，本列存"最后一次"，历史在 release_receipts 侧';
COMMENT ON COLUMN release_order_items.refuse_reason IS
    'REFUSED / NOT_RELEASED 的原因。必填由应用层校验：无原因拒收 = 账实缺口无法解释（D09 §4）';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_roi_item_status') THEN
        ALTER TABLE release_order_items
            ADD CONSTRAINT chk_roi_item_status
            CHECK (item_status IN ('PENDING', 'INSTRUCTED', 'ACKNOWLEDGED',
                                   'RELEASED', 'NOT_RELEASED', 'REFUSED')) NOT VALID;
    END IF;
    ALTER TABLE release_order_items VALIDATE CONSTRAINT chk_roi_item_status;
END $$;

-- item_type 的 USED 域扩展（D00 §8.3 ReleaseItemType 六值）：
-- V19 只写了注释、没有 CHECK，所以这里是"新增约束"而不是"扩展约束"。
-- 取值集合含 LEGACY 三值（VEHICLE/CERTIFICATE/KEY），存量行不会因它失败。
-- ★ 关键区分：CERTIFICATE = 车辆合格证（LEGACY，V19 口径），
--   INSPECTION_CERT = 車検証（USED 放证线的真正对象）。两者同名混用会让"放证"
--   指向一张二手车业务里根本不存在的合格证（D04 §1.2 避讳第 2 行）。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_roi_item_type') THEN
        ALTER TABLE release_order_items
            ADD CONSTRAINT chk_roi_item_type
            CHECK (item_type IN ('VEHICLE', 'CERTIFICATE', 'KEY',
                                 'INSPECTION_CERT', 'REGISTRATION_DOCS', 'OUTBOUND_DOC')) NOT VALID;
    END IF;
    ALTER TABLE release_order_items VALIDATE CONSTRAINT chk_roi_item_type;
END $$;

COMMENT ON COLUMN release_order_items.item_type IS
    '释放项类型，六值域（D00 §8.3）：VEHICLE 放车 / CERTIFICATE 合格证（LEGACY）/ KEY 放钥匙（只作事实记录）'
    ' ＋ INSPECTION_CERT 車検証 / REGISTRATION_DOCS 登记文件 / OUTBOUND_DOC 出库单（USED 域新增）。'
    'INSPECTION_CERT 与 CERTIFICATE 不得混用：放证线在 USED 域的对象是車検証，不是合格证';

-- V68 新列的默认值 'PENDING' 对存量行是个假事实：存量里 released=true 的项其实早已释放。
-- 按 D04 §10「唯一允许 UPDATE 的迁移内容」回填，并逐行留 DATA_BACKFILL 审计（操作人 system:migration）。
INSERT INTO audit_events (actor, actor_role, action, target_type, target_id,
                          before_snapshot, after_snapshot, trace_id)
SELECT 'system:migration', NULL, 'DATA_BACKFILL', 'release_order_items', i.id::text,
       json_build_object('item_status', 'PENDING', 'released', i.released)::text,
       json_build_object('item_status', 'RELEASED', 'migration', 'V68',
                         'rule', 'D04 §10：由 V19 既有 released 布尔反推项态，不新造事实')::text,
       'V68__release_item_receiver'
FROM release_order_items i
WHERE i.released = TRUE AND i.item_status = 'PENDING'
  AND NOT EXISTS (SELECT 1 FROM audit_events a
                  WHERE a.action = 'DATA_BACKFILL'
                    AND a.target_type = 'release_order_items'
                    AND a.target_id = i.id::text
                    AND a.trace_id = 'V68__release_item_receiver');

UPDATE release_order_items SET item_status = 'RELEASED' WHERE released = TRUE AND item_status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_roi_order_status ON release_order_items (order_id, item_status);
CREATE INDEX IF NOT EXISTS idx_roi_receiver     ON release_order_items (receiver_custodian_code)
    WHERE receiver_custodian_code IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_roi_receipt      ON release_order_items (receipt_id)
    WHERE receipt_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- ② release_receipts：USED 证据列（D04 §5.2 / D09 §7.2 三层防伪的第一、二层落点）
-- ---------------------------------------------------------------------------
ALTER TABLE release_receipts
    ADD COLUMN IF NOT EXISTS partner_code        VARCHAR(64),
    ADD COLUMN IF NOT EXISTS signature_ok        BOOLEAN,
    ADD COLUMN IF NOT EXISTS actual_items        VARCHAR(128),
    ADD COLUMN IF NOT EXISTS photo_paths         TEXT,
    ADD COLUMN IF NOT EXISTS exception_type      VARCHAR(24),
    ADD COLUMN IF NOT EXISTS inbound_message_id  BIGINT,
    ADD COLUMN IF NOT EXISTS item_type           VARCHAR(24),
    ADD COLUMN IF NOT EXISTS release_result      VARCHAR(24);

COMMENT ON COLUMN release_receipts.partner_code IS
    '回执主体（actor_type=PARTNER 的自然人替代，D10 S-2）。与 confirmed_by 的分工：'
    'partner_code 记"哪家机构回的"，confirmed_by 记"对方经办人/我方代录人"，两者不得混填';
COMMENT ON COLUMN release_receipts.signature_ok IS
    'HMAC 验签结论（D07 §2.3）。PARTNER_RECEIPT 要求本列为 TRUE：'
    '列刻意可为 NULL（人工代录无报文可判），因此"TRUE"由应用层断言 + 每日对账（D04 §6 步骤 4+(c)）双防，'
    '不写成 CHECK —— CHECK 无法表达"NULL 时按 source 分流"的跨列语义';
COMMENT ON COLUMN release_receipts.actual_items IS
    '对方申报**实际释放**的内容 CSV（ReleaseItemType 六值域，D00 §8.3）。'
    '与主表 release_items（我方要求放什么）是两回事，两者的差 = 部分释放/拒收的证据（D09 §5.3）';
COMMENT ON COLUMN release_receipts.photo_paths IS
    '现场照片本地路径（JSON 数组）。V19 已有 photos 列，本列是 USED 域回执最小字段集（D09 §7.3）'
    '要求的可枚举形态；两者并存时以本列为准，photos 只作 LEGACY 兼容读（差异已登记待收口）';
COMMENT ON COLUMN release_receipts.exception_type IS
    '异常类型九值（D00 §8.3）：NONE/REJECTED/PARTIAL/DELAYED/VEHICLE_NOT_FOUND/DOC_MISMATCH/'
    'ODOMETER_DIFF/DAMAGE_FOUND/SEALED_BROKEN。现状该列从不写入，本轮要求必填（默认 NONE）';
COMMENT ON COLUMN release_receipts.item_type IS
    '本回执对应的释放项类型（INSPECTION_CERT / VEHICLE / KEY / CERTIFICATE / REGISTRATION_DOCS / '
    'OUTBOUND_DOC）。D07 §6 RELEASE_ORDER_RECEIPTED 的 payload 要求 item_type，'
    '没有本列就得从主表 release_items CSV 猜，多项目指令必然猜错';
COMMENT ON COLUMN release_receipts.release_result IS
    '本回执的结论：RELEASED / PARTIAL / REFUSED / ACKNOWLEDGED。'
    '是"这一次回执的结论"，不是项态（项态在 release_order_items.item_status）';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_receipt_exception_type') THEN
        ALTER TABLE release_receipts
            ADD CONSTRAINT chk_receipt_exception_type
            CHECK (exception_type IN ('NONE', 'REJECTED', 'PARTIAL', 'DELAYED',
                                      'VEHICLE_NOT_FOUND', 'DOC_MISMATCH', 'ODOMETER_DIFF',
                                      'DAMAGE_FOUND', 'SEALED_BROKEN')) NOT VALID;
    END IF;
END $$;

-- 回执的项类型与结论同样钉死取值域：RELEASE_ORDER_RECEIPTED 的 payload 要 item_type + result
-- （D07 §6），自由文本会让消费端按字符串匹配判错。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_receipt_item_type') THEN
        ALTER TABLE release_receipts
            ADD CONSTRAINT chk_receipt_item_type
            CHECK (item_type IS NULL OR item_type IN ('VEHICLE', 'CERTIFICATE', 'KEY',
                                                      'INSPECTION_CERT', 'REGISTRATION_DOCS',
                                                      'OUTBOUND_DOC')) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_receipt_result') THEN
        ALTER TABLE release_receipts
            ADD CONSTRAINT chk_receipt_result
            CHECK (release_result IS NULL OR release_result IN
                   ('ACKNOWLEDGED', 'RELEASED', 'PARTIAL', 'REFUSED')) NOT VALID;
    END IF;
END $$;

-- exception_type：V19 起就是可空列，存量行全为 NULL ⇒ 先回填 NONE（D04 §10），再设默认与 NOT NULL。
-- 顺序不能反：NOT NULL 先上会让存量行直接把迁移打回滚。
INSERT INTO audit_events (actor, actor_role, action, target_type, target_id,
                          before_snapshot, after_snapshot, trace_id)
SELECT 'system:migration', NULL, 'DATA_BACKFILL', 'release_receipts', r.id::text,
       json_build_object('exception_type', NULL)::text,
       json_build_object('exception_type', 'NONE', 'migration', 'V68',
                         'rule', 'D04 §10：NULL→NONE；NONE 表示"未申报异常"，不是"已核实无异常"')::text,
       'V68__release_item_receiver'
FROM release_receipts r
WHERE r.exception_type IS NULL
  AND NOT EXISTS (SELECT 1 FROM audit_events a
                  WHERE a.action = 'DATA_BACKFILL'
                    AND a.target_type = 'release_receipts'
                    AND a.target_id = r.id::text
                    AND a.trace_id = 'V68__release_item_receiver'
                    AND a.after_snapshot LIKE '%"exception_type": "NONE"%');

UPDATE release_receipts SET exception_type = 'NONE' WHERE exception_type IS NULL;
ALTER TABLE release_receipts ALTER COLUMN exception_type SET DEFAULT 'NONE';
ALTER TABLE release_receipts VALIDATE CONSTRAINT chk_receipt_exception_type;
-- ★ 有意**不加** NOT NULL：D04 §5.2 写的是 `NOT NULL DEFAULT 'NONE'`，但既有
--   ReleaseApplicationService.saveReceipt(...) 不传该列，LEGACY 路径一旦显式传 NULL
--   就会当场报错、把放车主链路打断。本轮只把域钉死（CHECK）+ 给默认值（省略即 NONE），
--   NOT NULL 留作主干回归后的收口动作（先实查 SELECT count(*) WHERE exception_type IS NULL）。

CREATE INDEX IF NOT EXISTS idx_receipt_partner ON release_receipts (partner_code, confirmed_at DESC)
    WHERE partner_code IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_receipt_inbox   ON release_receipts (inbound_message_id)
    WHERE inbound_message_id IS NOT NULL;
