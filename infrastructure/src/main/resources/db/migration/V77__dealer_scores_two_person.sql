-- =====================================================================
-- V77__dealer_scores_two_person.sql
-- 用途：给 dealers 的双维评分补「录入人 / 第二签认人 / 评分批次幂等键」三列与硬约束。
-- 归属：USED R1 G-A4（D06 §1.3）+ D07 §7.1 的 /scores 行（幂等键 DSCR:{dealerId}:{ts}）。
-- 号段：head 之上取号（原因见 V75 头部），原 D04 预留号 V44 保持空号。
--
-- 【为什么必须加列，不能只在服务里判二人】
--   G-A4 的原文是「operating_score 与 stock_quality_score 均为**第二人确认后的终值**」。
--   这是放款闸门要读的判定项，不是一个录入动作的自检。没有存量的录入人/签认人两列，
--   闸门在算 LTV 档时根本无从回答"这个分数是谁给的、有没有第二人"——
--   只能选择「信库里的数」（等于把红线降成软约束）或「每次去翻审计」（跨聚合反查，
--   且审计是文本快照，不能作为判定输入）。所以判定所需的证据必须先能存下来。
--
-- 【为什么用 CHECK 表达】
--   与 ck_val_override_pair（估值人工覆盖必须第二人且不能同人）、
--   ck_ltp_policy_sod（政策批准人≠创建人）、V76 的 ck_dchk_two_person 同一做法：
--   「少一列这条事实就不成立」的成对约束一律落库，Java 里的判定只是给出错误码的那一层。
--   现网 dealers 为 0 行（2026-11-23 重建后），加约束不撞历史数据。
--
-- 【"未评过分"必须允许】
--   CHECK 的第一个析取项是"两个分数都是 NULL"：新建车商本来就还没有评分，
--   若强制非空就无法建档。评分缺失的拒付由闸门判（G-A4 不满足 → AUTH_003/QUAL_001 路径），
--   不靠 NOT NULL 拦在建表时。
-- =====================================================================

ALTER TABLE dealers
    ADD COLUMN IF NOT EXISTS score_set_by VARCHAR(64),
    ADD COLUMN IF NOT EXISTS score_co_signed_by VARCHAR(64),
    ADD COLUMN IF NOT EXISTS scores_confirmed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS scores_run_key VARCHAR(128);

COMMENT ON COLUMN dealers.score_set_by IS
    '双维评分录入人（登录名，来自 AuditActor）。与 score_co_signed_by 成对出现，见 ck_dealers_score_two_person。';
COMMENT ON COLUMN dealers.score_co_signed_by IS
    '双维评分第二签认人。D06 §1.3 G-A4：经营资质分与在库车辆质量分是准入与分档闸门的输入，'
    '单人可改 = 闸门可被一人搬动（P-7）。定源 D07 §7.1 /scores 行，错误码 AUTH_003 同人自证。';
COMMENT ON COLUMN dealers.scores_confirmed_at IS
    '本组分数被二人确认的时点。闸门读它来判断"当前分数是哪一次确认的结果"，'
    '与 dealers.updated_at 不同：后者会被任何档案编辑刷新。';
COMMENT ON COLUMN dealers.scores_run_key IS
    '评分批次幂等键，形状 DSCR:{dealerId}:{ts}（D07 §7.1）。'
    '同一批次重推不重复写入；换一批分数必须给新的 ts，否则会被判为重复请求。'
    '允许 NULL：NULL 表示本次不接受幂等保护（例如人工连续微调），部分唯一索引不约束它。';

ALTER TABLE dealers
    ADD CONSTRAINT ck_dealers_score_two_person
    CHECK (
        (operating_score IS NULL AND stock_quality_score IS NULL)
        OR (score_set_by IS NOT NULL
            AND score_co_signed_by IS NOT NULL
            AND score_co_signed_by IS DISTINCT FROM score_set_by)
    ) NOT VALID;

ALTER TABLE dealers VALIDATE CONSTRAINT ck_dealers_score_two_person;

-- 评分批次幂等：只在给了键时生效（NULL 不参与唯一性），与 uk_val_active / uk_wms_snapshot_active 同法。
CREATE UNIQUE INDEX IF NOT EXISTS uk_dealers_scores_run_key
    ON dealers (scores_run_key) WHERE scores_run_key IS NOT NULL;
