-- =====================================================================
-- V54__collateral_multi_source.sql
-- 用途：USED 域「权属权威源不止动产让渡登记一种」的落库改造（ADR-U-04 / ADR-Q27 兼容）。
-- 归属包：W-U1。**W-U4 的登记/放证侧依赖本文件**（它读 authoritative_source_type 与 certificate_id）。
-- 定源：D04 §2 批次计划第 V54 行 + §5.2 + §7 + §10。
--
-- 【不改 V18/V10/V25 文件本身】它们已上线，改字节会炸 checksum（V25 头注同款理由）。
--   口径变更一律 COMMENT ON 覆盖；只加限定语、不删原注释内容（D04 §12.8）。
--
-- 【为什么这里不建新表】「多源」= 权威源类型变多，不是投影表变多。
--   ADR-Q27 已定：唯一权威源 = V10 批次表，V18 三表只是只读投影。
--   若为多源再建一张投影表，就等于出现第二个可被写入的权属结论位，Q21 门禁读哪一份又成悬案。
--   所以本文件的形态是「批次表加列 + 结论回填 + 注释覆盖」，投影链路一条不加。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. collateral_registration_batches 加列（D04 §5.2）
-- ---------------------------------------------------------------------
-- 权威源类型：现状只有「法务省动産譲渡担保登記」一条路；USED 域多了
-- 車検証持有 / 自動車登録ファイル / 抵当権設定 三种可采信来源（D00 §8.2）。
-- 本列记的是**这一批次当初凭哪种来源成立**，不是全局开关。
ALTER TABLE collateral_registration_batches
    ADD COLUMN IF NOT EXISTS authoritative_source_type VARCHAR(32);

-- 权利瑕疵聚合结论（批次级；单车结论仍读 certificate_custody_events / V52 明细）
ALTER TABLE collateral_registration_batches
    ADD COLUMN IF NOT EXISTS encumbrance_conclusion VARCHAR(24);

-- 批次 ↔ 車検証 的显式关联（R5.1 与登记事实要对得上，W-U4 放证线要用）
ALTER TABLE collateral_registration_batches
    ADD COLUMN IF NOT EXISTS certificate_id BIGINT;

ALTER TABLE collateral_registration_batches
    DROP CONSTRAINT IF EXISTS fk_crb_certificate;

ALTER TABLE collateral_registration_batches
    ADD CONSTRAINT fk_crb_certificate FOREIGN KEY (certificate_id)
        REFERENCES inspection_certificates (id);

-- 取值域 = D00 §8.2 AuthoritativeSourceType 四值 ＋ 'NONE'。
-- ⚠ 'NONE' 不在 D00 §8.2 的枚举表里，但 D04 §10 的回填规则明确要求「无批次行/已抹消 → NONE」，
--   两者只能取其一：本文件按 **D04 §10（回填可执行性优先）** 落 'NONE'，
--   并把它登记为需收口方在 D00 §8.2 补值的口径缺口（见实现说明"文档冲突"节）。
--   NULL 与 NONE 语义不同：NULL=本列从未回填（迁移未跑完的中间态），NONE=回填后判定为无权威来源。
ALTER TABLE collateral_registration_batches
    DROP CONSTRAINT IF EXISTS ck_crb_auth_source;

ALTER TABLE collateral_registration_batches
    ADD CONSTRAINT ck_crb_auth_source CHECK (authoritative_source_type IS NULL
        OR authoritative_source_type IN ('MOVABLE_REGISTRATION', 'INSPECTION_CERT',
                                         'VEHICLE_REGISTRY', 'MORTGAGE_REGISTRATION', 'NONE'));

ALTER TABLE collateral_registration_batches
    DROP CONSTRAINT IF EXISTS ck_crb_encumbrance;

ALTER TABLE collateral_registration_batches
    ADD CONSTRAINT ck_crb_encumbrance CHECK (encumbrance_conclusion IS NULL
        OR encumbrance_conclusion IN ('NONE', 'MORTGAGE', 'OWNERSHIP_RESERVATION',
                                      'ATTACHMENT', 'THEFT_REPORT', 'UNKNOWN'));

-- ⚠ D04 §5.2 还要求加 `rank_no INT（顺位）`，本文件**不建这一列**，理由与 D13 G15 对
--   dealers.business_years 的裁决同型：V10:155 已有 `priority_rank INTEGER`，注释即「顺位
--   （1 为优先；新法按对抗要件具备日定序）」——两列同义必然导致取数分叉。
--   正确做法是把既有条款声明为唯一顺位载体（下面的 COMMENT ON），
--   「是否改用 rank_no 替换 priority_rank」属破坏性变更，需签认后另起迁移，本包不自决。
COMMENT ON COLUMN collateral_registration_batches.priority_rank IS
    '顺位（V10 原注：1 为优先；新法按对抗要件具备日定序）。'
    || 'USED 多源并存时**仍以本列为唯一顺位载体**（D04 §5.2 草案的 rank_no 与它同义，已取消，见 V54 头注）。'
    || '顺位规则本身（哪种来源优先）[待定源 U-02/D-03]，本包不写任何比较逻辑。';

