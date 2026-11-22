-- =====================================================================
-- V45__dealer_qualification.sql
-- 用途：R1.1 车商资质档案（古物商許可 / 拍卖场会员）+ R1.2 合规核查留痕。
-- 归属包：W-U1（二手车库存垫资改造）。定源：D04 §3.1 / §3.2 / §3.3。
-- 落地前置（开工门禁，见 D13 §7 W-U1 行）：
--   · G15 表名逐字照抄——本文件**不建** registration_applications（那属 W-U4 / V69）；
--   · G1 「谁写这些表」未收口：D06 §1.1 把 UC-R1-1/2 标在 web，D07 §7.1 写 `web`
--     而同节路由表把 /api/dealers/** 标为 credit-service ——本轮**按任务卡落 web(core)**，
--     网关 yml 不改（本包无写权限），差异已在实现说明里上报。
-- 纪律：幂等（IF NOT EXISTS）；不建空文件；无 DROP / DELETE / TRUNCATE；
--       部分唯一索引一律表外 CREATE UNIQUE INDEX ... WHERE（表内 UNIQUE(...) WHERE 非法，V19 教训）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. dealer_licenses 车商许可证（R1.1，D04 §3.1）
-- ---------------------------------------------------------------------
-- 业务口径：日本二手车交易的入场券是「古物商許可」（营业許可证），由都道府县公安委员会颁发。
-- 门禁 G-A1（D06 §1.3）读的就是本表：status='ACTIVE' 且（expires_on IS NULL 或 >= 今天）。
-- expires_on 允许 NULL 是业务事实——古物商许可本身**没有有效期**，是否强制填 [待定源 D-02]，
-- 故这里不写「必须有到期日」的 CHECK，也不由本包猜测到期判定天数（判定见 domain，无超时猜测）。
CREATE TABLE IF NOT EXISTS dealer_licenses (
    id                  BIGSERIAL    PRIMARY KEY,
    dealer_id           BIGINT       NOT NULL REFERENCES dealers (id),
    license_type        VARCHAR(32)  NOT NULL,
    license_no          VARCHAR(64)  NOT NULL,
    competent_authority VARCHAR(128) NOT NULL,
    issued_on           DATE         NOT NULL,
    expires_on          DATE,
    status              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    evidence_path       VARCHAR(512),
    evidence_digest     VARCHAR(64),
    verified_by         VARCHAR(64),
    verified_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          VARCHAR(64),
    updated_by          VARCHAR(64),
    -- 枚举双层约束（R13.4）：Java 枚举 DealerLicenseType + 此处 CHECK
    CONSTRAINT ck_dlic_license_type CHECK (license_type IN ('FURIGUSHO')),
    CONSTRAINT ck_dlic_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'REVOKED', 'SUPERSEDED'))
);

-- 本表唯一约束有两道，兜的是两件不同的事，缺一都留缺口：
--
-- ① 自然键唯一（本条）：dealer_id + license_type + license_no 全表唯一。
--    这是幂等键 DLIC:{dealerId}:{type}:{no} 的数据库兜底——同一番号重放必然命中同一行，
--    应用层据此做"已存在则 CAS 更新"，不会插出两行同番号事实。
--    为什么不能只靠②：②只约束 ACTIVE 行，若同一番号先被置为 SUPERSEDED 再重放录入，
--    ②不拦（新旧两行 status 不同），于是"一车商一type一番号"出现两条历史行，
--    重放的 upsert 探测（findLicense）无从定位，幂等口径失效。
CREATE UNIQUE INDEX IF NOT EXISTS uk_dlic_dealer_type_no
    ON dealer_licenses (dealer_id, license_type, license_no);
-- ② 有效性唯一：一 dealer 一 type 同时只允许一张 ACTIVE 证。
--    拦的是"业务事实冲突"——换证漏改旧行、两个番号同时声称有效（G-A1 判定会二义）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_dealer_license_active
    ON dealer_licenses (dealer_id, license_type)
    WHERE status = 'ACTIVE';
-- 到期预警作业用（只扫有效证，避免全表膨胀）
CREATE INDEX IF NOT EXISTS idx_license_expiry
    ON dealer_licenses (expires_on)
    WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_dlic_dealer ON dealer_licenses (dealer_id);

COMMENT ON TABLE dealer_licenses IS '车商（借款人）许可证档案（R1.1 古物商营业許可）：明细分层，dealers.furigusho_no 只存摘要。'
    || '写入口=web(core) DealerQualificationController，权限点 DEALER_QUALIFICATION_MAINTAIN（D07 §7.1）。'
    || '「无有效期」[待定源 D-02] 未签：expires_on 允许 NULL，到期天数口径不得在本包猜测。';
COMMENT ON COLUMN dealer_licenses.license_type IS '许可类型，本期仅 FURIGUSHO=古物商（D04 §3.1 预留扩展位，扩展值需业务定源后加入 CHECK）';
COMMENT ON COLUMN dealer_licenses.competent_authority IS '管辖公安委员会（都道府县），是核查与函证的对接对象';
COMMENT ON COLUMN dealer_licenses.expires_on IS '许可满了日；NULL=业务上无有效期（古物商许可无期限说，[待定源 D-02] 未签前不得当作到期风险）';
COMMENT ON COLUMN dealer_licenses.status IS 'ACTIVE/EXPIRED/REVOKED/SUPERSEDED；SUPERSEDED=被换证取代，与换证链同义（D03 §2.1）';
COMMENT ON COLUMN dealer_licenses.verified_by IS '人工核查人（G-A1 复核留痕，对应审计动作 COMPLIANCE_CHECK_DONE 的主体来源之一）';
COMMENT ON COLUMN dealer_licenses.evidence_path IS '扫描件本地路径（U-R12：一期不接对象存储）；digest 为 SHA-256，用于事后验真';

-- ---------------------------------------------------------------------
-- 2. dealer_auction_memberships 拍卖场会员关系（R1.1，D04 §3.2）
-- ---------------------------------------------------------------------
-- 验收点「会员 ID 按场分别维护」= 本表主键语义：一个车商在 UUSS / TAA / IAA 各是一条独立记录，
-- 不能压成 dealers 上的一个列。门禁 G-A2 判「申请所用场次是否存在 ACTIVE 会员」。
-- auction_house_code 为**逻辑引用**：物理表 auction_houses 属 V60 / W-U4，跨包跨上下文，
-- 不在本迁移建 FK（D04 §1.1 只在同上下文建 FK；引用完整性由 W-U4 侧与对账作业保证）。
CREATE TABLE IF NOT EXISTS dealer_auction_memberships (
    id                 BIGSERIAL   PRIMARY KEY,
    dealer_id          BIGINT      NOT NULL REFERENCES dealers (id),
    auction_house_code VARCHAR(32) NOT NULL,
    member_no          VARCHAR(64) NOT NULL,
    member_grade       VARCHAR(32),
    joined_on          DATE,
    expires_on         DATE,
    status             VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    evidence_path      VARCHAR(512),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by         VARCHAR(64),
    updated_by         VARCHAR(64),
    -- 全表唯一（非部分索引）：DMEM:{dealerId}:{house}:{memberNo} 的兜底
    CONSTRAINT uk_dmem_dealer_house_no UNIQUE (dealer_id, auction_house_code, member_no),
    CONSTRAINT ck_dmem_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'EXPIRED', 'TERMINATED'))
);

-- G-A2 / D08 §4.1 G2 的高频查询：按（车商，场次）取 ACTIVE 会员
CREATE INDEX IF NOT EXISTS idx_dmem_dealer_house
    ON dealer_auction_memberships (dealer_id, auction_house_code, status);

COMMENT ON TABLE dealer_auction_memberships IS '车商在各拍卖场的会员关系（R1.1：按场分别维护，一个场次一条记录）。'
    || 'auction_house_code 逻辑引用 auction_houses（V60 / W-U4），本包不建物理 FK。';
COMMENT ON COLUMN dealer_auction_memberships.member_grade IS '会员分级（影響 出品制限与 LTV 上浮），分级口径 [待定源]：本列只存代码，不在本包推导任何上浮幅度';
COMMENT ON COLUMN dealer_auction_memberships.status IS 'ACTIVE/SUSPENDED/EXPIRED/TERMINATED；只有 ACTIVE 参与 G-A2 判定';

-- ---------------------------------------------------------------------
-- 3. dealer_compliance_checks 合规核查记录（R1.2，D04 §3.3）
-- ---------------------------------------------------------------------
-- 四类核查（反社会势力 / AML / 本人确认 / 征信）。G-A3 要求四类**都有未过期 PASS**，
-- 且 UNKNOWN 按不通过处理（D00 原则 P-7）——该取严判定写在 domain 的 DealerAdmissionPolicy，
-- 不在本表用 CHECK 假装约束（CHECK 只管取值合法，不管业务结论）。
-- 外部（I-11）入站写本表的通路属 R11/W-U6，本包只开放内部录入端点 + 幂等键 request_no。
CREATE TABLE IF NOT EXISTS dealer_compliance_checks (
    id            BIGSERIAL   PRIMARY KEY,
    dealer_id     BIGINT      NOT NULL REFERENCES dealers (id),
    check_type    VARCHAR(24) NOT NULL,
    checked_on    DATE        NOT NULL,
    valid_until   DATE,
    result        VARCHAR(16) NOT NULL,
    source        VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    request_no    VARCHAR(64),
    evidence_path VARCHAR(512),
    note          VARCHAR(500),
    checked_by    VARCHAR(64),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    VARCHAR(64),
    updated_by    VARCHAR(64),
    CONSTRAINT ck_dchk_type CHECK (check_type IN ('ANTI_SOCIAL', 'AML', 'IDENTITY', 'CREDIT_BUREAU')),
    CONSTRAINT ck_dchk_result CHECK (result IN ('PASS', 'FAIL', 'UNKNOWN')),
    CONSTRAINT ck_dchk_source CHECK (source IN ('MANUAL', 'PARTNER_API', 'CREDIT_BUREAU'))
);

-- CHK:{partner_code}:{request_no} 的兜底：外部入站重推同一 request_no 不会落两行。
-- PostgreSQL 的 UNIQUE 允许多行 NULL，故人工录入（无报文号）不受影响。
CREATE UNIQUE INDEX IF NOT EXISTS uk_dchk_request_no
    ON dealer_compliance_checks (request_no)
    WHERE request_no IS NOT NULL;
-- G-A3 取「每类最新一条」用
CREATE INDEX IF NOT EXISTS idx_dchk_dealer_type
    ON dealer_compliance_checks (dealer_id, check_type, checked_on DESC);

COMMENT ON TABLE dealer_compliance_checks IS '车商合规核查留痕（R1.2 反社 / AML / 本人确认 / 征信）。'
    || 'valid_until 的有效期天数=floorplan.used.compliance-valid-days [待定源 D-02]，本包不硬编码，由应用层从配置读取后写入；配置缺省时以入参为准。';
COMMENT ON COLUMN dealer_compliance_checks.result IS 'PASS/FAIL/UNKNOWN；UNKNOWN 在准入判定中按不通过处理（P-7），不得因"未知"放行';
COMMENT ON COLUMN dealer_compliance_checks.request_no IS '外部核查请求号（幂等键组成段，D00 §13 CHK:{partner_code}:{request_no}）；人工录入为 NULL';
COMMENT ON COLUMN dealer_compliance_checks.checked_by IS '核查执行人，同时是 COMPLIANCE_CHECK_DONE 审计动作的主体（D04 §3.3）';
