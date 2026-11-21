-- 库存融资核心系统 · V3 schema
-- 授信评级分级口径（后台动态配置）。
--
-- ⚠️【预设】分级口径待客户确认 —— 因此口径数据一律不写死在代码里，
-- 由本表承载；运营通过 /api/rating-configs 管理接口随时调整，
-- 调整即时生效（下一次垫款建账即按新口径计算利率），无需发版重启。
--
-- 字段说明：
--   grade          等级代码（主键）：A / B / C / D ...（1~8 位大写字母或数字）
--   grade_name     等级名称（展示用，如"优质 / 标准 / 关注 / 观察"）
--   rate_surcharge 年化利率加点（小数：0.005 = +0.5%）。
--                  实际执行利率 = 基准年利率(floorplan.interest.default-annual-rate) + rate_surcharge。
--                  加点上限由应用配置 floorplan.rating.max-rate-surcharge 控制（预设 0.05）。
--   allowed        该等级是否准入"新业务"：false = 停用（该等级经销商不再受理新增垫款建账；
--                  存量车辆的还款/结清等存量业务不受影响）。
--   sort_order     展示排序
--   updated_at/by  最后变更时间与操作人（口径变更是授信敏感操作，必须留痕）
-- ============================================================
CREATE TABLE rating_configs (
    grade          VARCHAR(8)   PRIMARY KEY,
    grade_name     VARCHAR(64),
    rate_surcharge NUMERIC(6,4) NOT NULL DEFAULT 0,
    allowed        BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order     INT          NOT NULL DEFAULT 0,
    remark         VARCHAR(255),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by     VARCHAR(64)
);

-- 预设种子口径（⚠️ 待客户确认后直接通过管理接口修改，无需改代码）
INSERT INTO rating_configs (grade, grade_name, rate_surcharge, allowed, sort_order, remark, updated_by) VALUES
    ('A', '优质（预设）', 0.0000, TRUE,  1, '预设：执行基准利率，待客户确认', 'system'),
    ('B', '标准（预设）', 0.0050, TRUE,  2, '预设：基准 + 0.5%，待客户确认', 'system'),
    ('C', '关注（预设）', 0.0100, TRUE,  3, '预设：基准 + 1.0%，待客户确认', 'system'),
    ('D', '观察（预设）', 0.0200, FALSE, 4, '预设：基准 + 2.0%，暂不准入新业务，待客户确认', 'system');
