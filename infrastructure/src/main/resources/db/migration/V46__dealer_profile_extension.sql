-- =====================================================================
-- V46__dealer_profile_extension.sql
-- 用途：R1.1 车商档案补列 + R1.3 双维评级落点 + R1.5 准入态落点。
-- 归属包：W-U1。定源：D04 §5.2（dealers 行）+ D03 §2.1（AdmissionState）+ D06 §1.2/§1.3/§1.4。
-- 纪律：只加列、不改不删既有列；V1–V26 字节不变（本文件是新迁移，不动 V6 的既有定义）。
--       存量行（老车商）新列一律 NULL，故所有 CHECK 必须允许 NULL（D04 §5.1 同一取舍）。
-- 2026-11-22 R12 裁决：本文件**不建新表**。原第 2 段的 dealer_score_reviews 已删除
--       （评审流水不是业务对象，留痕点用已有的 audit_events），R1.3 的库内落点只剩
--       dealers 两个分数列；证据链见第 2 段的裁决记录。本文件因此是纯 ALTER/COMMENT，
--       对既有 schema 幂等（重复执行不产生新对象）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. dealers 扩展列（D04 §5.2 逐列照抄，不增不减不改名）
-- ---------------------------------------------------------------------

-- R1.3 双维评级：经营资质分 + 在库车辆质量分（D06 §1.4 三口径表）
ALTER TABLE dealers ADD COLUMN IF NOT EXISTS operating_score      INT;
ALTER TABLE dealers ADD COLUMN IF NOT EXISTS stock_quality_score  INT;
-- R1.1 保证金（金额一律 BIGINT 整数最小单位，禁浮点）
ALTER TABLE dealers ADD COLUMN IF NOT EXISTS margin_deposit       BIGINT;
-- R1.1 许可摘要：明细在 dealer_licenses，本二列只是列表页投影
ALTER TABLE dealers ADD COLUMN IF NOT EXISTS license_status       VARCHAR(16);
ALTER TABLE dealers ADD COLUMN IF NOT EXISTS license_expiry_date  DATE;
-- R1.5 准入态（UC-R1-4 重算的落点，是放款闸门的输入）
ALTER TABLE dealers ADD COLUMN IF NOT EXISTS admission_state      VARCHAR(24);
ALTER TABLE dealers ADD COLUMN IF NOT EXISTS furigusho_no         VARCHAR(64);
-- R1.1 档案补列（2026-11-22 D13 §3 G15 回灌）
ALTER TABLE dealers ADD COLUMN IF NOT EXISTS capital_amount_jpy       BIGINT;
ALTER TABLE dealers ADD COLUMN IF NOT EXISTS annual_revenue_jpy       BIGINT;
ALTER TABLE dealers ADD COLUMN IF NOT EXISTS storage_capacity         INT;
ALTER TABLE dealers ADD COLUMN IF NOT EXISTS service_cert_type        VARCHAR(24);
ALTER TABLE dealers ADD COLUMN IF NOT EXISTS service_cert_no          VARCHAR(64);
ALTER TABLE dealers ADD COLUMN IF NOT EXISTS service_cert_expires_on  DATE;

-- 准入态取值集 = D03 §2.1 DealerAdmissionPolicy 的结论枚举（与 domain AdmissionState 同名）。
-- 无 NOT NULL：存量行从未算过准入态，NULL 表示"未评估"，判定侧按 fail-closed 处理（不许默认放行）。
-- 写法沿用 V11:19-24 的 DROP/ADD CONSTRAINT 成对模式（PG 的 ADD CONSTRAINT 没有 IF NOT EXISTS）。
ALTER TABLE dealers
    DROP CONSTRAINT IF EXISTS ck_dealers_admission_state;

ALTER TABLE dealers
    ADD CONSTRAINT ck_dealers_admission_state CHECK (admission_state IS NULL
        OR admission_state IN ('ADMITTED', 'BLOCKED_LICENSE', 'BLOCKED_COMPLIANCE',
                               'BLOCKED_MEMBERSHIP', 'EXPIRING'));

COMMENT ON COLUMN dealers.operating_score IS 'R1.3 经营资质分（資本金/年商/展位/年限/整备资质的合成分）。
    ⚠ 只有 G-A4 二人签认通过后的终值才允许回写本列；录入而未签认的候选值不得写这里（2026-11-22 R12 裁决：
    原拟的 dealer_score_reviews 流水表已撤销，签认证据改落 audit_events 一行，见 V46 头注）。';
