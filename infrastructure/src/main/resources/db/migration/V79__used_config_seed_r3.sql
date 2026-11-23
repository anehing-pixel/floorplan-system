-- =====================================================================
-- V79__used_config_seed_r3.sql
-- 用途：种 USED R3（多源审慎估值）要读的三枚参数，并回正 V78 一处错误的 source_ref。
-- 归属：USED R3.1 / R3.4（D06 §16 参数表 + D08 §3.2/§3.3/§3.4）。
-- 号段：head 之上取号（V75–V78 已被 R1/R2 占用）。
--       ⚠ V79 是 D04 §2 划给 USED 的 V40–V79 整段的**最后一号**。本号被 R3 用掉后，
--       处置变现（R10）与报表灰度（R14）改用 V80 起，即 USED 段实际向右延伸一格。
--       这条边界变更必须同步进 PROGRESS.md §1 号段登记行，否则下一个取号的人
--       会以为 V79 还空着 —— 那正是「两个 V24 撞车」的重演。
--
-- 【为什么这三枚要进库而不是 Java 常量】
--   与 V75/V78 同一个判据：**决定「钱」或「是否开单」的阈值一律外置**。
--     · valuation.valid-days 决定一笔估值哪天失去放款资格（闸门输入）；
--     · manual-override-max-uplift 决定人工能把基准抬高多少（SOD 的上限本身）；
--     · default-prudent-rule 决定审慎值是取小还是加权（放款基准的算法）。
--   写成常量后冒烟无法证明「这个数来自库里」，也就无法证伪「代码里偷偷换了口径」。
--   R1 的 license-expiry-alert-days、R2 的 mileage-diff-threshold-km 都是同一手法。
--
-- 【为什么 D06 §16 的另外两枚不种】
--   valuation.require-second-source-for-advance=false：
--     D08 §3.2 的「除人工覆盖外，单源不得作放款基准」是与 Q21 同族的**硬策略**，
--     给它一个能置为 false 的键 = 留一条把硬策略软放行的可配置旁路（AGENTS.md §8 明令警惕的形状）。
--     业务要改这条口径应当改 D08 §3.2 并签字，不是在库里翻开关。
--   valuation.default-max-ratio-fallback=0.40：
--     兜底成数已经有权威源 = ltv_policies.default_ratio（带版本、带二人批准、带 L3 校验）。
--     再种一枚全局默认值，就有了「政策没配就用配置」的第二条路径，
--     而 D08 §4.2 的 L2 要求恰恰是「政策未给兜底 ⟹ 零命中必须判 LTV_002 阻断」。
--     种下它等于把阻断改成放行。
--   两枚都是**刻意不种**，不是遗漏；登记在 PROGRESS.md §4.1 的待签字清单里。
--
-- 【幂等】uk_ucp_active_key 是条件唯一索引（ON (param_key) WHERE status='ACTIVE'），
--   冲突目标必须带上同一个 WHERE 谓词，只写 (param_key) 会报
--   "no unique constraint matching given columns"（V75/V78 同注释）。
-- =====================================================================

INSERT INTO used_config_params
    (param_key, param_value, value_type, unit, status, effective_on, source_ref, approved_by, note)
VALUES
    ('floorplan.used.valuation.valid-days', '45', 'INT', 'day', 'ACTIVE', DATE '2026-11-23',
     'D06 §16；D08 §3.3；[待定源 D-05]', 'user-adopted-defaults',
     '未签字口径按 D01 §3.11 采纳默认值实现（45 日）。语义：valid_until = valuation_date + 本值，'
     || '日粒度（V55 两列都是 DATE，故当天仍有效、次日起失效）。'
     || '改本值**不回算存量行**的 valid_until：已有结论的有效期是当时那次估值的产物，'
     || '回算等于改写历史（V55 表注 P20）。业务若要「新口径即刻作用于存量」，走重估而不是改键。')
    ,
    ('floorplan.used.valuation.manual-override-max-uplift', '1.15', 'DECIMAL', 'ratio', 'ACTIVE',
     DATE '2026-11-23', 'D06 §16；D08 §3.4（上限 115%，无待定源标记）', 'user-adopted-defaults',
     '语义：manual_value <= 可用自动源最大值 × 本值，超过回 VALU_005。'
     || '下限不设限（D08 §3.4：更保守的方向不拦）。判定在 PrudentValueCalculator.overrideCeiling，'
     || '本键只出数 —— Java 里不硬编码 1.15，否则调这个数要发版，而它是风控可调的口径。'
     || '置 1.00 表示「人工只能等于自动源」；置小于 1 会让覆盖只能往下压，两者都是合法业务选择。')
    ,
    ('floorplan.used.valuation.default-prudent-rule', 'MIN_OF_SOURCES', 'STRING', '', 'ACTIVE',
     DATE '2026-11-23', 'D06 §16；D08 §3.2（默认为取小，加权只能由政策改写）', 'user-adopted-defaults',
     '语义：请求未显式给 prudentRule 时按本值算审慎值。取值域 = PrudentValueRule = '
     || '{MIN_OF_SOURCES, WEIGHTED}。WEIGHTED 需要每条输入带 weight，'
     || '且权重和不为 1 时按实际和归一（D08 §3.2），不做「缺权重补默认值」的静默兜底。'
     || '本键改成 WEIGHTED 是全局算法切换，改动前必须确认存量政策的权重数据齐备。')
ON CONFLICT (param_key) WHERE status = 'ACTIVE' DO NOTHING;

-- ---------------------------------------------------------------------
-- 回正 V78 的 source_ref：[待定源 D-10] 是误标。
-- D01 §5.1 的待定源编号到 D-09 为止（§5.2 另有 U-01~U-13），不存在 D-10；
-- 「D-10」在 D10 §5 指的是「计算书编制↔签认」那对 SOD，与本键无关。
-- 本键的定值出处是 D06 §2.5 配置行原文（mileage-diff-threshold-km=1000），**没有待定源标记**，
-- 因此它不是「等业务签字的默认值」，而是设计已给的定值 —— 这两件事在待签字清单上必须分开，
-- 否则业务方会在一堆真正待裁决的项里替一个已定值签字。
-- 已应用的迁移不可改（Flyway checksum），所以用一次 UPDATE 回正，V78 原样保留。
-- ---------------------------------------------------------------------
UPDATE used_config_params
SET source_ref = 'D06 §2.5 配置行（定值 1000，文档未标待定源）/ §2.3 勾稽规则',
    updated_by = 'used-r3',
    updated_at = now()
WHERE param_key = 'floorplan.used.mileage-diff-threshold-km'
  AND status = 'ACTIVE';

COMMENT ON COLUMN used_config_params.source_ref IS
    '取值的出处（文档号 + 是否待定源）。⚠ 待定源编号只认 D01 §5.1 的 D-01~D-09 与 §5.2 的 U-01~U-13；'
    '标了不存在的编号会把已定值挂进待签字清单（V78 的 D-10 就是这么错的，V79 回正）。';
