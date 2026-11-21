-- ============================================================
-- V12  车辆生命周期起点修正：引入 PENDING_ADVANCE（待垫款）
-- ============================================================
-- 背景（缺陷成因）
--   原实现把"建账"与"已放款"合并成一步：建账即置 FINANCED。
--   由此产生三处连锁错误：
--     1. FINANCED 语义是"金融公司已付款给厂家"，却出现在付款之前；
--     2. stageAdvance（申请垫款）反过来要求"申请前状态必须已是已放款"；
--     3. finance() 在真正付款时被迫绕过状态机白名单直接赋值
--        （因为状态已是 FINANCED，走 transit 会命中 this == target 而抛错）。
--   结果：状态机里没有"待放款"，"在途"与"已放款"共用一个状态，
--        付款这一刻被压缩掉，"钱付了没有"在状态上不可表达。
--
-- 本脚本只做数据侧的两件事；代码侧（枚举白名单、finance 流转、
-- stageAdvance 校验）由应用发版同步完成，二者必须一起上线。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 存量数据拆分：把"未付款"的 FINANCED 车降为 PENDING_ADVANCE
-- ------------------------------------------------------------
-- 判定口径（双条件，缺一不可）：
--   a) vehicle_units.advanced_at IS NULL   —— 台账从未记录垫款日
--   b) 该 VIN 无 PAID 状态的垫款记录        —— 垫款指令从未成功
-- 只要有任何付款痕迹（advanced_at 有值，或存在 PAID 垫款），
-- 一律保留 FINANCED，绝不误降——误降会让已付款的车重新变回"可申请垫款"，
-- 造成重复放款，性质比状态不准严重得多。
DO $$
DECLARE
    v_candidate INT;
    v_migrated  INT;
BEGIN
    SELECT COUNT(*) INTO v_candidate
      FROM vehicle_units v
     WHERE v.status = 'FINANCED'
       AND v.advanced_at IS NULL
       AND NOT EXISTS (
           SELECT 1 FROM advances a
            WHERE a.vin = v.vin AND a.status = 'PAID'
       );

    UPDATE vehicle_units v
       SET status     = 'PENDING_ADVANCE',
           updated_at = now()
     WHERE v.status = 'FINANCED'
       AND v.advanced_at IS NULL
       AND NOT EXISTS (
           SELECT 1 FROM advances a
            WHERE a.vin = v.vin AND a.status = 'PAID'
       );

    GET DIAGNOSTICS v_migrated = ROW_COUNT;

    RAISE NOTICE 'V12 待垫款拆分：候选 % 台，实际迁移 % 台', v_candidate, v_migrated;

    IF v_migrated <> v_candidate THEN
        RAISE EXCEPTION 'V12 迁移行数不一致（候选 %，实际 %），事务中止', v_candidate, v_migrated;
    END IF;

    IF v_candidate > 0 THEN
        RAISE NOTICE 'V12 已迁移的 VIN 明细可执行以下语句复查：'
                     'SELECT vin, status, advanced_at FROM vehicle_units WHERE status = ''PENDING_ADVANCE'';';
    END IF;
END $$;

-- ------------------------------------------------------------
-- 2. 重建 VIN 幂等索引，把 PENDING_ADVANCE 纳入未终结状态集合
-- ------------------------------------------------------------
-- 说明：vehicle_units.vin 本身是主键，已保证 VIN 物理唯一，
--   因此本索引当前并不承担"拦重复"的实际作用（真正拦重复的是主键 +
--   应用层 existsByVin）。保留并同步状态集合，是为了让
--   "同一 VIN 只能有一条未终结记录"这个业务意图在 schema 上保持可读，
--   也为将来把主键改为 (vin, seq) 留好替换点。
-- 前提：VehicleStatus.ACTIVE 集合必须与本 WHERE 条件严格一致。
DROP INDEX IF EXISTS uk_po_vin_active;

CREATE UNIQUE INDEX uk_po_vin_active
    ON vehicle_units (vin)
    WHERE purchase_order_id IS NOT NULL
      AND status IN ('PENDING_ADVANCE', 'FINANCED', 'IN_STOCK', 'SOLD_PENDING');

COMMENT ON INDEX uk_po_vin_active IS
    'VIN 幂等：同一 VIN 不得出现在两个未终结采购订单中；状态集合须与 VehicleStatus.ACTIVE 同步（V12 起含 PENDING_ADVANCE）';

-- ------------------------------------------------------------
-- 3. 待垫款工作台查询索引
-- ------------------------------------------------------------
-- "待放款清单"是业务经办岗最高频的查询：按经销商筛 + 按建账时间排序。
CREATE INDEX IF NOT EXISTS idx_vu_pending_advance
    ON vehicle_units (dealer_id, created_at)
    WHERE status = 'PENDING_ADVANCE';

COMMENT ON INDEX idx_vu_pending_advance IS '待放款清单：仅索引 PENDING_ADVANCE 行';

-- ------------------------------------------------------------
-- 4. 迁移后自检（只读，失败即中止）
-- ------------------------------------------------------------
DO $$
DECLARE
    v_paid_but_pending INT;
    v_pending_with_advance INT;
BEGIN
    -- 自检 A：不该存在"已有 PAID 垫款却处于待垫款"的车（误降）
    SELECT COUNT(*) INTO v_paid_but_pending
      FROM vehicle_units v
     WHERE v.status = 'PENDING_ADVANCE'
       AND EXISTS (SELECT 1 FROM advances a WHERE a.vin = v.vin AND a.status = 'PAID');

    IF v_paid_but_pending > 0 THEN
        RAISE EXCEPTION 'V12 自检失败：% 台待垫款车辆存在 PAID 垫款记录，疑似误降，事务中止',
            v_paid_but_pending;
    END IF;

    -- 自检 B：待垫款车不应带垫款日
    SELECT COUNT(*) INTO v_pending_with_advance
      FROM vehicle_units
     WHERE status = 'PENDING_ADVANCE'
       AND advanced_at IS NOT NULL;

    IF v_pending_with_advance > 0 THEN
        RAISE EXCEPTION 'V12 自检失败：% 台待垫款车辆带 advanced_at，状态与资金事实矛盾，事务中止',
            v_pending_with_advance;
    END IF;

    RAISE NOTICE 'V12 自检通过：无误降、无状态与资金事实矛盾的行';
END $$;
