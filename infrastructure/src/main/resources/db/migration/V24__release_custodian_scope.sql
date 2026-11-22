-- ===========================================================================
-- V24：出库指令接收方按释放项拆分 + 确认权威留痕（Q9/Q10 建议口径落地）
-- ===========================================================================
--
-- 【为什么不动 V19】
-- V19 已执行，Flyway 校验和按文件内容计算；哪怕只改一个注释也会导致 validate
-- 失败、服务启动不了。口径与注释调整一律走新迁移，V19 保持字节不变。
--
-- 【业务依据：用户已确认的日本新车渠道】
-- 《业务流程文档》§6（201-207 行）：物理监管只做实地盘点（逐台扫 VIN + 拍照），
-- 不做扣押钥匙 / GPS / 封签 / 驻场监管（那是中国第三方监管公司对二手车、
-- 高风险车商的做法），兜底靠厂家回购承诺 + 经销商信用 + 让与担保。
-- 也就是说车物理上就在经销商自己的库里，金融公司没有第三方替它看车。
-- 因此接收方不能是一个单一机构，必须按释放项分两条线：
--   放车 VEHICLE / KEY  → DEALER_WAREHOUSE      经销商自有仓库（执行交付）
--   放证 CERTIFICATE    → CERTIFICATE_DEPOSITORY 凭证保管点（权属凭证才是真正的控制手段）
-- THIRD_PARTY_WAREHOUSE / ON_SITE_SUPERVISOR 与已确认口径冲突，
-- 枚举保留但须业务书面确认后方可启用（不得作为默认配置）。
--
-- 【确认权威与执行接收方解耦】
-- 设计稿 §7 #10 要求回执不能由我方代填、不能由经销商自证代替；但日本渠道
-- 没有第三方实体监管，起步只能人工转交 + 我方见证代录。所以必须把
-- "谁执行的交付" 与 "谁有权确认推进" 分开记：
--   release_receipts.source / confirmed_by  记录执行方与代录人
--   release_state_history.confirmation_authority 记录每次推进的权威来源
-- 这样 SENT→CONFIRMED→RELEASED 的推进可审计，且不与 §7 #10 硬冲突。
-- ===========================================================================

-- ① 明细级接收方：NULL 表示继承主表 custodian_id（兼容既有数据与调用方）
ALTER TABLE release_order_items
    ADD COLUMN IF NOT EXISTS custodian_id BIGINT;

COMMENT ON COLUMN release_order_items.custodian_id IS
    '该项实际接收方（逻辑引用 custodians，不建跨服务外键）；NULL 表示继承 release_orders.custodian_id。放车线与放证线可指向不同机构';

-- ② 状态推进的确认权威：把「执行接收方」与「权威确认方」解耦
ALTER TABLE release_state_history
    ADD COLUMN IF NOT EXISTS confirmation_authority VARCHAR(24);

COMMENT ON COLUMN release_state_history.confirmation_authority IS
    '该次推进的确认权威：PARTNER_RECEIPT 接收方回执 / INTERNAL_WITNESS 我方见证代录 / CERTIFICATE_HANDOVER 凭证交出；仅 CONFIRM 动作写入，其余为 NULL';

-- ③ 回执同样记录确认权威，与状态历史互相印证
ALTER TABLE release_receipts
    ADD COLUMN IF NOT EXISTS confirmation_authority VARCHAR(24);

COMMENT ON COLUMN release_receipts.confirmation_authority IS
    '回执的确认权威，取值同 release_state_history.confirmation_authority；source=MANUAL 时一般为 INTERNAL_WITNESS';

-- ④ 覆盖 V19 的旧注释（COMMENT ON 是覆盖语义，不改表结构、不影响 V19 校验和）
COMMENT ON TABLE custodians IS
    '接收方配置。日本新车渠道口径（业务流程文档 §6）：主用 DEALER_WAREHOUSE（放车线）与 CERTIFICATE_DEPOSITORY（放证线）；THIRD_PARTY_WAREHOUSE / ON_SITE_SUPERVISOR 属中国二手车监管假设，与已确认口径冲突，须业务书面确认后方可启用';

COMMENT ON COLUMN release_orders.custodian_id IS
    '主接收方（默认放车线）；各释放项可在 release_order_items.custodian_id 覆盖，未覆盖时继承本列。Q9 建议口径已落地，最终以业务书面确认为准';

COMMENT ON COLUMN release_receipts.confirmed_by IS
    '回执确认人：接收方 partner_code，或 source=MANUAL 时由我方担保管理岗见证代录的自然人账号；两类来源按 source 区分，审计不可混淆';

COMMENT ON COLUMN release_receipts.source IS
    'PORTAL 接收方门户 / API 系统直连 / MQ 消息 / MANUAL 人工转交后由我方代录见证。起步口径：无外部 WMS，MANUAL 为主（设计稿 §7 #6、ADR-009 人工路径一等公民）';
