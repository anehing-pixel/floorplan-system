-- ===========================================================================
-- V67：放车放证指令 USED 化 + confirmation_authority 收紧为受约束列（W-U4，R7）
-- ===========================================================================
--
-- 【规格来源】D04 §5.2（release_orders / vehicle_units 行）、§6（四步收紧方案）、
--   §7（COMMENT ON 覆盖）、§10（回填规则）；D09 §2 两线、§5.1 十态写入者、
--   §6 S4/S9、§7.1 U-09 降级、§11 证据链八条；D07 §7.6（dispatch 重校验清单、settle_seq 定源）。
--
-- 【为什么十态不需要为"放宽 CHECK"建迁移，但本轮还是要建一条】
-- D03 §3.3 落地形态实测 `release_orders.status VARCHAR(24)` **无 CHECK**，
-- 所以扩到十态不需要放宽（"不需要建迁移"这句仅指放宽动作）。
-- 本文件加的 `chk_release_order_status` 是**收紧**：把状态域钉死成十值白名单，
-- 让"代人审批"类的野状态值写不进库。用 NOT VALID 落约束、VALIDATE 延到主干回归，
-- 因为分支库看不到主干可能存在的历史脏值，先 VALIDATE 会把整个迁移打回滚
-- （V19 语法错误全断的同一条教训，D04 §12 第 3/7 条）。
--
-- 【confirmation_authority 的可伪造面（本轮最关键的一处）】
-- 现状：该列是自由 VARCHAR，`normalizeAuthority()` 只做判空 + 大写化
-- （ReleaseApplicationService.java:96-101），任何字符串——包括伪造的
-- 'PARTNER_RECEIPT'——都能落库并把状态推到 RELEASED（红线 2）。
-- D04 §6 的四步就是把这个"约定"变成"数据库层不可能事件"。
--
-- 【D00 §8.3 与本文件的号段差异（待收口裁决）】
-- D00 §8.3 写「列由自由 VARCHAR 改为受约束（**V65 段**迁移加 CHECK + 回填清洗）」，
-- 但 D04 §6 标题与 §2 号段表都把该收紧放在 **V67**（V65 的主题是 custodians/WMS 入库，
-- 与 release_receipts 无关）。本文件按 D04 落在 V67；D00 那句按"文档描述错位"处理，不改代码。
--
-- 【幂等与顺序安全】
-- 全文件可重复执行：加列 IF NOT EXISTS、加约束先查 pg_constraint、回填 UPDATE 天然幂等。
-- 无 DROP / TRUNCATE / DELETE（D04 §12）。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- ① release_orders USED 列（D04 §5.2 第 5 行）
-- ---------------------------------------------------------------------------
ALTER TABLE release_orders
    ADD COLUMN IF NOT EXISTS inspection_certificate_no  VARCHAR(64),
    ADD COLUMN IF NOT EXISTS wms_warehouse_code         VARCHAR(32),
    ADD COLUMN IF NOT EXISTS auction_lot_id             BIGINT,
    ADD COLUMN IF NOT EXISTS acked_at                   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS expired_at                 TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS partial_at                 TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS lien_extinguished_at       TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_inbound_message_id    BIGINT,
    ADD COLUMN IF NOT EXISTS receipt_degraded           BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS dispatch_seq               INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS product_line               VARCHAR(12);

COMMENT ON COLUMN release_orders.inspection_certificate_no IS
    'USED 域放证对象的車検証番号。与 certificate_no（V19 = 车辆合格证号，非法务局登记证明书）严格区分（D04 §1.2 避讳）';
COMMENT ON COLUMN release_orders.wms_warehouse_code IS
    '指令指向的监管仓代码（逻辑引用 custodians.custodian_code）。放车线接收方解析优先级 3 的输入（D09 §4）';
COMMENT ON COLUMN release_orders.auction_lot_id IS
    '落札行 ID（逻辑引用 auction_lots，V61 = W-U2 号段）。放车去拍卖场交付的链路溯源，不建外键';
COMMENT ON COLUMN release_orders.acked_at IS
    '对方受理时间（S7 → ACKNOWLEDGED 的写入时刻）。与 confirmed_at 不同：受理≠已放';
