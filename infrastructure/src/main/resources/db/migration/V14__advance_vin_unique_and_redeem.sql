-- ============================================================================
-- V14：一车一笔垫款 + 垫款终态（作废 / 已清偿）
-- ============================================================================
--
-- 【要解决的业务问题】
--   一台车只允许有一笔有效垫款。但"有效"不等于"永久"：
--     ① 经销商撤销申请（或风控驳回、超时未放款）—— 钱一分未付；
--     ② 质押中经销商提前还款赎车 —— 钱付过、后来又还清，物权回归经销商；
--   这两种情况发生后，同一台车记录失效，日后再来申请垫款必须能通过。
--
-- 【为什么不是物理删除、也不是加 deleted_at 软删除】
--   · 物理删除：丢掉"谁撤的、何时撤的、额度被占了多久"——这三个问题恰是
--     重复融资风控要看的信号（一台车反复申请又反复撤销本身就是异常）。
--   · 软删除：advances 被额度占用、还款核销、审计、对账多处引用，加 deleted_at 后
--     每一处查询都必须带 IS NULL，漏一处就是资损，且往往季度对账才暴露。
--     这与 ADR-002「禁止把规则藏进隐式行为」直接冲突。
--   因此用显式终态：VOIDED（未付款作废）/ REDEEMED（已付款后还清），
--   记录全部保留，约束由部分唯一索引显式表达。
--
-- 【VOIDED 与 REDEEMED 为什么必须分开】
--   前者没有任何资金流，后者有真实的付款与还款流水。合并成一个"作废"后，
--   财务对账无法区分"从没付过钱"和"付了又还清"，垫款累计发生额会凭空少一整笔。
--
-- 【本脚本做五件事】
--   1. advances 增加终结痕迹字段 closed_at / closed_reason；
--   2. 清理存量重复在途垫款（保留最早一条，其余作废并释放重复占用的额度）；
--   3. 修正历史遗留：已结清车辆的垫款记录仍停在 PAID → 推进到 REDEEMED；
--   4. 重建 vehicle_units 的 VIN 幂等索引，纳入 REDEEMED（车还在库，台账唯一）；
--   5. 新建 advances 的部分唯一索引 uk_adv_vin_active：一车一笔有效垫款。
-- ============================================================================

-- ------------------------------------------------------------
-- 1. 终结痕迹字段
-- ------------------------------------------------------------
-- closed_at     : 进入 VOIDED / REDEEMED / REVERSED 的时刻
--                 （额度占用时长、同一台车两次融资的间隔都要靠它算）
-- closed_reason : 同一终态下的不同业务事实，取值见下方 COMMENT
ALTER TABLE advances ADD COLUMN IF NOT EXISTS closed_at     TIMESTAMPTZ;
ALTER TABLE advances ADD COLUMN IF NOT EXISTS closed_reason VARCHAR(32);

COMMENT ON COLUMN advances.closed_at IS
    '终结时间：进入 VOIDED / REDEEMED / REVERSED 的时刻';

COMMENT ON COLUMN advances.closed_reason IS
    '终结原因：DEALER_CANCELLED 经销商主动撤销 / REVIEW_REJECTED 风控驳回 / TIMEOUT 超时未放款 / '
    'CHANNEL_FAILED 通道失败 / SOLD_SETTLED 售出结清 / EARLY_REDEEMED 质押中提前赎回 / DUPLICATE 历史重复数据';

-- ------------------------------------------------------------
-- 2. 清理存量重复在途垫款
-- ------------------------------------------------------------
-- 背景：ADR-010 记录的真实事故——同一台车在 33 毫秒内被提交多次，
-- 生成多条金额完全相同的 advance，idempotency_key 各不相同，额度被重复占用多份。
-- 该问题的直接成因是"申请垫款"没有一车一笔的数据库级约束，
-- 应用层只能靠车辆状态间接兜底（详见下方第 5 节）。
--
-- 处理原则：保留最早一条（id 最小）作为该车的有效垫款，
-- 其余置 VOIDED（closed_reason='DUPLICATE'）并释放其占用的额度。
-- 不做物理删除——留着才能看清"当初多占了多少、占了多久"。
CREATE TEMP TABLE _dup_advance ON COMMIT DROP AS
SELECT id,
       vin,
       credit_line_id,
       amount,
       ROW_NUMBER() OVER (PARTITION BY vin ORDER BY id) AS rn
FROM advances
WHERE status IN ('PENDING', 'PROCESSING', 'PAID');

-- 2.1 先释放重复记录占用的额度（按额度维度汇总，避免逐条 UPDATE 竞态）
UPDATE credit_lines cl
SET used_amount = cl.used_amount - d.dup_amount,
    updated_at  = now()
FROM (SELECT credit_line_id, SUM(amount) AS dup_amount
      FROM _dup_advance
      WHERE rn > 1
      GROUP BY credit_line_id) d
