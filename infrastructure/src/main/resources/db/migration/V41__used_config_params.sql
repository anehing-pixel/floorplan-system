-- =====================================================================
-- V41__used_config_params.sql
-- 用途：USED 域可调参数的**结构载体**（表结构只骨架，零种子行）。
-- 归属包：W-U1。定源：D04 §2（V41 行）+ §11（参数落 DB 还是落 yml）+ D00 §12 + D06 §1.5 / §2.5。
--
-- 【为什么建这张表，却不放任何数值】
--   本轮唯一可先行的是"编号无关的骨架"（表结构 / 状态机 / 权限点 / SOD / 错误码 / 写集）；
--   而 D00 §12 与 D06 §1.5/§2.5 里的 180 天、1,000km、±15%、30 天预警窗口全部挂 [待定源]
--   （D-02 / D-05 / D-06 / U-02 / U-11），签字前**一个都不许落库**，也不许写进 Java 常量。
--   没有这张表，代码里就没有"从参数表读"这条路，未签数值只会被硬编码——
--   那才是真正不可回收的口径污染。所以：结构先落，值等签认。
--
-- 【读侧接线】UsedConfigRepository（infrastructure）+ 应用层按需读取：
--   读到 ACTIVE 行 → 用库内值；读不到 → 用请求显式传入值；两者都没有 → 按 fail-closed 处理
--   （例：合规核查无 valid_until 时不判通过，P-7 / V25 同款默认）。
--   本包**不往 web/application.yml 加 floorplan.used.* 默认值**：那等于把未签数值换个地方硬编码。
--
-- 【D04 §2.1 条件项已触发】本文件落的是"DB 参数表"分支 ⟹ 本包 public 新建表 +1，
--   分支门槛不再是卡面写的 73（详见 docs/AGENT_TASK-W-U1-实现说明.md 的门槛差异一节）。
-- =====================================================================

CREATE TABLE IF NOT EXISTS used_config_params (
    id           BIGSERIAL    PRIMARY KEY,
    param_key    VARCHAR(64)  NOT NULL,
    param_value  VARCHAR(255) NOT NULL,
    value_type   VARCHAR(16)  NOT NULL DEFAULT 'INT',
    unit         VARCHAR(16),
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    effective_on DATE,
    expired_on   DATE,
    source_ref   VARCHAR(64),
    approved_by  VARCHAR(64),
    note         VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   VARCHAR(64),
    updated_by   VARCHAR(64),
    version      INT          NOT NULL DEFAULT 0,
    CONSTRAINT ck_ucp_value_type CHECK (value_type IN ('INT', 'BIGINT', 'DECIMAL', 'STRING', 'BOOLEAN')),
    CONSTRAINT ck_ucp_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED'))
);

-- 同一参数同时只允许一个生效值：这是 D04 §11「风控口径参数需要启停版本 + 生效区间」的最小组合，
-- 也是参数变更可回溯的前提（旧值转 RETIRED，不删行——本表禁止 DELETE，与全库口径一致）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_ucp_active_key
    ON used_config_params (param_key)
    WHERE status = 'ACTIVE';
-- 参数台账列表（后台按状态筛）
CREATE INDEX IF NOT EXISTS idx_ucp_status_key
    ON used_config_params (status, param_key);

COMMENT ON TABLE used_config_params IS 'USED 域可调参数表（R13.4 的"可调参数必须双层约束"里的数据侧那一半）。
    **本轮零种子行**：D00 §12 里标注 [待定源] 的默认值（D-02/D-05/D-06/U-02/U-11）都还没签字，
    按边界纪律不实现任何未签字数值，因此不插种子；已签字的 §12 键走 yml 载体（配置优先于表，两种载体可互换），
    本表只承载"需要运行时改且需要审计留痕"的那部分。
    签字后由**独立迁移**补种子，且每行必须写 source_ref 指明定源编号——无源号的数值不许进本表。
    写入口属后台配置域（权限点 CONFIG_MAINTAIN）；读侧当前接线见 UsedConfigRepository。';
COMMENT ON COLUMN used_config_params.param_key IS '参数键，命名沿用 D00 §12 的统一前缀 floorplan.used.（§12 明写与 LEGACY 配置物理隔离）。
    ★ 更正（2026-11-22 自查）：§12 现表内**没有**下列五个 R1 键（只给了 valuation/ltv/settlement/release 等段），
    本列把它们列出的唯一出处是 V41 自身，属"包内暂定名"，需收口方在 §12 补登记后才能落种子行。
    已知待签键位（**只列名不列值**）：floorplan.used.compliance-valid-days[D-02]、
    floorplan.used.license-expiry-alert-days、floorplan.used.mileage-diff-threshold-km、
    floorplan.used.master-data-diff-alert-enabled、
    floorplan.used.dealer-rating-two-person-required（红线，签认后必须为 true）';
COMMENT ON COLUMN used_config_params.value_type IS '值类型：应用层据此解析，避免把"1000"当字符串比大小。DECIMAL 用于比例/阈值，金额为 BIGINT 整数最小单位';
COMMENT ON COLUMN used_config_params.status IS 'DRAFT=已录入未生效（签字流程中间态）/ ACTIVE=生效（同键唯一）/ RETIRED=已停用（保留历史，不删行）';
COMMENT ON COLUMN used_config_params.effective_on IS '生效日；判定读参数时必须带"当前日期落在 [effective_on, expired_on) 内"，否则等于给了个随时可改的数';
COMMENT ON COLUMN used_config_params.source_ref IS '定源编号（如 D-02、U-11）。★ 本列是"这行数值凭哪份签字进来"的证据，NOT NULL 与否由评审定，暂可空但空值行不得置 ACTIVE（应用层校验）';
COMMENT ON COLUMN used_config_params.approved_by IS '批准人（D04 §11 要求参数变更"二人审批 + 审计"；单靠 updated_by 无法把"改的人"和"批的人"分开）';
COMMENT ON COLUMN used_config_params.param_value IS '参数值（字符串存储，按 value_type 解析）。
    ★ 禁止把本列当兜底：读不到 ACTIVE 行时应用层不得猜默认值，按 fail-closed 走（见 V41 头注）';