COMMENT ON COLUMN release_orders.expired_at IS
    '置 EXPIRED 的时间。只允许巡检作业写入（RB-1：EXPIRED 不接受人工/接口直写）';
COMMENT ON COLUMN release_orders.partial_at IS
    '首次出现部分释放的时间（→ PARTIAL）。补齐后仍走 CONFIRMED，本列只留事实不留结论';
COMMENT ON COLUMN release_orders.lien_extinguished_at IS
    '担保**实体权利消灭**时点（D09 §6 S4，两段式解押的第一段唯一载体）。'
    '与 lien_discharged_at（登记抹消完成，S9③）分列——V17 注释已明确 RELEASED ≠ 登记簿已抹消，'
    '两者混用会把"权利消灭"当成"登记已抹消"对外主张';
COMMENT ON COLUMN release_orders.last_inbound_message_id IS
    '最近一次推进该指令的入站报文（逻辑引用 integration.inbound_messages.id，V72 = W-U3 号段）。'
    '证据链第 4/5 条的溯源锚（D09 §11），不建外键';
COMMENT ON COLUMN release_orders.receipt_degraded IS
    'U-09 降级标记：本单结论含 INTERNAL_WITNESS 人工代录成分。'
    'D09 §7.1 采纳口径 = 可结清但必须在报表单列；本列是"单列"的唯一判据，不得由前端推断';
COMMENT ON COLUMN release_orders.dispatch_seq IS
    '下发轮次（幂等键 RLO:{order_no}:{dispatch_seq} 的组成部分，D07 §7.6 重校验清单 + D00 §10.2 '
    'RELEASE_ORDER_DISPATCHED 的 aggregate_key）。撤销后重新下发沿用同一 order_no 的新轮次；'
    'D04 §5.2 的 V67 列清单未列本列，按 D07 的幂等键定义补齐（矛盾已登记）';
COMMENT ON COLUMN release_orders.product_line IS
    '产品域标记 LEGACY / USED，决定状态机选表（D03 §3.3 落地形态）与接收方主用判定。'
    'D04 §2 把 product_line 的"语义基座"放在 V40（W-U1），但 §5.2 的 release_orders 列清单未含本列，'
    '而 D03 §3.3 / D04 §6 / D07 §7.6 的分叉判定都要读它 ⇒ 本包在 V67 补列（不建 CHECK，'
    '取值由应用层写死两值 + 域枚举约束），避免与 V40 的字典表做法抢口径';
COMMENT ON COLUMN release_orders.certificate_no IS
    '车辆合格证号——与法务局登记事项证明书严格区分，后者用于抹消，不是放证对象。'
    '【USED 域追加】USED 域不使用本列存車検証，車検証见 inspection_certificate_no（D04 §7）';

-- ---------------------------------------------------------------------------
-- ② vehicle_units 结清流水列（D04 §5.2 第 6 行 / D07 §7.6 settle_seq 定源）
-- ---------------------------------------------------------------------------
-- settle_seq = {order_no}:{release_receipts.id}，一次结清一个串，天然不复用；
-- 它是 CLR:{vin}:{settle_seq} 回补幂等键与 VEHICLE_SETTLED 的 aggregate_key 同源（D00 §10.2）。
-- 【不要拿 release_order_items.id 顶替】项可拆多次部分释放（PARTIAL），
-- 用项 id 会把"同一项的第二笔补齐回执"当重复吞掉（D13 G7 同 G2 形状的错误）。
ALTER TABLE vehicle_units
    ADD COLUMN IF NOT EXISTS settle_seq VARCHAR(64);

COMMENT ON COLUMN vehicle_units.settle_seq IS
    '结清流水号 {order_no}:{release_receipts.id}。回补幂等键 CLR:{vin}:{settle_seq} 与 '
    'VEHICLE_SETTLED 的 aggregate_key 同源；部分唯一索引 WHERE settle_seq IS NOT NULL，'
    '同一串只能结清一次（D07 §7.6 定源）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_vehicle_settle_seq
    ON vehicle_units (settle_seq)
    WHERE settle_seq IS NOT NULL;