COMMENT ON COLUMN collateral_registration_batches.authoritative_source_type IS
    '本批次的权属权威源类型（D00 §8.2 + D04 §10 的 NONE）：'
    || 'MOVABLE_REGISTRATION=法务省动産譲渡担保登記（LEGACY 唯一来源）；INSPECTION_CERT=車検証持有；'
    || 'VEHICLE_REGISTRY=自動車登録ファイル；MORTGAGE_REGISTRATION=抵当権設定；NONE=回填后判定为无有效来源。'
    || '★ Q21 硬门禁读本列时**不得**绕过批次有效性（registration_no 非空 + cancellation_status 未抹消），'
    || '本列是"来源种类"而不是"是否已登记"——把它当门禁结论用就是第二权威源（违反 ADR-Q27）。';
COMMENT ON COLUMN collateral_registration_batches.encumbrance_conclusion IS
    '批次级权利瑕疵聚合结论（EncumbranceType）。明细在 certificate_custody_events；'
    || '除 NONE 外任一项硬阻断质押（P-7）。UNKNOWN 按不通过处理，不得当"没问题"。';
COMMENT ON COLUMN collateral_registration_batches.certificate_id IS
    '本批次对应的車検証（inspection_certificates.id，V52）。逻辑关联用物理 FK：'
    || '同一上下文内（担保域），且 W-U4 的放证线需要按证反查批次。';

-- ---------------------------------------------------------------------
-- 2. security_records 加列：USED 域三种设定事实（D04 §5.2）
-- ---------------------------------------------------------------------
-- 为什么用三个布尔位而不是塞进 security_type：一辆二手车可以同时
-- 「抵当権設定 + 名義変更 + 車検証保管」，单值枚举表达不了并存。
ALTER TABLE security_records ADD COLUMN IF NOT EXISTS mortgage_flag     BOOLEAN;
ALTER TABLE security_records ADD COLUMN IF NOT EXISTS title_transfer_flag BOOLEAN;
ALTER TABLE security_records ADD COLUMN IF NOT EXISTS cert_custody_flag   BOOLEAN;

COMMENT ON COLUMN security_records.mortgage_flag IS '抵当権设定事实（R5.2，USED 域新增）。NULL=LEGACY 存量行从未判定，按 false 处理但不得回填伪造 false 之外的值';
COMMENT ON COLUMN security_records.title_transfer_flag IS '移転登録（名义变更）事实：让渡担保型的是非设定，与担保登记并见时须报差异';
COMMENT ON COLUMN security_records.cert_custody_flag IS '車検証保管事实（SecurityType.INSPECTION_CERT_CUSTODY 的设定侧投影）';

-- ---------------------------------------------------------------------
-- 3. financing_lien_checks 取值收紧（D04 §5.2 + §7）
-- ---------------------------------------------------------------------
-- 现状 V2:13 该列**没有任何 CHECK**（自由字符串）。按 D04 §6「CHECK 必须包含存量已有值，
-- 否则迁移整体失败」：先实查存量取值再建约束——本表是登记核查留痕，
-- 已知取值 {MANUAL, OEM_PORTAL, REGISTRY_API}（V2:13 注释），USED 新增 {REGISTRY_AGENT}。
-- 保留 OEM_PORTAL 兼容存量行（USED 域新写入禁用；但存量行不能被新约束打死）。
--
-- ⚠ D04 §5.2 要求「条件 CHECK `WHEN vehicle_type='USED'`」，DDL 层做不到：
--   CHECK 只能引用本行同表列，而 vehicle_type 在 vehicle_units（跨表谓词需触发器或应用层）。
--   本文件因此只建全值 CHECK，USED 侧禁写 OEM_PORTAL 由应用层保证（属 W-U4 的登记核查写入路径）。
--   若一定要数据库层保证，需给本表加 vehicle_type 冗余列或写触发器——两者都超出本包号段口径，已上报。
ALTER TABLE financing_lien_checks
    DROP CONSTRAINT IF EXISTS ck_lien_check_source;

-- NOT VALID 是刻意选择，不是偷懒：check_source 现状是**自由字符串**
-- （LienCheckApplicationService:42 只判空取默认 MANUAL，risk-service 端点把请求体原样写入），
-- 存量取值集合本轮无法实查（无库访问权限）。直接 VALIDATE 会让一个 unseen 旧值炸掉整条迁移链
-- （AGENTS §12 反模式第 8 条）。NOT VALID 的效果是：**新写入立刻受约束，历史脏值不被追溯**。
-- 收口方在全量验证时按实查结果补 `VALIDATE CONSTRAINT`（已写进实现说明的收尾清单）。
-- 既有冒烟只用 MANUAL（smoke-test.py:451-452），故本约束不会打断 L 组回归。
ALTER TABLE financing_lien_checks
    ADD CONSTRAINT ck_lien_check_source CHECK (check_source IN
        ('MANUAL', 'OEM_PORTAL', 'REGISTRY_API', 'REGISTRY_AGENT')) NOT VALID;

