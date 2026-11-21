-- ============================================================
-- V13  状态机起点再前移一级：引入 CREATED（已建账）
-- ============================================================
-- 背景（与 V12 的关系）
--   V12 把"待垫款 PENDING_ADVANCE"从"已垫款 FINANCED"里拆了出来，
--   但把 PENDING_ADVANCE 当成建账时的初始态。业务上仍不对：
--
--     批量到货建账  →  采购审核岗线下验车确权  →  applyAdvance  →  放款确认
--     （CREATED）        （不改状态，是申请前置）   （PENDING_ADVANCE）（FINANCED）
--
--   "已申请待放款"与"刚建完账还没申请"是两件事。混在一个状态里，
--   待放款工作台会把还没验车确权的车一并列出，出纳可能对未确权的车付款。
--
-- 本脚本做数据侧三件事；代码侧（枚举白名单、registerLedger/markPendingAdvance、
-- stageAdvance 校验、markFailed 回退）由应用发版同步完成，二者必须一起上线。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 存量拆分：没有在途垫款指令的 PENDING_ADVANCE → CREATED
-- ------------------------------------------------------------
-- 判定口径：该 VIN 不存在 PENDING / PROCESSING 的垫款指令。
--   有在途指令  → 确实是"已申请待放款"，保留 PENDING_ADVANCE；
--   只有 FAILED → 申请失败、额度已释放，属"还没申请"，降为 CREATED；
--   完全没有    → 从未申请，降为 CREATED。
-- 绝不误降有在途指令的车——误降会让一台正在放款流程中的车重新开放申请入口，
-- 造成额度双重占用。
DO $$
DECLARE
    v_candidate INT;
    v_migrated  INT;
BEGIN
    SELECT COUNT(*) INTO v_candidate
      FROM vehicle_units v
     WHERE v.status = 'PENDING_ADVANCE'
       AND NOT EXISTS (
           SELECT 1 FROM advances a
            WHERE a.vin = v.vin AND a.status IN ('PENDING', 'PROCESSING')
       );

    UPDATE vehicle_units v
       SET status     = 'CREATED',
           updated_at = now()
     WHERE v.status = 'PENDING_ADVANCE'
       AND NOT EXISTS (
           SELECT 1 FROM advances a
            WHERE a.vin = v.vin AND a.status IN ('PENDING', 'PROCESSING')
       );

    GET DIAGNOSTICS v_migrated = ROW_COUNT;

    RAISE NOTICE 'V13 已建账拆分：候选 % 台，实际迁移 % 台', v_candidate, v_migrated;

    IF v_migrated <> v_candidate THEN
        RAISE EXCEPTION 'V13 迁移行数不一致（候选 %，实际 %），事务中止', v_candidate, v_migrated;
    END IF;
END $$;

-- ------------------------------------------------------------
-- 2. 重建 VIN 幂等索引：未终结状态集合加入 CREATED
-- ------------------------------------------------------------
-- CREATED 的车同样挂在采购订单下、同样占用 VIN 幂等名额，必须纳入。
-- 前提：VehicleStatus.ACTIVE 集合必须与本 WHERE 条件严格一致。
DROP INDEX IF EXISTS uk_po_vin_active;

CREATE UNIQUE INDEX uk_po_vin_active
    ON vehicle_units (vin)
    WHERE purchase_order_id IS NOT NULL
      AND status IN ('CREATED', 'PENDING_ADVANCE', 'FINANCED', 'IN_STOCK', 'SOLD_PENDING');

COMMENT ON INDEX uk_po_vin_active IS
    'VIN 幂等：同一 VIN 不得出现在两个未终结采购订单中；状态集合须与 VehicleStatus.ACTIVE 同步（V13 起含 CREATED）';

-- ------------------------------------------------------------
-- 3. "待验车确权"工作台索引
-- ------------------------------------------------------------
-- 采购审核岗按经销商拉待办清单：CREATED 且尚未确权。
CREATE INDEX IF NOT EXISTS idx_vu_created
    ON vehicle_units (dealer_id, created_at)
    WHERE status = 'CREATED';

COMMENT ON INDEX idx_vu_created IS '待验车确权清单：仅索引 CREATED 行';

-- ------------------------------------------------------------
-- 4. 迁移后自检（只读，失败即中止）
-- ------------------------------------------------------------
DO $$
DECLARE
    v_bad_created   INT;
    v_bad_pending   INT;
    v_stuck         INT;
BEGIN
    -- 自检 A：已建账的车不应带在途垫款指令（误降 → 会重复占用额度）
    SELECT COUNT(*) INTO v_bad_created
      FROM vehicle_units v
     WHERE v.status = 'CREATED'
       AND EXISTS (SELECT 1 FROM advances a
                    WHERE a.vin = v.vin AND a.status IN ('PENDING','PROCESSING'));

    IF v_bad_created > 0 THEN
        RAISE EXCEPTION 'V13 自检失败：% 台已建账车辆存在在途垫款指令，疑似误降，事务中止', v_bad_created;
    END IF;

    -- 自检 B：待垫款的车必须真的有在途指令，否则状态在撒谎
    SELECT COUNT(*) INTO v_bad_pending
      FROM vehicle_units v
     WHERE v.status = 'PENDING_ADVANCE'
       AND NOT EXISTS (SELECT 1 FROM advances a
                        WHERE a.vin = v.vin AND a.status IN ('PENDING','PROCESSING'));

    IF v_bad_pending > 0 THEN
        RAISE EXCEPTION 'V13 自检失败：% 台待垫款车辆无在途垫款指令，状态与申请事实矛盾，事务中止', v_bad_pending;
    END IF;

    -- 自检 C：已建账的车不该已经有垫款金额入账
    SELECT COUNT(*) INTO v_stuck
      FROM vehicle_units
     WHERE status = 'CREATED'
       AND (advanced_at IS NOT NULL OR principal_balance > 0);

    IF v_stuck > 0 THEN
        RAISE EXCEPTION 'V13 自检失败：% 台已建账车辆已有垫款事实（advanced_at/本金），事务中止', v_stuck;
    END IF;

    RAISE NOTICE 'V13 自检通过：无重复占用、无状态与申请事实矛盾的行';
END $$;
