-- ===========================================================================
-- V70：integration schema + DMZ 专属角色 floorplan_dmz（W-U3 / R11.1 / D02 INV-1）
-- ===========================================================================
--
-- 【本文件解决什么】
-- 外部 partner 的报文入口 `external-api`（:18090）住在 DMZ 区，它必须**只能看见集成队列，
-- 不能看见业务库**。R11.1 要求落地 ADR009 的五层纵深，其中第三层（进程隔离）在数据侧的唯一
-- 可断言形态就是「一个对 public 零权限的 DB 角色」。所以本文件不是建表的配角，
-- 它是 INV-1 的实现本体：D11 §4 的证伪实验（以 floorplan_dmz 执行
-- `SELECT count(*) FROM public.vehicle_units` 必须 permission denied）判的就是这里的授权面。
--
-- 【为什么同库分 schema 而不是独立实例】
-- D00 §5.2：一期以「PG 角色 + schema 授权」在同一实例内做出**可断言**的边界，
-- 生产物理分库列为部署加固项（D02 §8.2），不改任何代码。
-- 因此本文件里所有语句都必须满足：分库后仍然成立、或分库后由 DBA 等价重建。
--
-- 【三条执行前置条件（本轮按用户指令未连库，属 DBA 待办，见实现说明）】
--   1) `CREATE ROLE` 需要超级用户或 CREATEROLE 权限。本地由 docker 初始化的 floorplan 用户满足；
--      生产若 Flyway 连接的不是超级用户，这一句会失败——此时角色必须由部署预置，
--      本文件的 DO 块因 `IF NOT EXISTS` 判断而自动跳过（这正是 DO 块的目的）。
--   2) 角色与授权是**库级对象**，不随并行分支的 agent schema 复制（D04 §1.3），
--      所以 INV-1 的最终结论只在主干回归那一次算，分支绿灯不算。
--   3) `ALTER DEFAULT PRIVILEGES` 只对「该语句执行者」后续创建的表生效（D04 §4.1 已警示）。
--      本文件不依赖它来给六张开权限——六张表的权限在 V71–V74 里逐表显式授予，
--      这条默认权限只兜「以后有人新增 integration 表时忘了授权」的情形，
--      且刻意只发 SELECT：漏授权的症状是 DMZ 报 permission denied（响亮），
--      而不是悄悄多出一个可写面（沉默）——要的是前者。
-- ===========================================================================

-- 集成消息库：六张表（partners / partner_credentials / inbound_messages / nonces /
-- outbound_tasks / call_logs）全在此 schema，public 表数基准不受本包影响（D04 §2.1）。
CREATE SCHEMA IF NOT EXISTS integration;

-- COMMENT ON 的 IS 只接受**字符串字面量**，不接受表达式：写成 'a' || 'b' 会在
-- 干净 schema 上直接 "syntax error at or near \"||\"" 并让整条 Flyway 链断在这里。
-- 2026-11-23 串行第 3 棒在全新 used_u3 schema 上实测到：主干 V1–V64 全部 success=t
-- （V65–V69 属 W-U4，本轮未取入），V70 起不来。PostgreSQL 的 DDL 与 schema_history 插入
-- 同事务回滚，所以**历史表里连一行失败记录都不留**——排障时只看 history 会误判成
-- "V70 没被解析到"，实际是跑过并失败了。长注释就用一个字面量，不要靠 || 拼。
COMMENT ON SCHEMA integration IS
    '集成消息库（R11）：partner 注册、入站收件箱、防重放、出站任务、调用留痕。不是业务库——六表不含任何业务判定字段，业务语义只由 core 解释（D02 §5.2 共同约束 ①）。';

-- ---------------------------------------------------------------------------
-- DMZ 专属角色
-- ---------------------------------------------------------------------------
-- 为什么用 DO $$ 包裹：CREATE ROLE 没有 IF NOT EXISTS，而迁移必须幂等
-- （历史上 V17/V19/V20/V21 全靠幂等写法才能 out-of-order 补齐）。
--
-- ⚠️ 关于 PASSWORD 'floorplan'：这是 D04 §4.1 定稿的写法（与主干既有迁移同形），
--    只在「角色不存在时」生效。它与 D12 W-U3 硬约束 ③「密钥不落 yml/表/代码」有张力，
--    D13 §4.5 WU3-14 的结论是「角色/密码由部署预置，Flyway 只校验存在与授权」。
--    本轮**不擅自改动文档定稿的语句**，改为在两处留痕：
--      ① 本注释（仓库内可见，评审可见）；
--      ② docs/AGENT_TASK-W-U3-实现说明.md 的「待裁决项」清单；原来登记的
--         「V70 'floorplan' 与 external-api/…/application.yml 的 FLOORPLAN_DMZ_DB_PASSWORD
--          默认值不一致（两处都取默认值则 DMZ 连不上库）」已在 W-U3 内消解——
--          yml 侧默认值改为**不给**（口令一律由部署注入），本 DDL 定稿语句保持不动。
--    生产落地时该角色应由 DBA 以部署注入的密码创建，本 DO 块因角色已存在而跳过。
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'floorplan_dmz') THEN
    CREATE ROLE floorplan_dmz LOGIN PASSWORD 'floorplan';   -- 本地与现状同密；生产由部署注入
  END IF;
END $$;

-- 关键防线（INV-1 的正身）：对 public **一律不授权**，且显式撤销——
-- 只靠「不授」不够，因为 public schema 在 PG<15 默认对 PUBLIC 可建表，
-- 且角色可能通过成员关系继承权限，所以三条撤销都要写：
--   ① schema 级（能不能进这个 schema、能不能在里面建对象）
--   ② 已存在表的对象级（能不能读/写现有业务表）
--   ③ 默认权限（以后新建的业务表会不会自动带上权限）
REVOKE ALL ON SCHEMA public FROM floorplan_dmz;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM floorplan_dmz;
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM floorplan_dmz;

-- 同样撤销序列与函数：业务库里的 BIGSERIAL 序列与 V5 装的 pgcrypto 函数都不属于 DMZ 面。
-- 这两条不在 D04 §4.1 的规格文本里，但与「DMZ 不持核心库连接」同一判据，
-- 少了它们，DMZ 仍可 nextval('public.xxx_id_seq') 探到业务库形状。属保守收紧，不改变任何正向行为。
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM floorplan_dmz;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM floorplan_dmz;

-- 进入 integration 的门票。
-- ⚠️ USAGE 只允许「解析该 schema 下的对象名」，**不蕴含任何表的读写权限**（D13 §4.5 WU3-12 的教训：
--    原稿只有这一句，于是 V71–V74 建完表后 floorplan_dmz 连自己的队列都读写不了）。
--    真正的 DML 权限在 V71/V72/V73/V74 里逐表授予，且刻意不给
--    `ON ALL TABLES IN SCHEMA integration` 通配——通配会把 partners / partner_credentials
--    也授成可写，DMZ 一旦失守就能自己插一行 status='ACTIVE' 的密钥版本，绕过验签闸门。
GRANT USAGE ON SCHEMA integration TO floorplan_dmz;

-- 后续新建表默认只给 SELECT：要给写权限必须在建表迁移里显式补 GRANT。
ALTER DEFAULT PRIVILEGES IN SCHEMA integration GRANT SELECT ON TABLES TO floorplan_dmz;

-- 序列默认不给任何权限（BIGSERIAL 的隐式序列由属主 floorplan 持有；
-- DMZ 需要的 USAGE/SELECT 在 V71–V74 逐表建表后统一授予，见各文件末尾的授权段）。
