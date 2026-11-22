-- ===========================================================================
-- V25：ADR-Q27 裁决落地（担保权属权威源）
-- ===========================================================================
--
-- 【为什么用新迁移，不改 V18 文件】
-- V18 已在所有环境应用过，改它的任何字节都会触发 Flyway checksum 校验失败。
-- 因此口径纠正一律用本文件的 COMMENT ON 覆盖，V18 原文件保持只读。
--
-- 【裁决摘要】见 docs/ADR-Q27-担保权属权威源裁决.md
--   · V10 collateral_registration_batches + items = 唯一权威源（system of record）
--   · V18 三张表 = 只读投影 + 留痕，不参与放款门禁判定
--   · 门禁按 VIN 从 items → batches 取最新有效批次：
--     registration_no IS NOT NULL 且 cancellation_status NOT IN ('DONE','CANCELLED')
--     → REGISTERED；否则 NONE（fail-closed 拒付）
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- ① 修正 ownerName 字段错位
-- ---------------------------------------------------------------------------
-- V10 批次表原先没有「担保权人」专用列，CORE 曾把 ownerName 塞进 co_signed_by
-- （共同申请对方＝登记义务人＝经销商）。两者不是同一主体，属字段混用。
-- 现加专用列；co_signed_by 保留其本义，不再承载权利人。
ALTER TABLE collateral_registration_batches
    ADD COLUMN IF NOT EXISTS owner_name VARCHAR(256);

COMMENT ON COLUMN collateral_registration_batches.owner_name IS
    '担保权人（权利人）姓名；ADR-Q27 前曾被误写入 co_signed_by，现用本列。co_signed_by 仅表示共同申请对方（登记义务人＝经销商）';

-- ---------------------------------------------------------------------------
-- ② 用 COMMENT ON 纠正 V18 三表的口径声明
-- ---------------------------------------------------------------------------
-- V18 原注释写「Q27 定源前查询一律 fail-closed 返回 NONE」「权威表」「三源裁决」，
-- 与 ADR-Q27 裁决不符。这里覆盖表/列注释，使数据库自描述与实现一致。
COMMENT ON TABLE collateral_registration IS
    '担保权属投影表（只读）：由 V10 批次表 items→batches 同事务同步写入，供运营查询与审计；放款门禁不读本表，直接读 V10 权威源。见 ADR-Q27';

COMMENT ON COLUMN collateral_registration.status IS
    '投影状态：由 V10 有效登记批次推导（REGISTERED/NONE）；非独立判定口径，见 ADR-Q27';

COMMENT ON COLUMN collateral_registration.source IS
    '投影来源：固定 REGISTRATION_BATCH（V10 批次表）；见 ADR-Q27';

COMMENT ON TABLE collateral_registration_history IS
    '担保权属流水：V10 登记/抹消同事务追加，以及人工登记的留痕；只追加不更新。见 ADR-Q27';

COMMENT ON TABLE collateral_source_snapshot IS
    '担保三源快照：对账体检留痕。批量落库后只诊断一次、产出一致性结论，绝不 adopt 覆盖任何权属表。见 ADR-Q27';

COMMENT ON COLUMN collateral_source_snapshot.raw_payload IS
    '原始上报报文（JSONB）：对账争议时自证；见 ADR-Q27';

-- ---------------------------------------------------------------------------
-- ③ 门禁按 VIN 查 V10 有效批次：补一个覆盖索引，避免全表扫
-- ---------------------------------------------------------------------------
-- 查询形态：items.vin = ? JOIN batches ON id，按 registered_at DESC 取第一条有效。
-- items 已有 idx_cri_vin；这里给批次表加「有效登记」筛选的部分索引，
-- 让 cancellation_status 过滤与排序都能走索引。
CREATE INDEX IF NOT EXISTS idx_crb_effective_reg
    ON collateral_registration_batches (registered_at DESC, id DESC)
    WHERE registration_no IS NOT NULL
      AND cancellation_status NOT IN ('DONE', 'CANCELLED');

COMMENT ON INDEX idx_crb_effective_reg IS
    'ADR-Q27：放款门禁按 VIN 查有效登记批次的覆盖索引（未抹消且登记编号非空）';