-- ---------------------------------------------------------------------------
-- ③ 十态状态域收紧（D00 §8.3 / D03 §3.3）
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_release_order_status') THEN
        ALTER TABLE release_orders
            ADD CONSTRAINT chk_release_order_status
            CHECK (status IN ('PENDING_RELEASE', 'SENT', 'ACKNOWLEDGED', 'PARTIAL',
                              'CONFIRMED', 'RELEASED', 'LIEN_DISCHARGED',
                              'REJECTED', 'EXPIRED', 'CANCELLED')) NOT VALID;
        -- ★ 有意不在此 VALIDATE：分支库无法预知主干历史脏值，
        --   VALIDATE 延到主干回归（收口项，执行前用
        --   `SELECT DISTINCT status FROM release_orders` 实查取值集）。
    END IF;
END $$;

COMMENT ON CONSTRAINT chk_release_order_status ON release_orders IS
    'USED 域十态白名单（D03 §3.3）。迁移合法性仍由状态机类判定（ReleaseStateMachine / '
    'ReleaseStateMachineUsed），本 CHECK 只钉状态域，二者不是重复实现：'
    'CHECK 拦"写进一个不存在的态"，状态机拦"从 A 跳到 B"';

-- ---------------------------------------------------------------------------
-- ④ confirmation_authority 收紧四步（D04 §6，必须按序）
-- ---------------------------------------------------------------------------
-- inbound_message_id 按 §5.2 属 V68，但步骤 4 的 CHECK 要引用它 ⇒ 本文件先建，
-- V68 保留同一条 ADD COLUMN IF NOT EXISTS 以兼容 §5.2 的号段归属（两处都幂等）。
ALTER TABLE release_receipts
    ADD COLUMN IF NOT EXISTS inbound_message_id BIGINT;

COMMENT ON COLUMN release_receipts.inbound_message_id IS
    '外部回执的入站报文（逻辑引用 integration.inbound_messages.id，V72 = W-U3 号段）。'
    'PARTNER_RECEIPT 的**唯一**合法来源证据：没有本列的 PARTNER_RECEIPT 在库层就写不进去（D02 INV-5）';

-- ---- 步骤 2：回填清洗（D04 §10，唯一允许 UPDATE 的内容，U-05 红线）----
-- 规则：MANUAL / 空 / 不在三值集内的自由文本 → INTERNAL_WITNESS；
--       已是 PARTNER_RECEIPT 但**没有 inbound_message_id** 的行 → 降为 INTERNAL_WITNESS；
--       **绝不把任何行升级为 PARTNER_RECEIPT**（升级 = 把代录伪造成对方回执，红线）。
-- 每一条改写都写 audit_events(action=DATA_BACKFILL) 留痕（D04 §10 末行）。
INSERT INTO audit_events (actor, actor_role, action, target_type, target_id,
                          before_snapshot, after_snapshot, trace_id)
SELECT 'system:migration', NULL, 'DATA_BACKFILL', 'release_receipts', r.id::text,
       json_build_object('confirmation_authority', r.confirmation_authority,
                         'source', r.source,
                         'inbound_message_id', r.inbound_message_id)::text,
       json_build_object('confirmation_authority', 'INTERNAL_WITNESS',
                         'migration', 'V67',
                         'rule', 'D04 §10 / U-05 归一，不升级为 PARTNER_RECEIPT')::text,
       'V67__release_order_used_extension'
FROM release_receipts r
WHERE (r.confirmation_authority IS NULL
       OR r.confirmation_authority NOT IN ('PARTNER_RECEIPT', 'INTERNAL_WITNESS', 'DEALER_SELF_DECLARED')
       OR (r.confirmation_authority = 'PARTNER_RECEIPT' AND r.inbound_message_id IS NULL))
  -- 只留"还没留痕"的行：重复执行时不会因为上面已改成 INTERNAL_WITNESS 而再次插入
  -- （括号是必需的：AND 比 OR 结合更紧，不括起来 NOT EXISTS 只会挂到最后一个 OR 分支上，
  --   重跑就会堆重复审计行。）
  AND NOT EXISTS (SELECT 1 FROM audit_events a
                  WHERE a.action = 'DATA_BACKFILL'
                    AND a.target_type = 'release_receipts'
                    AND a.target_id = r.id::text
                    AND a.trace_id = 'V67__release_order_used_extension');

