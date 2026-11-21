-- V7: 采购订单锚点、上游主数据与收款账户（ADR-007 最终版）
--
-- 背景（ADR-007 决策一）：打款对象不由系统规则决定，而由**采购订单凭证**决定。
-- 融资申请 → 关联采购订单 → 订单携带 supplier_type + payee_account_ref → 打款对象落地。
-- 本迁移把这条链路的数据结构补齐，是后续放款校验与导入通道的地基。
--
-- 关键设计约束（勿在后续迁移中违反）：
--   1. payee_mode（DIRECT_PAY / SELF_PAY）是 supplier_type 的**派生值**，
--      不落库为独立可编辑字段——否则"选 DEALER_SELF 就绕开代付协议"将成为漏洞；
--   2. 账号明文不落库：只存 AES-GCM 密文 + 对外掩码 + 业务引用码 account_ref；
--   3. 附件文件本体不入库：attachments 只存元数据，文件本体走对象存储；
--   4. order_no 与 VIN 双幂等：前者防"同一张单重复导入"，后者防"两个订单号报同一台车"。

-- ============================================================
-- 1. 上游主数据 suppliers
-- ============================================================
-- 为什么需要独立的"上游"实体：日本的经销商不直接向汽车工厂下单，
-- 实际经由制造商销售公司 / 区域总代理 / 批发公司 / 拍卖行 / 中古车批发商多层渠道，
-- 把上游当"厂家"单一实体会算错打款对象与担保能力。
CREATE TABLE suppliers (
    id                 BIGSERIAL PRIMARY KEY,
    supplier_code      VARCHAR(32)  UNIQUE NOT NULL,
    name               VARCHAR(128) NOT NULL,
    supplier_type      VARCHAR(24)  NOT NULL,
    status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    risk_grade         VARCHAR(8),
    -- 回购/担保能力：STRONG / LIMITED / NONE
    -- 中间层级（总代理、批发）与拍卖行普遍没有厂商回购，不可一刀切按 STRONG 处理。
    guarantee_ability  VARCHAR(16),
    contact_person     VARCHAR(64),
    contact_phone      VARCHAR(32),
    remark             VARCHAR(500),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_supplier_type CHECK (supplier_type IN (
        'MAKER_SALES_CO', 'REGIONAL_AGENCY', 'WHOLESALER',
        'AUCTION', 'USED_WHOLESALER', 'DEALER_SELF')),
    CONSTRAINT ck_supplier_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'TERMINATED')),
    CONSTRAINT ck_supplier_guarantee CHECK (
        guarantee_ability IS NULL OR guarantee_ability IN ('STRONG', 'LIMITED', 'NONE'))
);
CREATE INDEX idx_suppliers_type   ON suppliers (supplier_type);
CREATE INDEX idx_suppliers_status ON suppliers (status);

COMMENT ON TABLE  suppliers IS '上游主数据（制造商销售公司/区域总代理/批发公司/拍卖行/中古批发商/经销商自身）';
COMMENT ON COLUMN suppliers.supplier_type IS '上游类型，派生打款模式：DIRECT_PAY(除DEALER_SELF) / SELF_PAY(DEALER_SELF)';
COMMENT ON COLUMN suppliers.guarantee_ability IS '回购/担保能力：STRONG强 / LIMITED有限 / NONE无——不可按上游类型一刀切';

-- ============================================================
-- 2. 收款账户（上游账户 + 经销商结算账户）
-- ============================================================
-- 两张表结构相同但归属不同，故不合并：
--   supplier_bank_accounts → 供应商账户（DIRECT_PAY 的收款方）
--   dealer_bank_accounts   → 经销商结算账户（SELF_PAY 场景 C 的收款方）
-- 场景 C 之前无任何账户表可存，是能力缺口，必须建表留位。
CREATE TABLE supplier_bank_accounts (
    id                BIGSERIAL PRIMARY KEY,
    supplier_id       BIGINT      NOT NULL REFERENCES suppliers (id),
    bank_name         VARCHAR(64) NOT NULL,
    account_holder    VARCHAR(128) NOT NULL,
    -- AES-GCM 密文：账号明文绝不落库
    account_no_cipher TEXT        NOT NULL,
    -- 对外只返掩码（例：1234 **** **** 5678）
    account_no_mask   VARCHAR(32) NOT NULL,
    -- 业务引用码：Excel/CSV 导入只填这个，系统按引用查主数据。
    -- 不接受文件里直接写明文账号——否则导入通道就成了塞入攻击者账户的后门。
    account_ref       VARCHAR(32) UNIQUE,
    status            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    effective_from    DATE,
    effective_to      DATE,
    -- 双人复核：录入人与复核人必须是两个人
    reviewer          VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_supplier_account_status CHECK (status IN ('ACTIVE', 'PENDING_REVIEW', 'FROZEN', 'CLOSED'))
);
CREATE INDEX idx_supplier_accounts_supplier ON supplier_bank_accounts (supplier_id);
CREATE INDEX idx_supplier_accounts_status   ON supplier_bank_accounts (status);

