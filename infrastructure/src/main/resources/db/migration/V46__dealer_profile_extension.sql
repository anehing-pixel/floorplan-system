-- =====================================================================
-- V46__dealer_profile_extension.sql
-- 用途：R1.1 车商档案补列 + R1.3 双维评级落点 + R1.5 准入态落点。
-- 归属包：W-U1。定源：D04 §5.2（dealers 行）+ D03 §2.1（AdmissionState）+ D06 §1.2/§1.3/§1.4。
-- 纪律：只加列、不改不删既有列；V1–V26 字节不变（本文件是新迁移，不动 V6 的既有定义）。
--       存量行（老车商）新列一律 NULL，故所有 CHECK 必须允许 NULL（D04 §5.1 同一取舍）。
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

COMMENT ON COLUMN dealers.operating_score IS 'R1.3 经营资质分（資本金/年商/展位/年限/整备资质的合成分）。'
    || '⚠ 只有 dealer_score_reviews.status=CONFIRMED 的第二人确认终值才允许回写本列（G-A4）；候选值不得写这里。';
COMMENT ON COLUMN dealers.stock_quality_score IS 'R1.3 在库车辆质量分（年式/里程/修復歴/評価点加权，StockQualityScoreCalculator）。'
    || '分档口径与权重 [待定源 U-11/D-06]：本包只存分，不实现加权算法，也不把分数换算成任何额度或 LTV 幅度。';
COMMENT ON COLUMN dealers.margin_deposit IS 'R1.1 保证金/敷金（JPY 整数最小单位）。是否强制、最低额 [待定源]，本包不设 CHECK。';
COMMENT ON COLUMN dealers.license_status IS '许可状态**只读投影**（列表页免 join 用）。判定一律读 dealer_licenses.status，两者不一致时以明细表为准并报差异。';
COMMENT ON COLUMN dealers.license_expiry_date IS '到期日投影，同上；NULL=无有效期或未采集（两种事实不合并，见 D04 §5.1 里程同款口径）。';
COMMENT ON COLUMN dealers.admission_state IS 'R1.5 准入态（D03 §2.1 五值）。写入口只有 UC-R1-4 recalculateAdmission（状态经领域方法流转，禁止直接 UPDATE 本列）。'
    || 'NULL=未评估，闸门按 QUAL_001 拒（fail-closed）。**无白名单旁路**——本列不存在"人工置为 ADMITTED"的入口（D03 §2.1 明文）。';
COMMENT ON COLUMN dealers.furigusho_no IS '古物商許可番号摘要（明细与证据在 dealer_licenses，D04 §3.1）';
COMMENT ON COLUMN dealers.capital_amount_jpy IS '資本金（JPY 整数）';
COMMENT ON COLUMN dealers.annual_revenue_jpy IS '年销售额（JPY 整数）';
COMMENT ON COLUMN dealers.storage_capacity IS '展位·在库容量（台数）';
COMMENT ON COLUMN dealers.service_cert_type IS '整备·车检资质类型；取值集需求书未定源，故本列不建 CHECK（不自造枚举）';
COMMENT ON COLUMN dealers.service_cert_no IS '整备·车检资质编号';
COMMENT ON COLUMN dealers.service_cert_expires_on IS '整备·车检资质到期日';

-- ---------------------------------------------------------------------
-- 2. dealer_score_reviews 双维评级评审流水（R1.3 二人确认的载体）
-- ---------------------------------------------------------------------
-- 为什么必须建表（D04 §5.2 只给 dealers 两个分数列，但那张表放不下 SOD 证据）：
--   D06 §1.2 的签名是 confirmScores(dealerId, candidateId, operatorA, operatorB)——
--   candidateId 要求候选评分必须先落库、可被第二人单独确认，否则：
--     · 幂等键 DSCR:{dealerId}:{ts}（D00 §13.1）没有唯一约束兜底；
--     · G-A4「两维均为第二人确认后的终值」没有可核查的证据行，AUTH_003 无从判定；
--     · 「改分」与「谁改的」只能塞进 audit_events 的自由文本，事后不可复核。
--   因此本包在此号段补这张表。**这是超出 D04 §2.1 计数的新增 public 表**：
--   本包实际新建 8 张（卡面 6 张 + 本表 + V41 used_config_params），
--   W-U1 分支 FLOORPLAN_PUBLIC_TABLES 应为 **75**（67+8），不是 D12 §3 卡面写的 73。
--   若收口方不认可本表，需另指派号段或删除，并同步 D04 §2.1 与 D12 §3 卡面门槛——本包不自决（已上报）。
CREATE TABLE IF NOT EXISTS dealer_score_reviews (
    id                   BIGSERIAL   PRIMARY KEY,
    dealer_id            BIGINT      NOT NULL REFERENCES dealers (id),
    review_year          INT         NOT NULL,
    operating_score      INT         NOT NULL,
    stock_quality_score  INT         NOT NULL,
    status               VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    scored_by            VARCHAR(64) NOT NULL,
    confirmed_by         VARCHAR(64),
    evidence_path        VARCHAR(512),
    note                 VARCHAR(500),
    idempotency_key      VARCHAR(96),
    request_fingerprint  VARCHAR(64),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    version              INT         NOT NULL DEFAULT 0,
    CONSTRAINT ck_dsr_status CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED'))
);

-- DSCR:{dealerId}:{ts} 的数据库兜底：同键重放只产生一行候选
CREATE UNIQUE INDEX IF NOT EXISTS uk_dsr_idem
    ON dealer_score_reviews (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
-- 同一车商同一评审年度只允许一条在途候选（防并发双开；CONFIRMED/CANCELLED 历史可多条）
CREATE UNIQUE INDEX IF NOT EXISTS uk_dsr_pending
    ON dealer_score_reviews (dealer_id, review_year)
    WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_dsr_dealer
    ON dealer_score_reviews (dealer_id, status, review_year DESC);

COMMENT ON TABLE dealer_score_reviews IS 'R1.3 双维评级评审流水（候选 → 第二人确认）。'
    || 'PENDING 行的分数不回写 dealers；只有 CONFIRMED 才由应用层在同一事务内 CAS 更新 dealers 两列并写审计。'
    || 'scored_by 与 confirmed_by 不得为同一自然人（RoleSeparationPolicy.assertNoSelfApproval → AUTH_003）。';
COMMENT ON COLUMN dealer_score_reviews.review_year IS '评审年度（与年审周期对齐；周期起止 [待定源]，本列只存年份不做区间推算）';
COMMENT ON COLUMN dealer_score_reviews.confirmed_by IS '第二人（确认人）；NULL 表示尚未确认，此时 G-A4 判定为不通过';
COMMENT ON COLUMN dealer_score_reviews.request_fingerprint IS '请求内容 SHA-256：同 idempotency_key 但内容不同 → CONFLICT_001（沿用既有幂等口径，不自造新错误码）';