COMMENT ON COLUMN dealers.stock_quality_score IS 'R1.3 在库车辆质量分（年式/里程/修復歴/評価点加权，StockQualityScoreCalculator）。
    分档口径与权重 [待定源 U-11/D-06]：本包只存分，不实现加权算法，也不把分数换算成任何额度或 LTV 幅度。
    回写条件与 operating_score 同款：G-A4 二人签认通过后的终值才写本列（R12 裁决，签认证据在 audit_events）。';
COMMENT ON COLUMN dealers.margin_deposit IS 'R1.1 保证金/敷金（JPY 整数最小单位）。是否强制、最低额 [待定源]，本包不设 CHECK。';
COMMENT ON COLUMN dealers.license_status IS '许可状态**只读投影**（列表页免 join 用）。判定一律读 dealer_licenses.status，两者不一致时以明细表为准并报差异。';
COMMENT ON COLUMN dealers.license_expiry_date IS '到期日投影，同上；NULL=无有效期或未采集（两种事实不合并，见 D04 §5.1 里程同款口径）。';
COMMENT ON COLUMN dealers.admission_state IS 'R1.5 准入态（D03 §2.1 五值）。写入口只有 UC-R1-4 recalculateAdmission（状态经领域方法流转，禁止直接 UPDATE 本列）。
    NULL=未评估，闸门按 QUAL_001 拒（fail-closed）。**无白名单旁路**——本列不存在"人工置为 ADMITTED"的入口（D03 §2.1 明文）。';
COMMENT ON COLUMN dealers.furigusho_no IS '古物商許可番号摘要（明细与证据在 dealer_licenses，D04 §3.1）';
COMMENT ON COLUMN dealers.capital_amount_jpy IS '資本金（JPY 整数）';
COMMENT ON COLUMN dealers.annual_revenue_jpy IS '年销售额（JPY 整数）';
COMMENT ON COLUMN dealers.storage_capacity IS '展位·在库容量（台数）';
COMMENT ON COLUMN dealers.service_cert_type IS '整备·车检资质类型；取值集需求书未定源，故本列不建 CHECK（不自造枚举）';
COMMENT ON COLUMN dealers.service_cert_no IS '整备·车检资质编号';
COMMENT ON COLUMN dealers.service_cert_expires_on IS '整备·车检资质到期日';

-- ---------------------------------------------------------------------
-- 2. （已撤销）dealer_score_reviews 双维评级评审流水
-- ---------------------------------------------------------------------
-- 2026-11-22 R12 裁决：本表删除，不在本号段建。
--   裁决理由（记录在此，免得后人在 dealers 两列上又"顺手补一张流水表"）：
--     · D04 §2.1 定的是 W-U1 = 6 张 public 表，全仓六处引用均按 6 张算；
--       本表在 docs/ 目录 0 命中，属实现自加，其列形状未经评审。
--     · D06 §1.2（原文 :67）的 G-A4 四道检查要有地方留痕，但留痕点用**已有的
--       audit_events**，不新建表——评级动作不是业务对象，是一步操作日志。
--   落地影响（本包据此调整，不在库侧留半截）：
--     · 双维评级的候选→签认证据改由 audit_events 的 action/target_type/target_id
--       + before/after 快照承载（操作人取自 AuditActor，不从请求体读）；
--     · 原拟在本表上的 uk_dsr_idem（幂等唯一）／uk_dsr_pending（同年度单条在途）
--       两个库侧兜底随之消失，防重放只剩应用层，缺口已在实现说明中如实登记；
--     · dealers.operating_score／stock_quality_score 两列保留（D04 §5.2 原口径），
--       仍是"第二人签认通过后的终值"才允许回写。
-- 本段仅剩 dealers 列扩展，无新增表 ⟹ W-U1 新建 public 表数回到 D04 §2.1 的 6 张
-- （V45 3 张 + V51 1 张 + V52 2 张）；V41 used_config_params 是 §2.1 明文的
-- 「若落 DB 参数表则 +1」条件项，是否计入由收口方按同一条文裁，本包不再自改门槛。
-- ---------------------------------------------------------------------