COMMENT ON COLUMN financing_lien_checks.check_source IS
    '登记核查来源（V2:13 原义：MANUAL / OEM_PORTAL / REGISTRY_API）。'
    || 'USED 域限定（D04 §7）：USED 域取值限 MANUAL / REGISTRY_AGENT / REGISTRY_API；'
    || 'OEM_PORTAL 属 LEGACY（厂家门户查不到二手车权属），USED 域新写入禁用——'
    || '该条是应用层约束，DDL CHECK 无法跨表判 vehicle_type（见上方说明）。';

-- ---------------------------------------------------------------------
-- 4. 回填（D04 §10 V54 行：唯一允许 UPDATE 的场景，且必须留审计）
-- ---------------------------------------------------------------------
-- 为什么必须回填：Q21 硬门禁一旦开始读 authoritative_source_type，
-- 未回填 = NULL = 读成"无权威来源" = 存量在贷车辆全量拒付。
-- 这正是 AGENTS §8 里 guarantee-gate 默认关闭所担心的炸法，不能由新列提前引爆。
--
-- 规则（照抄 D04 §10，不自造）：
--   · 有有效批次（登记编号非空 + 未抹消）→ MOVABLE_REGISTRATION（现状唯一来源，不新造值）
--   · 已抹消 / 无登记编号的批次        → NONE（判"无有效来源"，fail-closed）
--   · **绝不凭空造 REGISTERED 结论**：伪造权属比留 NONE 严重得多（D04 §10 红线）
-- 顺序：先回填 MOVABLE_REGISTRATION，再兜底 NONE，两批各留一条 DATA_BACKFILL 审计。
WITH backfilled AS (
    UPDATE collateral_registration_batches
       SET authoritative_source_type = 'MOVABLE_REGISTRATION'
     WHERE authoritative_source_type IS NULL
       AND registration_no IS NOT NULL
       AND cancellation_status NOT IN ('DONE', 'CANCELLED')
    RETURNING id
),
counts AS (
    SELECT count(*) AS n FROM backfilled
)
INSERT INTO audit_events (actor, actor_role, action, target_type, target_id,
                          before_snapshot, after_snapshot, trace_id)
SELECT 'system:migration', NULL, 'DATA_BACKFILL', 'COLLATERAL_BATCH', 'V54:authoritative_source_type',
       'authoritative_source_type IS NULL AND 有效登记批次',
       'MOVABLE_REGISTRATION, rows=' || n,
       'migration:V54'
  FROM counts
 WHERE n > 0;

WITH backfilled AS (
    UPDATE collateral_registration_batches
       SET authoritative_source_type = 'NONE'
     WHERE authoritative_source_type IS NULL
    RETURNING id
),
counts AS (
    SELECT count(*) AS n FROM backfilled
)
INSERT INTO audit_events (actor, actor_role, action, target_type, target_id,
                          before_snapshot, after_snapshot, trace_id)
SELECT 'system:migration', NULL, 'DATA_BACKFILL', 'COLLATERAL_BATCH', 'V54:authoritative_source_type',
       'authoritative_source_type IS NULL AND 无有效登记批次',
       'NONE, rows=' || n,
       'migration:V54'
  FROM counts
 WHERE n > 0;

-- 回填完成前，guarantee-gate 必须保持关闭（AGENTS §8 红线）。
-- 本迁移**不碰任何 yml / 配置开关**：开关的启停属部署与签认动作，不属包内自决。
COMMENT ON TABLE collateral_registration_batches IS
    '担保登记批次：一批车一次法务局登记，共享一个登记编号与一份担保合同（V10 原义）。'
    || 'USED 多源改造（V54 / ADR-U-04）后，本表仍是**唯一权威源**（ADR-Q27 不变），'
    || '只是多了 authoritative_source_type 说明"这批凭什么来源成立"；'
    || '门禁判定条件不变：registration_no IS NOT NULL 且 cancellation_status NOT IN (''DONE'',''CANCELLED'')，'
    || '再叠 encumbrance_conclusion 的瑕疵阻断。';

-- V18 投影表口径再覆盖一次（V25 已改过一轮，这里只补 USED 多源后的增量语义）
COMMENT ON COLUMN collateral_registration.status IS
    '投影状态：由 V10 有效登记批次推导（REGISTERED/NONE）；非独立判定口径，见 ADR-Q27'  -- V25:54 原文保留
    || '。USED 多源后：投影仍只反映"登记类来源是否成立"，'
    || '新增来源类型（INSPECTION_CERT / VEHICLE_REGISTRY / MORTGAGE_REGISTRATION）**是否进投影值域**'
    || '[待定源 U-02] 未签，签认前本表值域与既有断言一律不变。';
