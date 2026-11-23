-- =====================================================================
-- V78__used_config_seed_r2.sql
-- 用途：种 USED R2（车辆主数据 / 里程勾稽）要读的可调参数。
-- 归属：USED R2（D06 §2.3 里程取严与差异工单 / §16 参数表）。
-- 号段：head 之上取号（V75–V77 已被 R1 占用），理由与 V75 头注相同——
--       库里 head=V74 且未开 out-of-order，低号会让启动校验整栈失败。
--       V32–V39 与 V42–V44 是有意留空/永久空号，不回填（PROGRESS.md §1 号段行）。
--
-- 【本键为什么必须进库而不是 Java 常量】
--   阈值决定「一次里程观测算不算异常」，也就是决定差异工单的产量与放款是否被压住。
--   写成常量后：① 业务想收紧口径要发版；② 冒烟无法证明这个数来自库里——
--   smoke U-R2 段的判据正是「改 param_value ⟹ 同一次观测从『阈值内』变成『开差异』」，
--   常量版永远测不出这一条（R1 的 license-expiry-alert-days 断言同款设计）。
--
-- 【值 1000 的来源与性质】
--   D06 §16 给了键名 floorplan.used.mileage-diff-threshold-km 与「超阈值建 ODOMETER_DIFF」这个动作，
--   数值本身标 [待定源]，未签字。本行是**采纳默认值**（用户 2026-11-23 裁决：
--   未签字口径按 D01 采纳默认值实现 + 外部化成配置键 + 代码落点标 [待定源]），
--   依据是日本二手車市场把 1,000 km 以内视作运输/整备里程的常规容差，超过才有回表嫌疑。
--   业务改口径时：putActive 写新值即可，本文件与代码都不动。
--
-- 【幂等】与 V75 同理：uk_ucp_active_key 是<b>条件</b>唯一索引
--   （ON (param_key) WHERE status='ACTIVE'），冲突目标必须带上同一个 WHERE 谓词，
--   只写 (param_key) 会报 "no unique constraint matching given columns"。
-- =====================================================================

INSERT INTO used_config_params
    (param_key, param_value, value_type, unit, status, effective_on, source_ref, approved_by, note)
VALUES
    ('floorplan.used.mileage-diff-threshold-km', '1000', 'INT', 'km', 'ACTIVE', DATE '2026-11-23',
     'D06 §16 / §2.3；[待定源 D-10]', 'user-adopted-defaults',
     '未签字口径按 D01 采纳默认值实现。语义：|观测里程 − 主数据里程| > 本值 ⟹ 开 wms_reconcile_diffs'
     || '（ODOMETER_DIFF）。**只影响是否开差异，不影响是否采信**——采信由来源优先级决定'
     || '（高优先实物观测胜出即回写主数据，D06 §2.3；低优先票面/申报与主数据不符只建差异不回写，D04 §5.1）。'
     || '本键调成 0 ⟹ 任何差异都开单；调大不等于允许回表，只是不再产生工单，属业务决定。')
ON CONFLICT (param_key) WHERE status = 'ACTIVE' DO NOTHING;