UPDATE release_receipts
   SET confirmation_authority = 'INTERNAL_WITNESS'
 WHERE confirmation_authority IS NULL
    OR confirmation_authority NOT IN ('PARTNER_RECEIPT', 'INTERNAL_WITNESS', 'DEALER_SELF_DECLARED')
    OR (confirmation_authority = 'PARTNER_RECEIPT' AND inbound_message_id IS NULL);

-- ---- 步骤 3：加三值 CHECK（NOT VALID → VALIDATE；回填后必然通过）----
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_receipt_confirmation_authority') THEN
        ALTER TABLE release_receipts
            ADD CONSTRAINT chk_receipt_confirmation_authority
            CHECK (confirmation_authority IN
                   ('PARTNER_RECEIPT', 'INTERNAL_WITNESS', 'DEALER_SELF_DECLARED')) NOT VALID;
    END IF;
    ALTER TABLE release_receipts VALIDATE CONSTRAINT chk_receipt_confirmation_authority;
END $$;

-- ---- 步骤 4：PARTNER_RECEIPT 必须有 inbound_message_id ----
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_receipt_partner_needs_inbox') THEN
        ALTER TABLE release_receipts
            ADD CONSTRAINT chk_receipt_partner_needs_inbox
            CHECK (confirmation_authority <> 'PARTNER_RECEIPT' OR inbound_message_id IS NOT NULL) NOT VALID;
    END IF;
    ALTER TABLE release_receipts VALIDATE CONSTRAINT chk_receipt_partner_needs_inbox;
END $$;

-- ---- 步骤 4+(b)：一条入站报文最多只授权一张回执 ----
-- 没有这条，一条真报文可以被复制引用去结清 N 台车，仍是伪造面（D04 §6 步骤 4+）。
CREATE UNIQUE INDEX IF NOT EXISTS ux_receipt_inbox
    ON release_receipts (inbound_message_id)
    WHERE inbound_message_id IS NOT NULL;

-- ---- 步骤 4+(a)：真外键（跨 schema 同库允许；integration 未建时跳过）----
-- integration.inbound_messages 属 V72（W-U3 号段），并行同基线时本分支库里不存在该表。
-- 这里做存在性判定：有则补 FK（NOT VALID，避免存量脏引用回滚整个迁移），
-- 无则跳过并把它留在收口清单——**不是**"以后再说"，而是主干回归必须补的约束。
DO $$
BEGIN
    IF to_regclass('integration.inbound_messages') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_receipt_inbox') THEN
        ALTER TABLE release_receipts
            ADD CONSTRAINT fk_receipt_inbox
            FOREIGN KEY (inbound_message_id) REFERENCES integration.inbound_messages (id) NOT VALID;
    END IF;
END $$;

-- ---- 步骤 4+(c)：CHECK 做不到跨表谓词（signature_ok=true 且类型一致），
--      因此留两条可执行防线：
--      ① 唯一写入口 = inbox dispatcher（禁止任何内部端点写 inbound_message_id，
--         库层由上面的 ux_receipt_inbox 保证"一条报文只授权一张回执"；
--         D07 §8 已把 RELS_001 = "指令当前状态不接受该回执" 定为该面的拒收码，D07 §13 第 5 条）；
--      ② 每日对账把「authority=PARTNER_RECEIPT 但 join 不到 signature_ok=true 行」列为 CRITICAL
--         （D09 §11 证据链核对）。本迁移只把判据的列建出来，不在 DDL 里伪造约束。----

COMMENT ON COLUMN release_receipts.confirmation_authority IS
    'V24 折中（我方见证代录 INTERNAL_WITNESS）仅适用 LEGACY 域；'
    'USED 域权威为 PARTNER_RECEIPT，且必须有 inbound_message_id（chk_receipt_partner_needs_inbox）。'
    'DEALER_SELF_DECLARED 是**禁止值**，存在仅为把现状写入值显式收编（D00 §8.3，P-6）。'
    '见 D04 §7';

COMMENT ON TABLE release_orders IS
    '放车放证指令主表。USED 域十态（D03 §3.3）：PENDING_RELEASE→SENT→ACKNOWLEDGED'
    '→{CONFIRMED|PARTIAL}→RELEASED→LIEN_DISCHARGED，旁路 REJECTED/EXPIRED→CANCELLED；'
    '两段式解押 lien_extinguished_at（实体权利消灭）≠ lien_discharged_at（登记抹消完成）';
