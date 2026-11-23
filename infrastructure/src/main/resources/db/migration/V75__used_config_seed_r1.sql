-- =====================================================================
-- V75__used_config_seed_r1.sql
-- 用途：把 USED R1（车商资质）要读的三个可调参数落进 used_config_params。
-- 归属：USED R1（D06 §1.5 / §16）。
--
-- 【号段为什么是 V75 而不是 D04 §2 预留的 V42】
--   D04 把 V42–V44 划给本包，但库里 V45–V74 已经落过（历史 head = 74），而本仓库的 Flyway
--   没开 out-of-order（web/application.yml 只有 enabled/locations/baseline-on-migrate）。
--   低号未落库 + 高号已落库 ⟹ 启动期校验直接抛
--   "Detected resolved migration not applied to database: 42"，整个服务起不来。
--   所以新迁移一律取 head 之上的号（V75–V77）；V42–V44 留作永久的空号，不要回填。
--
-- 【为什么现在可以种值，而 V41 一行都不种】
--   V41 的立场是「D00 §12 / D06 §16 里标 [待定源] 的数值一个都不许落库，也不许写进 Java 常量」。
--   本仓库当前的执行口径由用户裁决改为<b>「按 D01 采纳默认值实现，未签字项外部化成配置键并挂 [待定源] 标记」</b>。
--   两者并不冲突，冲突的是载体：
--     · 写进 Java 常量  ⟹ 改一次值发一次版，且代码里出现无源数字（V41 反对的就是这个）；
--     · 写进 application.yml ⟹ V41 明确反对（「换个地方硬编码」），且改值要重启；
--     · 写进本表        ⟹ 每个值都带 source_ref / approved_by / 生效区间，运行时可改、可回溯、可一键回退旧值。
--   因此本文件是「用户裁决优先于 V41 的不种值立场」的落点，偏差已写进 PROGRESS.md。
--   业务一旦改口径：putActive 写一行新值，旧行自动转 RETIRED——不需要改代码、不需要发版。
--
-- 【只种代码真正会读的键】
--   D06 §16 全表 28 个键，本文件只给 R1 用到的 3 个。
--   理由：AGENTS.md §12「表建了就算功能有了」的同型问题在这里是「参数种了就算口径落地了」——
--   未实现的键先种进表，读它的人不存在，收口时无法判断这个值是"待接线"还是"已废弃"。
--   其余键随各自切片落地时单独登记（估值/LTV、放车/WMS、集成各一批，号按当时 head 往上取，不回填空号）。
--
-- 【幂等】ON CONFLICT DO NOTHING：本表禁 DELETE 且 ACTIVE 键有<b>条件</b>唯一索引
--   （uk_ucp_active_key ON (param_key) WHERE status='ACTIVE'），所以冲突目标必须带上同一个
--   WHERE 谓词才能被 PG 推断出来；只写 (param_key) 会直接报
--   "there is no unique constraint matching given columns for the inference of ON CONFLICT"。
--   重复执行同一迁移在「手工补跑」场景下不应把已调过的值刷回默认。
-- =====================================================================

INSERT INTO used_config_params
    (param_key, param_value, value_type, unit, status, effective_on, source_ref, approved_by, note)
VALUES
    -- G-A3 合规结论的默认有效期：内勤/入站未显式给 valid_until 时按 checked_on + 本天数推导。
    -- 库内缺失时应用层 fail-closed（要求显式期限），不回落硬编码 180。
    ('floorplan.used.compliance-valid-days', '180', 'INT', 'days', 'ACTIVE', DATE '2026-11-23',
     'D06 §16 / §1.5；[待定源 D-02]', 'user-adopted-defaults',
     '未签字口径按 D01 采纳默认值实现。判定：valid_until IS NULL 本身仍不判通过（P-7 fail-closed）。'),

    -- 许可/会员到期预警窗口：判定为 EXPIRING 的天数范围（D03 §2.1 规则 4）。
    ('floorplan.used.license-expiry-alert-days', '30', 'INT', 'days', 'ACTIVE', DATE '2026-11-23',
     'D06 §16（无待定源标记，属已定值）', 'user-adopted-defaults',
     'EXPIRING 不阻断放款，只产预警；改成 0 等于关闭预警，属业务决定，不在代码里硬压。'),

    -- 双维评分二人签认：G-A4 的红线开关。本键存在是为了让「红线有没有被关掉」在库里可见，
    -- 而不是靠代码评审记住"这里不许改成 false"。
    ('floorplan.used.reporting.dealer-rating-two-person-required', 'true', 'BOOLEAN', NULL, 'ACTIVE',
     DATE '2026-11-23', 'D06 §16 红线（标注"不可关"）；D08 §3.4/§1', 'user-adopted-defaults',
     '★ 红线：应用层读到 false 时仍按 true 执行并打 ERROR 日志（D06 §16 明写"不可配置为 false"）。'
     '保留本键的目的不是提供关闭能力，而是让"有人试图关闭"这件事在库里留下痕迹。')
ON CONFLICT (param_key) WHERE status = 'ACTIVE' DO NOTHING;
