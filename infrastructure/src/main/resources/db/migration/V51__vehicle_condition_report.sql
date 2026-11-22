-- =====================================================================
-- V51__vehicle_condition_report.sql
-- 用途：R2.2 车况票登记（検査票 / 入库复核 / 自盘）+ R6.4 车况证据留存。
-- 归属包：W-U1。新建 public 表数 = +1（`vehicle_condition_reports`，D04 §2.1 已把这张表计在 V51）。
-- 定源：D04 §3.8（列名逐字照抄）。
--
-- 【为什么本表列集与 D04 §3.8 完全一致、一列不多】
--   D04 §3.8 明文：R2.2 的字段范围「以法务/业务确认结果裁剪」，本文全量列出，评审时逐项勾选；
--   未勾选的列建表时删除，但**列名不得改写**。也就是说"少列"是待评审的正常结果，
--   而"多列"没有定源依据——本包不自造字段（尤其不自造 登録番号 / 初度登録 / 発行場所
--   这类需求书未点名的列），避免把未签的业务清单固化进 schema。
-- =====================================================================

CREATE TABLE IF NOT EXISTS vehicle_condition_reports (
    id                          BIGSERIAL    PRIMARY KEY,
    vin                         VARCHAR(32)  NOT NULL,
    report_no                   VARCHAR(64),
    report_type                 VARCHAR(24)  NOT NULL,
    inspected_on                DATE,
    inspector                   VARCHAR(64),
    structure_condition         VARCHAR(16),
    repair_history              VARCHAR(16),
    accident_history            BOOLEAN,
    modification_flag           BOOLEAN,
    interior_condition          VARCHAR(16),
    exterior_condition          VARCHAR(16),
    odometer_km                 INT,
    inspection_expiry_date      DATE,
    recycle_ticket_flag         BOOLEAN,
    compulsory_insurance_expiry DATE,
    automobile_tax_status       VARCHAR(16),
    photo_paths                 TEXT,
    attachment_path             VARCHAR(512),
    attachment_digest           VARCHAR(64),
    source_message_id           BIGINT,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by                  VARCHAR(64),
    CONSTRAINT ck_vcr_report_type CHECK (report_type IN
        ('AUCTION_INSPECTION', 'WMS_INBOUND_RECHECK', 'OWN_AUDIT')),
    -- D00 §8.1 RepairHistoryFlag 定源四值；UNKNOWN 允许入库（票面本身可能就是未知）
    CONSTRAINT ck_vcr_repair_history CHECK (repair_history IS NULL
        OR repair_history IN ('NONE', 'MINOR', 'MAJOR', 'UNKNOWN'))
);

-- 按车取最新票（车况抽屉、R6.4 证据链）
CREATE INDEX IF NOT EXISTS idx_vcr_vin_date
    ON vehicle_condition_reports (vin, inspected_on DESC);
-- CRR:{report_no}（D00 §13.1、D06 §2.5）的唯一约束兜底：同票号重推不落第二行。
-- 做成部分索引：自盘票常无外部票号（report_no 为 NULL），NULL 行不参与去重。
CREATE UNIQUE INDEX IF NOT EXISTS uk_vcr_report_no
    ON vehicle_condition_reports (report_no)
    WHERE report_no IS NOT NULL;

COMMENT ON TABLE vehicle_condition_reports IS '车况票（R2.2 三型：検査票 / 入库复核 / 自盘）。列集=D04 §3.8 全量，
    待法务/业务逐项勾选后删列（只删列、不改列名）。写入口 = UsedVehicleMasterApplicationService.recordConditionReport（UC-R2-2，web(core)）。
    本表**只追加不修改**：更正=新出一张票，历史票保留（同 VIN 多票是正常态，靠 idx_vcr_vin_date 取最新）。';
COMMENT ON COLUMN vehicle_condition_reports.report_no IS '外部票据编号（検査票番号等）。非空时兼作幂等键段 CRR:{report_no}；自盘票可空';
COMMENT ON COLUMN vehicle_condition_reports.report_type IS 'AUCTION_INSPECTION=検査票 / WMS_INBOUND_RECHECK=入库复核 / OWN_AUDIT=自盘（D04 §3.8 三值，D06 §2.1 UC-R2-2）';
COMMENT ON COLUMN vehicle_condition_reports.vin IS '車台番号。逻辑引用 vehicle_units.vin（V1:32 PK），不建物理 FK：
    建票（UC-R2-2）可能先于建账（UC-R2-1）到达（検査票在落札当晚就推送），建 FK 会让合法票被拒收';
COMMENT ON COLUMN vehicle_condition_reports.odometer_km IS '实车/票面里程观测值，与 vehicle_units.mileage（主数据权威）勾稽。
    差异超阈值只建差异工单、**不回写主数据**（D04 §5.1 三里程名分工 + D06 §2.3 禁止静默覆盖）；
    阈值 floorplan.used.mileage-diff-threshold-km 是 [待定源]，由配置读取，不在本包硬编码';
COMMENT ON COLUMN vehicle_condition_reports.structure_condition IS 'フレーム状況（车架状态）。取值集未定源 → 本列不建 CHECK，避免自造枚举';
COMMENT ON COLUMN vehicle_condition_reports.interior_condition IS '内装状態（同上，取值集未定源）';
COMMENT ON COLUMN vehicle_condition_reports.exterior_condition IS '外装状態（同上，取值集未定源）';
COMMENT ON COLUMN vehicle_condition_reports.repair_history IS '修復歴判定（D00 §8.1）。与主数据不一致时按"MAJOR 优先、UNKNOWN 不优于已知"取严（D06 §2.3），
    取严动作发生在应用层，本列只存票面事实';
COMMENT ON COLUMN vehicle_condition_reports.recycle_ticket_flag IS 'リサイクル券（回收费预缴凭证）持有标志——日本二手車过户的前置要件';
COMMENT ON COLUMN vehicle_condition_reports.compulsory_insurance_expiry IS '自賠責保険到期日';
COMMENT ON COLUMN vehicle_condition_reports.automobile_tax_status IS '自動車税状况。取值集未定源 → 不建 CHECK';
COMMENT ON COLUMN vehicle_condition_reports.photo_paths IS '4 方位照片本地路径（JSON 数组文本，U-R12：一期本地文件，不接对象存储）';
COMMENT ON COLUMN vehicle_condition_reports.source_message_id IS '入站报文溯源（逻辑引用 integration.inbound_messages.id，属 R11/W-U6 号段 V72，本包不建 FK）★证据链一环';
