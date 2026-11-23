-- ===========================================================================
-- V62：拍卖结算单 + 分摊明细（W-U2 / R4.2、R4.4）
-- ===========================================================================
--
-- 【规格源】D04 §3.7；守恒口径 D06 §4.4；错误码 AUC_005（D00 §9 码表）。
--
-- 【结算单为什么是"批次"而不是"单笔"】
-- 拍卖行按场次/周期给一张结算单，内含多笔落札。逐笔建结算单会丢掉
-- 「这张单一共该付多少、实付多少」这个对手方视角的事实，
-- 而对账恰恰是按单进行的。分摊（items）把单级的钱拆回笔级，
-- 于是"单是否平衡"（AUC_005）和"这笔车成本多少"（取得原价）两问都有答案。
--
-- 【守恒式】allocated_amount = Σ auction_settlement_items.alloc_amount
-- 必须等于 total_amount（D04 §3.7 原话：「**必须等于 total_amount**，CHECK 或作业校验」）。
-- D04 在这里留了个"二选一"，本迁移选的是**两边都做、但各管一段**：
--   - 库里能表达的：单行自洽（alloc_amount 非负、allocated_amount 非负、
--     同一单内同一 lot 只能分摊一次）；
--   - 库里表达不了的：跨行合计等于 total —— 那需要聚合，CHECK 无法引用聚合结果，
--     所以由 AuctionSettlementAllocator 在写入前断言（D03 §4）+ reconcile 端点复算，
--     不平时报 AUC_005（HTTP 422）。
-- 这里必须说清"为什么不能只靠 CHECK"：如果只写 CHECK，实现者会以为守恒已有保证；
-- 如果只写应用层断言，运维直插就能造出不平衡的单。两段都要有明确的执行主体。
-- ===========================================================================

CREATE TABLE IF NOT EXISTS auction_settlements (
    id                  BIGSERIAL    PRIMARY KEY,
    settlement_no       VARCHAR(64)  NOT NULL UNIQUE,     -- 拍场给的结算番号
    auction_house_code  VARCHAR(32)  NOT NULL,            -- 逻辑引用 auction_houses
    settlement_date     DATE         NOT NULL,
    due_date            DATE,                            -- 应付日；NULL = 拍场未给账期（不猜，见 V60 [D-08]）
    total_amount        BIGINT       NOT NULL CHECK (total_amount >= 0),   -- 整数日元
    lot_count           INT          NOT NULL DEFAULT 0 CHECK (lot_count >= 0),
    allocated_amount    BIGINT       NOT NULL DEFAULT 0 CHECK (allocated_amount >= 0),
    status              VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    source_message_id   BIGINT,                          -- 证据链（跨 schema，逻辑引用）
    -- ★ 支付幂等落点（见本迁移末尾的【规格缺口】说明）
    payment_ref         VARCHAR(128),                    -- 外部支付流水引用（对账用）
    idempotency_key     VARCHAR(128),                    -- AEF:{auction_code}:{settlement_no}:{paymentRef}
    paid_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          VARCHAR(64),
    version             INT          NOT NULL DEFAULT 0,
    note                VARCHAR(255),
    CONSTRAINT ck_settle_status CHECK (status IN ('OPEN','PAID','PARTIAL','OVERDUE','CLOSED')),
    -- 分摊额不得超过单额。这条不是"守恒"（等于号在库里做不到），
    -- 但它能挡住最常见的错：分摊重复累加导致 allocated > total，
    -- 而这种情况一旦发生，成本入账就多算 —— 早失败比晚发现便宜得多。
    CONSTRAINT ck_settle_alloc_le_total CHECK (allocated_amount <= total_amount),
    -- 状态与时间戳配套：PAID/CLOSED 却没有支付时点，等于"钱付了但不知道什么时候"。
    CONSTRAINT ck_settle_paid_at CHECK (status NOT IN ('PAID','CLOSED') OR paid_at IS NOT NULL),
    -- OVERDUE 必须有到期日：没有 due_date 的逾期判定是无中生有。
    CONSTRAINT ck_settle_overdue CHECK (status <> 'OVERDUE' OR due_date IS NOT NULL)
);

-- 支付幂等（L2）：同一支付引用只能落一张结算单。
-- WHERE payment_ref IS NOT NULL：OPEN 阶段的行该列为 NULL，Postgres 视 NULL 互不相等，
-- 加条件只是把"多条未支付行是合法的"这件事写成显式语义，避免后来人误解。
-- 这是数据库层对「同一笔代付重复落账」的唯一硬保证：应用层判"已存在就不再落"
-- 在并发下会同时通过检查（两条都读到"不存在"），结果就是重复结清两台车。
CREATE UNIQUE INDEX IF NOT EXISTS uk_settle_payment_ref
    ON auction_settlements (payment_ref) WHERE payment_ref IS NOT NULL;
