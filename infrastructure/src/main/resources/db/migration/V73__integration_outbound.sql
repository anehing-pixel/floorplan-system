-- ===========================================================================
-- V73：integration.outbound_tasks（W-U3 / R11.4 R11.5）
-- ===========================================================================
--
-- 【这张表的写方不是 DMZ】
-- 出站意图由 core 的 OutboundRelay 从 outbox_events 搬运进来（D02 §5.5），
-- DMZ 只**领取 + 回写执行态**（SELECT/UPDATE，无 INSERT）。
-- 为什么不绕 outbox 直接写：在业务事务里直接写 integration 表 = 一个事务写两个 schema，
-- 按 D01 §6 物理分库那一刻就退化成双库两阶段提交。绕 outbox 之后，
-- 同库与分库两种部署的代码路径完全一致，只是延迟多一跳。
--
-- 【状态集取 D02 §5.5，不取 §5.2 的「四态对齐」句】
-- D02 §5.2 共同约束 ③ 说「状态机与 event_consumptions.status 语义对齐
-- （PENDING/PROCESSED/FAILED/DEAD）」，而 §5.5 的状态机图给了八个态且 D04 §4.4
-- 明确「status = D02 §5.5 状态集 + CHECK」。两者不可同时满足：入站回执要区分
-- ACCEPTED（对方受理）与 REJECTED（4xx 业务拒绝，不自动重试），四态表达不了。
-- 本文件按 D04 §4.4 指向的 §5.5 建 CHECK，把这条不一致登记在实现说明
-- （另：D11 §4.1 E-1 里出现 SUCCESS，与 §5.5 的 ACCEPTED 是同一含义的第二个名字，
--  属测试文档的口径漂移，同样只登记不自创）。
--
-- 【领取并发】
-- locked_by / locked_at 是 DMZ 多实例的领取标记（D04 §4.4 原注「防 DMZ 多实例重复领取」）。
-- 现网一期每进程一实例（D06 §17 的多实例安全声明），故本包用
-- FOR UPDATE SKIP LOCKED 领取 + locked_by 记录，不加分布式锁；
-- 若将来真的多实例并行，还需要给 IN_FLIGHT 设看门狗超时回收，否则进程被杀会把任务永久卡在 IN_FLIGHT。
-- 这一条属遗留项，登记在实现说明。
-- ===========================================================================

CREATE TABLE IF NOT EXISTS integration.outbound_tasks (
    id                 BIGSERIAL       PRIMARY KEY,                     -- 即 D07 §3.9 与运维台所说的 task_id
    idempotency_key    VARCHAR(160)    NOT NULL UNIQUE,                 -- 见 D00 §13.2（OUT 段）；与 D07 §4 的构成不一致，取哪套属收口裁决，见实现说明
    task_type          VARCHAR(48)     NOT NULL,                        -- 任务语义分类，取值域见下方 CHECK
    partner_code       VARCHAR(64)     NOT NULL,                        -- 逻辑引用 partners.endpoint_base 与凭据
    endpoint           VARCHAR(256),                                    -- 完整出站 URL 或相对路径（由 relay 物化，DMZ 不回头查业务库）
    payload_ref        VARCHAR(512),                                    -- 载荷引用：本地文件路径。**出站报文不留全文**，只留引用与摘要（D04 §4.5）
    payload_digest     VARCHAR(64),                                     -- 载荷 SHA-256（小写十六进制）：重试时复用同一份，不重新拼报文
    business_ref       VARCHAR(64),                                     -- 反查键：order_no / settlement_no / disposal_case_no；partner 返回的 instruction_no 也回写这里（D07 §4.1）
    -- D02 §5.5 状态机：PENDING → IN_FLIGHT → ACCEPTED | RETRY_WAIT | FAILED | REJECTED；
    -- FAILED → DEAD（重试超限并开工单）或人工重触发回 PENDING；REJECTED/DEAD → CANCELLED。
    status             VARCHAR(24)     NOT NULL DEFAULT 'PENDING',
    attempts           INT             NOT NULL DEFAULT 0,
    max_attempts       INT             NOT NULL DEFAULT 5,              -- 出站 5 次（指数退避，D07 §4.1），与入站消费的 3 次不是一套
    next_retry_at      TIMESTAMPTZ,                                     -- 退避到期时刻；为空表示可立即投递
    expires_at         TIMESTAMPTZ,                                     -- 与 release_orders.valid_until 对齐（D04 §4.4）
    last_error         TEXT,                                            -- 最后一次失败摘要；运维台可读，**不进 partner 响应**（WU3-15）
    last_error_class   VARCHAR(24),                                     -- TIMEOUT / HTTP_5XX / HTTP_4XX / CIRCUIT_OPEN / SIGN_ERROR（D04 §4.4）
    http_status        INT,                                             -- 对方最后一次 HTTP 状态码
    elapsed_ms         INT,                                             -- 最后一次调用耗时
    trace_id           VARCHAR(64),                                     -- 贯穿到入站回执，串起「我发了什么 / 对方回了什么」
    locked_by          VARCHAR(64),                                     -- 领取者标识（进程名+实例），防多实例重复领取
    locked_at          TIMESTAMPTZ,                                     -- 领取时刻
    created_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by         VARCHAR(64),                                     -- 内部触发者，或常量 system:outbox-relay（D04 §4.4）
    updated_by         VARCHAR(64),                                     -- 状态迁移来源：external-api 实例名 / 人工重触发者（AuditActor）
    CONSTRAINT chk_outbound_task_type CHECK (task_type IN (
        'RELEASE_ORDER_DISPATCH', 'RELEASE_ORDER_CANCEL', 'AUCTION_PAYMENT',
        'RELIST_CONSIGN', 'MARKET_QUERY', 'COMPLIANCE_QUERY')),
    CONSTRAINT chk_outbound_status CHECK (status IN (
        'PENDING', 'IN_FLIGHT', 'ACCEPTED', 'RETRY_WAIT', 'FAILED', 'DEAD', 'REJECTED', 'CANCELLED')),
    CONSTRAINT chk_outbound_error_class CHECK (last_error_class IS NULL OR last_error_class IN (
        'TIMEOUT', 'HTTP_5XX', 'HTTP_4XX', 'CIRCUIT_OPEN', 'SIGN_ERROR')),
    -- 退避次数必须非负；max_attempts 至少 1，否则任务建出来就是 DEAD，属于配置错误而非业务状态。
    CONSTRAINT chk_outbound_attempts CHECK (attempts >= 0 AND max_attempts >= 1),
    -- 已终结的任务不许再排重试：ACCEPTED/DEAD/CANCELLED/REJECTED 若带着 next_retry_at，
    -- 领取查询会把它当待办重新发一遍，对 partner 重复下发指令是资损面。
    CONSTRAINT chk_outbound_terminal_no_retry CHECK (
        status NOT IN ('ACCEPTED', 'DEAD', 'CANCELLED', 'REJECTED') OR next_retry_at IS NULL)
);

