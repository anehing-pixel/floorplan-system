-- ===========================================================================
-- V74：integration.call_logs + 挡板 partner 种子（W-U3 / R11.3 D11 §7 R7）
-- ===========================================================================
--
-- 【call_logs 是留痕表，不是第二份业务真相源】
-- R11.3 与 D04 §4.5 的共同要求：只存**摘要与哈希**，不存请求/响应全文，
-- 也不存完整签名（D07 §12「完整签名｜日志只留前 8 位」——这里连前 8 位都不单独存列，
-- 只存 request_digest/response_digest，签名材料一概不进日志，见 D13 §4.5 WU3-15）。
-- append-only：不建 updated_at，不授权 UPDATE（D04 §4.5）。
-- 同理也不给 DELETE——保留与清理属 D-02 未签字口径，见下方「保留期」段。
--
-- 【key_version 为什么必须有（G9 补列）】
-- 签名串里含 X-Key-Version，但日志不记版本，事后就无法重现「哪版密钥签的这条」，
-- 轮换期争议会退化成各执一词。存版本号不存密钥，与 INV-4 不冲突。
--
-- 【种子 partner 的边界（D11 §7 六张 / D07 §11 的对应关系）】
-- 六个 TEST-* 与 D11 §7 的夹具表逐条对应，partner_code 一律取 D11 §7 的名字
-- （D07 §11 旧稿写 FAKE-*，与冒烟断言查不到 partner 是同一个坑，D13 G19 已改）。
-- I-12 行情/查定**不是一张 partner 行**：行情源在模型里是 valuation_inputs.source
-- （枚举 ValuationSource），不是 integration.partners 的成员，所以这里不建 TEST-VALUATION；
-- 它的挡板是 FakePartnerServer + fault=DEGRADED_VALUATION_ONLY，属代码夹具不属种子数据。
-- ⚠️ 种子里**没有任何真实密钥**：secret_ref 是 keystore 引用名（假路径），
--    secret_fingerprint 是固定可读标记。D11 §4 的 INV-4 证伪实验搜的是响应里的密钥字面值，
--    本种子刻意不使用 test-secret-* 这类会被误认为密钥的串，避免把「引用名」写成「密钥值」。
--
-- 【保留期（D-02 未签）】
-- D07 §12 写「报文与调用留痕 5 年」，D06 §16 落 integration.payload-retention-days=1825，
-- 而本轮采纳的业务读法是「业务 10 年 / 电子记录 7 年」。**三处天数都不写进 DDL**，
-- 归档与清理作业一律从配置项读取，D-02 签字后只改配置不改库。
-- 因此本文件不建任何 CHECK (occurred_at > now() - INTERVAL '...')，也不建分区。
-- 若将来按保留期做表分区，属新增迁移，不在本号段内偷偷改形状。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- integration.call_logs：出站/入站调用留痕（append-only）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS integration.call_logs (
    id               BIGSERIAL    PRIMARY KEY,
    direction        VARCHAR(8)   NOT NULL,                             -- IN=partner 打进来 / OUT=我方发出去
    partner_code     VARCHAR(64)  NOT NULL,                             -- 逻辑引用 partners；审计主体（R12.2）
    endpoint         VARCHAR(256),                                      -- 入站记请求路径，出站记实际 URL
    request_digest   VARCHAR(64),                                       -- 请求正文 SHA-256（小写十六进制）。**不是签名，不是密钥**
    response_digest  VARCHAR(64),                                       -- 响应正文 SHA-256；入站侧是我方回执的响应
    request_bytes    INT,                                               -- 只记长度，不记内容
    response_bytes   INT,
    http_status      INT,                                               -- 入站记我方返回给 partner 的状态码，出站记对方返回的
    elapsed_ms       INT,
    signature_ok     BOOLEAN,                                           -- 入站才有意义；出站侧为空（我方自己签的，无需再验）
    key_version      INT,                                               -- G9 补列：哪版密钥签的这条
    error_class      VARCHAR(24),                                       -- 与 outbound_tasks.last_error_class 同一取值域；入站侧记验签失败分类
    business_ref     VARCHAR(64),                                       -- 反查：把一次调用挂回业务对象
    trace_id         VARCHAR(64),                                       -- 串起「入站一条 + 出站一条」的同一笔业务
    task_id          BIGINT,                                            -- 逻辑引用 outbound_tasks.id（出站行才有）
    message_id       BIGINT,                                            -- 逻辑引用 inbound_messages.id（入站行才有）
    occurred_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT chk_calllog_direction CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT chk_calllog_error_class CHECK (error_class IS NULL OR error_class IN (
        'TIMEOUT', 'HTTP_5XX', 'HTTP_4XX', 'CIRCUIT_OPEN', 'SIGN_ERROR',
        'TIMESTAMP_SKEW', 'NONCE_REPLAY', 'SIGNATURE_MISMATCH', 'KEY_VERSION_REJECTED',
        'PARTNER_DISABLED', 'SCOPE_DENIED', 'IP_DENIED', 'RATE_LIMITED')),
    -- 一条日志只能属于一个方向：OUT 行挂 message_id 或 IN 行挂 task_id 都会让反查结果自相矛盾。
    CONSTRAINT chk_calllog_one_side CHECK (
        (direction = 'OUT' AND message_id IS NULL) OR (direction = 'IN' AND task_id IS NULL))
);