WHERE cl.id = d.credit_line_id;

-- 2.2 再把重复记录置为作废
UPDATE advances a
SET status        = 'VOIDED',
    closed_at     = now(),
    closed_reason = 'DUPLICATE'
FROM _dup_advance d
WHERE a.id = d.id
  AND d.rn > 1;

-- ------------------------------------------------------------
-- 3. 修正历史遗留：已结清车辆的垫款仍停在 PAID
-- ------------------------------------------------------------
-- 原实现的 settle() 只改车辆台账，从不触碰垫款记录的状态。
-- 结果：车已 SETTLED，垫款却永久停在 PAID —— 而 PAID 表示"债权存续中"，
-- 它占用该 VIN 的唯一垫款名额，等于这台车结清后再也借不出来。
--
-- 仅处理 vehicle_status = 'SETTLED'（售出结清）的情形。
-- CLOSED（异常关单/处置完成）的垫款保持 PAID 不动：那笔钱可能已损失，
-- 终结必须走带资金流的 REVERSED（冲正），不能当成"已清偿"抹掉。
UPDATE advances a
SET status        = 'REDEEMED',
    closed_at     = COALESCE(v.settled_at, now()),
    closed_reason = 'SOLD_SETTLED'
FROM vehicle_units v
WHERE v.vin = a.vin
  AND a.status = 'PAID'
  AND v.status = 'SETTLED';

-- ------------------------------------------------------------
-- 4. 重建 VIN 幂等索引：纳入 REDEEMED
-- ------------------------------------------------------------
-- REDEEMED 的车还在经销商场地，台账就是这台车的唯一档案。
-- 若把 REDEEMED 排除在索引条件外，同一 VIN 可以再建一条台账，
-- 历史查询立刻出现两台"同一辆车"，两次融资的债权归属无法对齐。
-- 再次融资应复用本条台账，而不是新开一条。
-- 前提：VehicleStatus.ACTIVE 集合必须与本 WHERE 条件严格一致。
DROP INDEX IF EXISTS uk_po_vin_active;

CREATE UNIQUE INDEX uk_po_vin_active
    ON vehicle_units (vin)
    WHERE purchase_order_id IS NOT NULL
      AND status IN ('CREATED', 'PENDING_ADVANCE', 'FINANCED', 'IN_STOCK', 'SOLD_PENDING', 'REDEEMED');

COMMENT ON INDEX uk_po_vin_active IS
    'VIN 幂等：同一 VIN 不得出现在两个未终结采购订单中；状态集合须与 VehicleStatus.ACTIVE 同步（V14 起含 REDEEMED）';

-- ------------------------------------------------------------
-- 5. 一车一笔有效垫款（核心约束）
-- ------------------------------------------------------------
-- 这是"一台车只允许一笔垫款"在数据库层的唯一落点。
--
-- 为什么必须是部分唯一索引而不是普通唯一约束：
--   同一台车可以合法地拥有多条历史垫款记录（撤销后重提、还清后再借），
--   只有"仍在占用名额"的那一笔才要求唯一。
--
-- WHERE 条件必须与 AdvanceStatus.occupiesVehicle() 严格一致：
--   PENDING    钱在路上（额度已占）
--   PROCESSING 钱已提交银行（额度已占）
--   PAID       债权存续中（额度已占）
--   FAILED / VOIDED / REDEEMED / REVERSED 均已终结，不占名额，可重新申请。
--
-- 注意：本索引能建上的前提是第 2 节已清理干净重复数据。
-- 若此处报唯一约束冲突，说明库里仍有同 VIN 多笔有效垫款，须先人工排查。
DROP INDEX IF EXISTS uk_adv_vin_active;

CREATE UNIQUE INDEX uk_adv_vin_active
    ON advances (vin)
    WHERE status IN ('PENDING', 'PROCESSING', 'PAID');

COMMENT ON INDEX uk_adv_vin_active IS
    '一车一笔有效垫款：PENDING/PROCESSING/PAID 三种状态同一 VIN 至多一条；'
    '作废(VOIDED)与已清偿(REDEEMED)可存在多条，形成该车的完整融资时间线';

-- ------------------------------------------------------------
-- 6. 台账查询辅助索引
-- ------------------------------------------------------------
-- 车辆详情页要按 VIN 倒序列出该车全部融资历史（含已作废/已清偿），
-- 原 idx_advances_vin 没有排序信息，数据量上来后每次都要额外排序。
CREATE INDEX IF NOT EXISTS idx_advances_vin_id ON advances (vin, id DESC);

-- "待放款清单"排除已作废记录时用得上
CREATE INDEX IF NOT EXISTS idx_advances_status_closed ON advances (status, closed_at)
    WHERE status IN ('PENDING', 'PROCESSING');