-- AEF 键的库层唯一约束：与 payment_ref 唯一索引同向，但键里含拍场码与结算号，
-- 用于跨拍场、跨结算号的组合去重（D00 §13.3 的键式）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_settle_aef_key
    ON auction_settlements (idempotency_key) WHERE idempotency_key IS NOT NULL;
-- 按拍场 + 状态（结算单列表与 reconcile 端点的读取路径）
CREATE INDEX IF NOT EXISTS idx_settle_house_status ON auction_settlements (auction_house_code, status, settlement_date DESC);
-- 未平/部分付的巡检队列
CREATE INDEX IF NOT EXISTS idx_settle_open ON auction_settlements (due_date) WHERE status IN ('OPEN','PARTIAL','OVERDUE');

COMMENT ON TABLE auction_settlements IS
    '拍卖结算单（W-U2 / R4.2、R4.4）。一行 = 拍场给的一张单（含多笔落札）。 守恒式 allocated_amount = Σ items.alloc_amount 由写入前断言 + reconcile 复算保证， 不平时 AUC_005（422），禁止只改 allocated 而不改明细。';
COMMENT ON COLUMN auction_settlements.status IS
    'OPEN/PAID/PARTIAL/OVERDUE/CLOSED（D04 §3.7）。SELF_PAY（经销商自营资金）不走本表结清： 自营是真实资金动作，不得因为"没有外部代付"就恒置 PAID —— 见实现说明 §5 与 D08 §7.4。';
COMMENT ON COLUMN auction_settlements.allocated_amount IS
    '已分摊金额合计（整数日元）。库内 CHECK 只保证 ≤ total_amount； 与明细合计的相等关系在应用层与 reconcile 端点校验。';

-- ===========================================================================
-- 分摊明细：一行 = 一笔落札在这张单里承担多少
-- ===========================================================================
CREATE TABLE IF NOT EXISTS auction_settlement_items (
    id             BIGSERIAL   PRIMARY KEY,
    settlement_id  BIGINT      NOT NULL REFERENCES auction_settlements(id) ON DELETE CASCADE,
    lot_id         BIGINT      NOT NULL REFERENCES auction_lots(id),
    alloc_amount   BIGINT      NOT NULL CHECK (alloc_amount >= 0),
    note           VARCHAR(255),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 同一单内同一落札只能分摊一次（D04 §3.7）。
    -- 重复分摊会让 Σalloc 超过 total 并把同一辆车的成本记两遍；
    -- 表内 UNIQUE 语法不支持条件索引，这里用普通唯一约束（无 NULL 列，语义等价）。
    CONSTRAINT uk_settle_item UNIQUE (settlement_id, lot_id)
);

-- 按落札反查它出现在哪些结算单（争议处理与成本还原）
CREATE INDEX IF NOT EXISTS idx_settle_item_lot ON auction_settlement_items (lot_id);

COMMENT ON TABLE auction_settlement_items IS
    '结算分摊明细（W-U2 / R4.2）。alloc_amount 是这台车的车款在本单内的份额， 诸费用另在 auction_lot_costs 逐笔登记，两者共同构成取得原价。';

-- ===========================================================================
-- 【规格缺口：支付引用列与 AEF 唯一约束】
-- D00 §13.3 要求把消费 PAYMENT_MATCHED 的幂等键
--     AEF:{auction_code}:{settlement_no}:{paymentRef}
-- 落到 auction_settlements 的支付引用列并加唯一约束；
-- 但 D04 §3.7 给出的 auction_settlements 列清单里没有支付引用列
-- （只有 settlement_no / total_amount / allocated_amount / status / source_message_id）。
-- 两处不一致的处置：本迁移补 payment_ref + idempotency_key 两列与两条部分唯一索引，
-- 理由不是"文档没写就不做"，而是没有这两列，消费侧的 L2 幂等无处可存 ——
-- 只剩 L1（event_consumptions）时，同一事件被框架判为已消费固然不会重复执行，
-- 但"人工按同一支付引用再次提交 reconcile"这条路径没有任何库层防线。
-- 该补列已登记为文档↔规格差（实现说明 §3-7），由 D04 回灌。
-- ===========================================================================