CREATE TABLE dealer_bank_accounts (
    id                BIGSERIAL PRIMARY KEY,
    dealer_id         BIGINT      NOT NULL REFERENCES dealers (id),
    bank_name         VARCHAR(64) NOT NULL,
    account_holder    VARCHAR(128) NOT NULL,
    account_no_cipher TEXT        NOT NULL,
    account_no_mask   VARCHAR(32) NOT NULL,
    account_ref       VARCHAR(32) UNIQUE,
    status            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    effective_from    DATE,
    effective_to      DATE,
    reviewer          VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_dealer_account_status CHECK (status IN ('ACTIVE', 'PENDING_REVIEW', 'FROZEN', 'CLOSED'))
);
CREATE INDEX idx_dealer_accounts_dealer ON dealer_bank_accounts (dealer_id);
CREATE INDEX idx_dealer_accounts_status ON dealer_bank_accounts (status);

COMMENT ON TABLE  supplier_bank_accounts IS '上游收款账户（账号密文落库，对外只返掩码；account_ref 供导入引用）';
COMMENT ON TABLE  dealer_bank_accounts   IS '经销商结算账户（场景C：库存抵押周转回款至经销商自身账户）';

-- ============================================================
-- 3. 附件元数据（文件本体存对象存储，库里只存元数据）
-- ============================================================
-- 不做 biz_id 外键：附件要挂多类业务对象（采购订单/代付协议/车辆照片…），
-- 外键会把附件表绑死在单一业务上。
CREATE TABLE attachments (
    id                BIGSERIAL PRIMARY KEY,
    biz_type          VARCHAR(32)  NOT NULL,
    biz_id            BIGINT       NOT NULL,
    -- 对象键用 UUID/哈希，不用原始文件名（文件名含客户名与车架号会泄露且可能冲突）
    object_key        VARCHAR(256) UNIQUE NOT NULL,
    bucket            VARCHAR(64),
    region            VARCHAR(64),
    -- 原始文件名仅供展示，不参与寻址
    original_filename VARCHAR(256),
    content_type      VARCHAR(64)  NOT NULL,
    content_length    BIGINT       NOT NULL,
    -- 内容指纹：自证与防替换（上传 A 登记 B 靠这两个字段发现）
    sha256            VARCHAR(64),
    etag              VARCHAR(64),
    -- COMPLIANCE / GOVERNANCE：存储层强制"一次写入不可删改"
    object_lock_mode  VARCHAR(16),
    uploaded_by       VARCHAR(64),
    uploaded_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_attachment_lock CHECK (
        object_lock_mode IS NULL OR object_lock_mode IN ('COMPLIANCE', 'GOVERNANCE'))
);
CREATE INDEX idx_attachment_biz ON attachments (biz_type, biz_id);

COMMENT ON TABLE attachments IS '附件元数据（文件本体存对象存储，数据库不存 BLOB）';

-- ============================================================
-- 4. 导入批次（Excel 通道的行级结果留痕）
-- ============================================================
-- 已有 batch_runs 表用于作业批处理；这里另建一张专门记"采购清单导入"的批次，
-- 因为两者生命周期不同：batch_runs 记的是系统作业，import_batches 记的是外部文件导入。
CREATE TABLE import_batches (
    id            BIGSERIAL PRIMARY KEY,
    channel       VARCHAR(16) NOT NULL,
    filename      VARCHAR(255),
    -- 文件哈希：同一份文件重复导入可识别
    file_hash     VARCHAR(64),
    total_rows    INT NOT NULL DEFAULT 0,
    accepted_rows INT NOT NULL DEFAULT 0,
    rejected_rows INT NOT NULL DEFAULT 0,
    total_amount  BIGINT NOT NULL DEFAULT 0,
    status        VARCHAR(16) NOT NULL DEFAULT 'PREVIEWED',
    operator      VARCHAR(64),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    committed_at  TIMESTAMPTZ,
    CONSTRAINT ck_import_batch_channel CHECK (channel IN ('API', 'EXCEL')),
    CONSTRAINT ck_import_batch_status CHECK (status IN ('PREVIEWED', 'COMMITTED', 'REJECTED'))
);
CREATE INDEX idx_import_batches_status ON import_batches (status);

COMMENT ON TABLE import_batches IS '采购清单导入批次（行级结果留痕：成功/失败数、文件哈希、操作人）';