COMMENT ON TABLE  integration.call_logs IS '调用留痕（R11.3）：只存摘要与哈希，不存全文与签名材料；append-only，不授权 UPDATE/DELETE';
COMMENT ON COLUMN integration.call_logs.request_digest IS 'SHA-256 摘要，非签名；D07 §2.3 日志纪律「禁止记 secret、完整签名、完整 body」的落点';
COMMENT ON COLUMN integration.call_logs.key_version IS 'G9 补列：轮换争议复现用；存版本号不存密钥（INV-4）';
COMMENT ON COLUMN integration.call_logs.occurred_at IS '保留期不写进 DDL：归档天数取配置（D-02 未签）';

-- 按 partner 看近期调用（运维台与熔断统计）。
CREATE INDEX IF NOT EXISTS idx_calllog_partner_time
    ON integration.call_logs (partner_code, occurred_at DESC);

-- 按链路号串全链路（D02 §9.1 traceId 贯穿）。
CREATE INDEX IF NOT EXISTS idx_calllog_trace ON integration.call_logs (trace_id);

-- 只看异常调用：安全审计与故障排查的主入口，避免全表扫。
CREATE INDEX IF NOT EXISTS idx_calllog_error
    ON integration.call_logs (occurred_at) WHERE error_class IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 逐表授权（D04 §4.1：call_logs 只给 SELECT + INSERT）
-- ---------------------------------------------------------------------------
GRANT SELECT, INSERT ON integration.call_logs TO floorplan_dmz;
-- BIGSERIAL 序列权限：DMZ 要往 call_logs 插行。
-- 这里不给 UPDATE/DELETE——日志表在 DMZ 侧只能追加；core 侧用超管连接做归档，
-- 归档属运维动作，不扩 DMZ 权限面。
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA integration TO floorplan_dmz;

-- ---------------------------------------------------------------------------
-- 挡板 partner 种子（D11 §7 六个 + 对应 partner_credentials 各一版）
-- ---------------------------------------------------------------------------
-- 幂等写法：ON CONFLICT DO NOTHING（撞 partners.partner_code UNIQUE 就跳过）。
-- allowed_ips 留空 = 不按 IP 限制：本地冒烟没有固定源 IP，写死会让挡板全 403。
-- receipt_sla_hours 六行一律 NULL：回执 SLA 与效力分级属 U-09 **未签字**（D07:19 与 :832 明确列为待签），
--   种子给它数值 = 把未签字口径写进库；DMZ/出站也不得替这一列补默认值。
--   （原先三行写 72、三行 NULL 的不对称一并消除；等 U-09 签字后由 DBA 统一回填，属运维项。）
-- 生产不得复用这些行，也不得留空 allowed_ips（部署清单项，见实现说明「需要 DBA/网络」段）。
INSERT INTO integration.partners
    (partner_code, partner_name, partner_type, status,
     secret_ref, secret_fingerprint, key_version,
     allowed_ips, allowed_scopes, rate_limit_per_min, receipt_sla_hours, protocol,
     endpoint_base, contact, created_by)
