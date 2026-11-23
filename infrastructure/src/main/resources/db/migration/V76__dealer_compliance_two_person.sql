-- =====================================================================
-- V76__dealer_compliance_two_person.sql
-- 用途：给 dealer_compliance_checks 补「第二签认人」列，并把 D07 §3.8 的二人规则落成库内硬约束。
-- 归属：USED R1（D06 §1.2 UC-R1-2 / D07 §7.1 的 compliance-checks 行）。
-- 号段：原计划用 D04 §2 的段内缓冲 V43，但库里 head 已是 V74 且未开 out-of-order，
--       低号会让启动校验失败 ⟹ 改为 head 之上取号（详见 V75 头部说明）。
--
-- 【为什么现有表结构不够】
--   D07 §3.8（I-11 反社/征信结果入站）与 §7.1 都要求：
--     source = MANUAL ⇒ 要求 evidence_path ＋ 二人签认。
--   V45 建表时只有 checked_by（第一人），没有第二人列，也没有任何约束把
--   「人工录入」和「有人复核」绑在一起 —— 于是「内勤自己查、自己填、自己说通过」
--   在库里是完全合法的一行。这正是 AGENTS.md §8 警惕的形态：
--   合规闸门（G-A3）的结论可以由一个人独立造出来。
--
-- 【为什么用 CHECK 而不是只在服务里判】
--   与 valuation 的 ck_val_override_pair、wms_reconcile_diffs 的 IGNORED 三件套同法：
--   二人签认属于「少一列就作废」的事实，落在库里才能保证任何写入路径（含未来的
--   批量导入、手工补数据）都绕不过去；只写在 Java 里，多一个入口就多一个洞。
--   本表当前 0 行，加约束不会撞上历史数据。
--
-- 【不改 V45】已落库迁移禁改（checksum，AGENTS §6.1）。
-- =====================================================================

ALTER TABLE dealer_compliance_checks
    ADD COLUMN IF NOT EXISTS co_signed_by VARCHAR(64);

COMMENT ON COLUMN dealer_compliance_checks.co_signed_by IS
    '第二签认人（登录名）。source=MANUAL 时必填且必须不同于 checked_by；'
    'PARTNER_API / CREDIT_BUREAU 的结论来自外部权威源，本行不由本行员工产生，故免二人（留空）。'
    '定源：D07 §3.8（I-11 入站）与 D07 §7.1（compliance-checks 行，错误码 AUTH_003 同人自证）。';

-- 人工录入必须有第二人，且不能是自己：
--   CHECK 写成「非 MANUAL 放行 ∨（有第二人 ∧ 第二人≠第一人）」。
--   用 IS_DISTINCT_FROM 而不是 <>：两者在 NULL 上行为不同，而 checked_by 理论可为 NULL
--   （定时作业代录），此时 <> 会得到 NULL 从而被 CHECK 当成「未判定=通过」，
--   IS_DISTINCT_FROM 则恒为 TRUE，约束方向才是收紧的。
ALTER TABLE dealer_compliance_checks
    ADD CONSTRAINT ck_dchk_two_person
    CHECK (
        source IS DISTINCT FROM 'MANUAL'
        OR (co_signed_by IS NOT NULL AND co_signed_by IS DISTINCT FROM checked_by)
    ) NOT VALID;

-- 单独 VALIDATE：与 V40/V65 同样的两段式写法，好处是校验失败时能定位到这一句，
-- 而不是整条 ALTER 回滚后只看到一句 "check constraint violated"。
ALTER TABLE dealer_compliance_checks VALIDATE CONSTRAINT ck_dchk_two_person;
