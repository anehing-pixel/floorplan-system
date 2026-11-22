-- =====================================================================
-- V53__certificate_constraint.sql
-- 用途：R2.5「一车一证」与换证链的**约束层**（表与列已在 V52 建好）。
-- 归属包：W-U1。新建 public 表数 = 0（本文件只建索引与约束，D04 §2 批次计划第 V53 行）。
--
-- 【为什么单独成文】D04 §2 把「约束」与「建表」分两批：建表批次可反复调试，
-- 约束批次一旦上线就成为业务不变量。分开写还能让"索引没建成功"与"表结构不对"
-- 在 Flyway 报错里一眼可分。V52 里刻意不建本索引，正是为了避免 V53 退化成空文件（AGENTS §6.1）。
--
-- 【语法纪律】部分唯一索引只能表外 CREATE UNIQUE INDEX ... WHERE ...
--   （表内 UNIQUE (...) WHERE 不是合法 PG 语法，V19 就是这么炸掉整个迁移链的）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 一车一证（R2.5 验收点，D04 §3.9）
-- ---------------------------------------------------------------------
-- 语义：同一 VIN 在任意时刻最多只有一张"还在场"的証書。
--   在场 = status NOT IN ('REISSUED','LOST')：
--     · REISSUED = 已被换出的旧证（UC-R2-5 换证链的另一半）；
--     · LOST     = 灭失申报后的旧证（走再発行，另立新证成链）。
--   其余四态（RECEIVED / PLEDGED / RELEASE_PENDING / RELEASED）都算在场，
--   所以 RELEASED（已交还经销商）仍占一坑：证已交还≠历史可抹，
--   同一 VIN 再插一张 RECEIVED 必须先把 RELEASED 那张转成 REISSUED 或 LOST。
--   ——这条口径来自 D03 §3.2 的六态定义（RELEASED 不是终态清理位），
--     若业务希望 RELEASED 让位，属**状态语义变更**，需签认后改本谓词，本包不自决。
CREATE UNIQUE INDEX IF NOT EXISTS uk_cert_active_vin
    ON inspection_certificates (vin)
    WHERE status NOT IN ('REISSUED', 'LOST');

-- ---------------------------------------------------------------------
-- 2. V50 推迟到这里的物理外键
-- ---------------------------------------------------------------------
-- vehicle_units.inspection_certificate_id 列在 V50 建（USED 主数据属性），
-- 但它指向的 inspection_certificates 到 V52 才存在，故 FK 只能在 V53 补——
-- 这是号段顺序决定的，不是遗漏（已在 V50 的列注释里写明）。
-- 存量行该列为 NULL，NULL 不违反 FK，因此直接 ADD CONSTRAINT，不需要 NOT VALID 两步。
ALTER TABLE vehicle_units
    DROP CONSTRAINT IF EXISTS fk_vehicle_units_certificate;

ALTER TABLE vehicle_units
    ADD CONSTRAINT fk_vehicle_units_certificate FOREIGN KEY (inspection_certificate_id)
        REFERENCES inspection_certificates (id);

-- ---------------------------------------------------------------------
-- 3. 换证链的自引用护栏
-- ---------------------------------------------------------------------
-- 旧证不能指向自己（数据错误会导致链遍历死循环）；
-- 新证必须能追溯 predecessor_id，但"首证无 predecessor"是正常态，故只排自身。
ALTER TABLE inspection_certificates
    DROP CONSTRAINT IF EXISTS ck_cert_predecessor_not_self;

ALTER TABLE inspection_certificates
    ADD CONSTRAINT ck_cert_predecessor_not_self CHECK (predecessor_id IS NULL
        OR predecessor_id <> id);

-- 换证/灭失必须留下时间戳：状态与时间戳不一致会让证据链出现"有结果无时点"的洞。
-- 只约束"进入该态必须有时间"，不回溯改历史（本表 V52 才建，无存量行，可安全加 CHECK）。
ALTER TABLE inspection_certificates
    DROP CONSTRAINT IF EXISTS ck_cert_status_timestamps;

ALTER TABLE inspection_certificates
    ADD CONSTRAINT ck_cert_status_timestamps CHECK (
        (status <> 'PLEDGED'  OR pledged_at  IS NOT NULL)
        AND (status <> 'RELEASED' OR released_at IS NOT NULL)
        AND (status <> 'LOST'     OR lost_at     IS NOT NULL));

COMMENT ON INDEX uk_cert_active_vin IS
    'R2.5 一车一证的数据库兜底：应用层查重（UsedVehicleMasterApplicationService）之外必须有本索引，
    否则并发建账会插出两张有效証書（select-then-insert 竞态）。违反时接口回 CERT_002（D07 §8）。';
COMMENT ON INDEX idx_cert_expiry IS '車検満了日扫描（R5.2 到期预警）——预警窗口天数 [待定源]，本索引只保证扫得动，不含任何天数口径';