COMMENT ON TABLE  integration.outbound_tasks IS '出站任务队列（R11.4）：core 写、DMZ 领取并回写执行态；出站报文不留全文';
COMMENT ON COLUMN integration.outbound_tasks.status IS 'D02 §5.5 八态；ACCEPTED 只表示 partner 受理，不表示业务动作完成（§5.5 与业务态的关系）';
COMMENT ON COLUMN integration.outbound_tasks.max_attempts IS '默认 5：出站按指数退避重试；4xx 不重试直接 REJECTED 转人工（D07 §4.1）';
COMMENT ON COLUMN integration.outbound_tasks.business_ref IS '反查键；partner 返回的 instruction_no 回写此列（D07 §4.1 期望响应）';
COMMENT ON COLUMN integration.outbound_tasks.locked_by IS 'DMZ 领取者标识；一期单实例假设，多实例需另加 IN_FLIGHT 看门狗（实现说明遗留项）';

-- 领取主查询：按 (status, next_retry_at) 捞可投递任务（D04 §4.4）。
CREATE INDEX IF NOT EXISTS idx_ob_poll ON integration.outbound_tasks (status, next_retry_at);

-- 熔断统计：按 partner 维度数近期失败（D07 §2.6 连续 5 次验签失败 / D02 §5.5 出站熔断）。
CREATE INDEX IF NOT EXISTS idx_ob_partner_status ON integration.outbound_tasks (partner_code, status);

-- 超期巡检只扫未完成任务（D07 §4.2：expires_at 到点未 ACKNOWLEDGED 由巡检置 EXPIRED）。
CREATE INDEX IF NOT EXISTS idx_ob_expiry
    ON integration.outbound_tasks (expires_at)
    WHERE status IN ('PENDING', 'RETRY_WAIT');

-- ---------------------------------------------------------------------------
-- 逐表授权（D04 §4.1）
-- ---------------------------------------------------------------------------
-- DMZ 对出站任务**只读 + 回写执行态，不给 INSERT**：
-- 「要不要发」是业务决定，只能由 core 的 relay 做；若 DMZ 能插任务，
-- DMZ 就拥有了凭空向 partner 下发指令的能力，INV-1/INV-3 同时失效。
-- 也不给 DELETE：任务永不物理删除，作废走 CANCELLED 状态（D04 §12 迁移纪律 + 留痕要求）。
GRANT SELECT, UPDATE ON integration.outbound_tasks TO floorplan_dmz;