-- 导入行级错误明细：preview 阶段返回给用户的"哪一行哪个字段为什么错"
CREATE TABLE import_row_errors (
    id           BIGSERIAL PRIMARY KEY,
    batch_id     BIGINT NOT NULL REFERENCES import_batches (id) ON DELETE CASCADE,
    row_no       INT    NOT NULL,
    field_name   VARCHAR(64),
    reason       VARCHAR(500) NOT NULL,
    raw_value    VARCHAR(255),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_import_row_errors_batch ON import_row_errors (batch_id);

-- ============================================================
-- 5. 采购订单主单（ADR-007 的锚点）
-- ============================================================
CREATE TABLE purchase_orders (
    id                    BIGSERIAL PRIMARY KEY,
    -- 采购订单号（上游单号）：幂等锚点，唯一
    order_no              VARCHAR(64) UNIQUE NOT NULL,
    dealer_id             BIGINT      NOT NULL REFERENCES dealers (id),
    credit_line_id        BIGINT      REFERENCES credit_lines (id),
    -- DEALER_SELF 场景（场景C）无外部上游，supplier_id 可空
    supplier_id           BIGINT      REFERENCES suppliers (id),
    -- 决定打款模式的字段：DIRECT_PAY(除DEALER_SELF) / SELF_PAY(DEALER_SELF)
    supplier_type         VARCHAR(24) NOT NULL,
    -- 收款账户外键：上游账户或经销商结算账户（二选一，由 supplier_type 决定归属侧）
    payee_account_id      BIGINT      NOT NULL,
    -- 下单时的账户事实（银行+户名+掩码）快照：留痕不可变
    payee_account_snapshot TEXT,
    total_amount          BIGINT      NOT NULL DEFAULT 0,
    vehicle_count         INT         NOT NULL DEFAULT 0,
    purchased_at          DATE,
    -- 契约/発注書/請求書 号：只留号 + 原件附件，不解析入库
    evidence_no           VARCHAR(64),
    evidence_attachment_id BIGINT     REFERENCES attachments (id),
    status                VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED',
    -- 导入通道：API（上游系统直连）/ EXCEL（经销商提交清单）
    ingest_channel        VARCHAR(16) NOT NULL,
    import_batch_id       BIGINT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_po_supplier_type CHECK (supplier_type IN (
        'MAKER_SALES_CO', 'REGIONAL_AGENCY', 'WHOLESALER',
        'AUCTION', 'USED_WHOLESALER', 'DEALER_SELF')),
    CONSTRAINT ck_po_status CHECK (status IN (
        'DRAFT', 'CONFIRMED', 'DISBURSED', 'SETTLED', 'CANCELLED')),
    CONSTRAINT ck_po_channel CHECK (ingest_channel IN ('API', 'EXCEL')),
    CONSTRAINT chk_po_amount CHECK (total_amount >= 0)
);
CREATE INDEX idx_po_dealer   ON purchase_orders (dealer_id);
CREATE INDEX idx_po_supplier ON purchase_orders (supplier_id);
CREATE INDEX idx_po_status   ON purchase_orders (status);
CREATE INDEX idx_po_batch    ON purchase_orders (import_batch_id);

COMMENT ON TABLE  purchase_orders IS '采购订单主单（放款前置锚点；两条导入通道产出同一份主单）';
COMMENT ON COLUMN purchase_orders.supplier_type IS '上游类型；payee_mode 由它派生，不可人工覆盖';
COMMENT ON COLUMN purchase_orders.payee_account_snapshot IS '下单时账户快照（与放款时快照不是同一份事实）';
COMMENT ON COLUMN purchase_orders.ingest_channel IS '导入通道：API 直连 / EXCEL 批量';

-- ============================================================
-- 6. 台账与垫款挂接采购订单
-- ============================================================
-- 车辆挂采购订单：订单是一单多车的天然载体。
ALTER TABLE vehicle_units ADD COLUMN IF NOT EXISTS purchase_order_id BIGINT REFERENCES purchase_orders (id);
CREATE INDEX idx_vehicle_units_po ON vehicle_units (purchase_order_id);

-- VIN 幂等：同一 VIN 不得出现在两个"未终结"的采购订单中。
-- 用条件唯一索引实现——PostgreSQL 的部分唯一索引只对满足条件的行生效，
-- 因此已结算/已取消订单释放 VIN，允许后续重新采购同一台车。
CREATE UNIQUE INDEX uk_po_vin_active
    ON vehicle_units (vin)
    WHERE purchase_order_id IS NOT NULL
      AND status IN ('FINANCED', 'IN_STOCK', 'SOLD_PENDING');

COMMENT ON INDEX uk_po_vin_active IS 'VIN 幂等：同一 VIN 不得出现在两个未终结采购订单中';

-- 垫款挂采购订单 + 放款时冻结的收款方事实
ALTER TABLE advances ADD COLUMN IF NOT EXISTS purchase_order_id BIGINT REFERENCES purchase_orders (id);
ALTER TABLE advances ADD COLUMN IF NOT EXISTS payee_supplier_id BIGINT REFERENCES suppliers (id);
ALTER TABLE advances ADD COLUMN IF NOT EXISTS payee_supplier_type VARCHAR(24);
ALTER TABLE advances ADD COLUMN IF NOT EXISTS payee_account_snapshot TEXT;
CREATE INDEX idx_advances_po ON advances (purchase_order_id);

COMMENT ON COLUMN advances.payee_account_snapshot IS '放款时冻结的收款方事实（不可变）；与下单时快照是两件事';

COMMENT ON TABLE import_row_errors IS '导入行级错误明细（行号+字段+原因，可下载修正后重传）';
