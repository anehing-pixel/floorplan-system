-- ===========================================================================
-- V20：对账清算（RECON）—— 对拍批次 + 差异明细
-- ===========================================================================
--
-- 【这两张表存什么】
-- 对拍服务只读核心账本表（advances / vehicle_units / credit_lines / repayments /
-- payment_transactions），不写任何账务；它自己的产出只有两样东西：
--   recon_runs  —— 一次对拍的"运行日志"（谁、什么时候、跑了几条规则、发现多少差异）
--   recon_diffs —— 对拍"发现的差异"（哪条规则、对拍对象、期望值、实际值、差异类型）
--
-- 【为什么批次与差异分表】
-- 一次对拍跑 8 条规则，可能产出 0 条差异，也可能产出上千条（全库对拍）。
-- 若把批次信息冗余进每条差异，零差异时连"跑过没有"都无从查起；
-- 而批次表单独一行，可回答"昨天那次到底有没有执行、是成功还是半路失败"。
--
-- 【为什么差异要落库，而不是只返回给调用方】
-- 对账的价值在"可追溯、可复发、可闭环"：
--   · 调用方拿到 HTTP 响应就走，差异必须留痕供财务复核；
--   · 差异有 status（OPEN/CONFIRMED/IGNORED/RESOLVED），需要人工判定与闭环；
--   · 下批次跑出同样的差异，靠 (batch_no, rule_code, subject) 判断是否重复出现。
-- 因此差异表不是日志，是工作流对象——这也是它比运行日志多一列 status 的原因。
--
-- 【为什么唯一约束是 (batch_no, rule_code, subject)，不是自增 id】
-- 同一批次、同一规则、同一对拍对象只允许一条差异。没有这条约束，
-- 重试或重复触发会让同一台车的同一笔差异翻倍，"差异条数"这种指标立刻失真；
-- 而 id 是数据库生成的，重试必然不同，根本拦不住重复。
--
-- 【只读承诺】
-- 本脚本及 recon-service 不修改任何账本表；对拍逻辑全部通过 SELECT 完成。
-- ===========================================================================

CREATE TABLE IF NOT EXISTS recon_runs (
    id           BIGSERIAL PRIMARY KEY,

    batch_no     VARCHAR(64)  NOT NULL UNIQUE,   -- 批次号：RC-日期-随机串，全局唯一
    biz_date     DATE         NOT NULL,          -- 对拍业务日（对哪个时点的账）
    status       VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',
        -- RUNNING 进行中 / SUCCEEDED 正常完成 / FAILED 异常中止

    rule_count   INT          NOT NULL DEFAULT 0, -- 本次实际执行的规则条数（含占位规则计 0）
    diff_count   INT          NOT NULL DEFAULT 0, -- 本次发现的差异条数

    started_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ,                     -- SUCCEEDED/FAILED 时写入
    error_detail TEXT,                            -- FAILED 时的异常摘要

    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 对拍批次按业务日倒序查看（"最近一次对拍是什么时候"是最高频查询）
CREATE INDEX IF NOT EXISTS idx_recon_runs_date   ON recon_runs (biz_date DESC);
CREATE INDEX IF NOT EXISTS idx_recon_runs_status ON recon_runs (status, started_at DESC);

COMMENT ON TABLE  recon_runs IS '对拍批次运行日志。一次对拍一行；只记录执行结果，不承担幂等——幂等锚点是 batch_no 唯一约束';
COMMENT ON COLUMN recon_runs.batch_no IS '批次号：RC-{bizDate}-{随机串}。差异表以此关联，重试必须换新批次号';
COMMENT ON COLUMN recon_runs.biz_date IS '对拍业务日：表示对哪个业务时点的账做一致性检查，不是作业运行日';
COMMENT ON COLUMN recon_runs.status IS 'RUNNING 进行中 / SUCCEEDED 正常完成 / FAILED 异常中止；进程被杀会遗留 RUNNING，可供事后发现';

-- ===========================================================================
-- 差异明细：对拍的产出，带人工闭环状态
-- ===========================================================================

CREATE TABLE IF NOT EXISTS recon_diffs (
    id            BIGSERIAL PRIMARY KEY,

    batch_no      VARCHAR(64)  NOT NULL,
    rule_code     VARCHAR(8)   NOT NULL,          -- R1..R8
    subject       VARCHAR(128) NOT NULL,          -- 对拍对象：VIN / 额度ID / 账户引用码
    expected_value BIGINT      NOT NULL,          -- 期望值（账本另一侧的口径，整数日元或计数）
    actual_value   BIGINT      NOT NULL,          -- 实际值（被对拍侧的口径）
    diff_type     VARCHAR(32)  NOT NULL,          -- BALANCE_MISMATCH / STATE_MISMATCH / CREDIT_MISMATCH / CASH_MISMATCH
    status        VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
        -- OPEN 待复核 / CONFIRMED 已确认差异 / IGNORED 已判定可忽略 / RESOLVED 已修复
    detail        TEXT,                           -- 判定说明（规则口径 + 数值含义），争议时自证

    detected_at   TIMESTAMPTZ  NOT NULL DEFAULT now(), -- 纯函数判定发生的时刻
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_recon_diff UNIQUE (batch_no, rule_code, subject),
    CONSTRAINT fk_recon_diff_run FOREIGN KEY (batch_no) REFERENCES recon_runs (batch_no)
);

-- 差异查询两件套：按批次+规则聚合统计、按状态拉取待办
CREATE INDEX IF NOT EXISTS idx_recon_diffs_batch  ON recon_diffs (batch_no, rule_code);
CREATE INDEX IF NOT EXISTS idx_recon_diffs_status ON recon_diffs (status, detected_at DESC);
CREATE INDEX IF NOT EXISTS idx_recon_diffs_rule   ON recon_diffs (rule_code, detected_at DESC);

COMMENT ON TABLE  recon_diffs IS '对拍差异明细。不可变事实（期望值/实际值写入后不改）+ 可变闭环状态(status)；唯一约束防止同批同规则同对象重复';
COMMENT ON COLUMN recon_diffs.subject IS '对拍对象标识：R1/R2/R4 为 VIN，R3 为 credit_lines.id 字符串；占位规则不产出差异';
COMMENT ON COLUMN recon_diffs.expected_value IS '期望值：来自规则定义的"应有口径"一侧；与账本总额比较，非人工录入';
COMMENT ON COLUMN recon_diffs.actual_value IS '实际值：来自被对拍账本表的"实有口径"一侧';
COMMENT ON COLUMN recon_diffs.status IS 'OPEN 待复核 / CONFIRMED 确认差异 / IGNORED 判定可忽略 / RESOLVED 已修复；由人工驱动，对拍作业只写 OPEN';
COMMENT ON COLUMN recon_diffs.detected_at IS '差异判定时刻（应用侧生成），区别于 created_at 的入库时刻';