VALUES
    ('TEST-AUCTION', '挡板·拍卖场', 'AUCTION', 'ACTIVE',
     'keystore:test/TEST-AUCTION:v1', 'TESTFNTP', 1,
     NULL, 'AUCTION_LOT_WRITE,AUCTION_SETTLEMENT_WRITE,DISPOSAL_RESULT_WRITE', 300, NULL, 'API',
     'http://localhost:19001', 'smoke 夹具，非真实合作方', 'system:wu3-seed'),
    ('TEST-WMS', '挡板·监管仓', 'WMS', 'ACTIVE',
     'keystore:test/TEST-WMS:v1', 'TESTFNTP', 1,
     NULL, 'WMS_INBOUND_WRITE,WMS_SNAPSHOT_WRITE,RELEASE_RECEIPT_WRITE', 300, NULL, 'API',
     'http://localhost:19002', 'smoke 夹具，非真实合作方', 'system:wu3-seed'),
    ('TEST-CERT-DEP', '挡板·凭证保管方', 'CERT_DEPOSITORY', 'ACTIVE',
     'keystore:test/TEST-CERT-DEP:v1', 'TESTFNTP', 1,
     NULL, 'RELEASE_RECEIPT_WRITE', 300, NULL, 'PORTAL',
     'http://localhost:19003', 'smoke 夹具，非真实合作方', 'system:wu3-seed'),
    ('TEST-LEGAL-AGENT', '挡板·登记代办', 'LEGAL_AGENT', 'ACTIVE',
     'keystore:test/TEST-LEGAL-AGENT:v1', 'TESTFNTP', 1,
     NULL, 'REGISTRATION_RESULT_WRITE', 300, NULL, 'API',
     'http://localhost:19004', 'smoke 夹具，非真实合作方', 'system:wu3-seed'),
    ('TEST-BANK', '挡板·银行', 'BANK', 'ACTIVE',
     'keystore:test/TEST-BANK:v1', 'TESTFNTP', 1,
     NULL, 'BANK_TXN_WRITE', 300, NULL, 'API',
     'http://localhost:19005', 'smoke 夹具，非真实合作方', 'system:wu3-seed'),
    ('TEST-COMPLIANCE', '挡板·征信/反社', 'CREDIT_BUREAU', 'ACTIVE',
     'keystore:test/TEST-COMPLIANCE:v1', 'TESTFNTP', 1,
     NULL, 'COMPLIANCE_RESULT_WRITE', 300, NULL, 'API',
     'http://localhost:19006', 'smoke 夹具，非真实合作方', 'system:wu3-seed')
ON CONFLICT (partner_code) DO NOTHING;

-- 凭据历史各建第一版。secret_ref 是 keystore 引用名，**不是密钥值**；
-- 真正的密钥由部署侧注入（本地用环境变量），DMZ 按 (partner_code, key_version) 解析。
-- ⚠️ allowed_scopes 用的是 D07 §3 各接口表头的 scope 名（WMS_INBOUND_WRITE 等），
--    不是 D00 §8.4 PartnerScope 的枚举名（INBOUND_CONFIRM_WRITE 等）。两处不一致：
--    本包按「接口协议以 D07 为准」的口径落 D07 名，并把它登记为实现说明待裁决项——
--    若收口裁决取 D00 §8.4，需要同时改这张种子与 DMZ 的 scope 常量表，不能只改一边。
INSERT INTO integration.partner_credentials
    (partner_code, key_version, secret_ref, secret_fingerprint,
     status, valid_from, rotated_by, rotate_reason)
SELECT p.partner_code, 1, p.secret_ref, p.secret_fingerprint,
       'ACTIVE', now(), 'system:wu3-seed', '挡板初始版本'
FROM integration.partners p
WHERE p.partner_code IN ('TEST-AUCTION', 'TEST-WMS', 'TEST-CERT-DEP',
                         'TEST-LEGAL-AGENT', 'TEST-BANK', 'TEST-COMPLIANCE')
ON CONFLICT (partner_code, key_version) DO NOTHING;

COMMENT ON TABLE integration.partners IS 'partner 注册主数据（R11.2）；本表含六个 TEST-* 挡板种子（D11 §7），生产部署须按 D07 §10 下线判据评估是否保留';
