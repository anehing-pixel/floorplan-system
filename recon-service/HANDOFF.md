# HANDOFF · RECON 对账清算服务（T3-2）

分支：`agent/recon` ｜ 工作树：`C:/Users/Administrator/Documents/GitHub/wt-recon`

## 1. 改了哪些文件

严格遵守任务卡边界：**只新增 `recon-service/` 下文件 + 唯一一个 V20 迁移**。父 pom、
`application.yml`、`common/`、`web/`、`domain/`、`application/`、`infrastructure/` 既有 java、
其他服务目录、V16/V17/V18/V19/V21 均未改动。

迁移（1 个）：
- `infrastructure/src/main/resources/db/migration/V20__reconciliation.sql` — `recon_runs` +
  `recon_diffs` 两张表，BIGSERIAL 主键、`created_at TIMESTAMPTZ DEFAULT now()`、
  唯一约束 `uq_recon_diff(batch_no, rule_code, subject)`、外键到批次表、5 个索引、
  表/列 COMMENT。

recon-service（21 个 java + pom + 启动类改动）：
- `pom.xml` — 新增 `spring-boot-starter-test`（test scope）。
- `ReconServiceApplication.java` — `@Import` 追加 `JdbcReconRepository`、`ReconRuleConfig`、
  `ReconApplicationService`、`ReconController`；`NamedParameterJdbcTemplate` 由 Boot 自动装配，
  未手动声明。
- `model/`：`Comparison`（对拍事实：subject/expected/actual/note）、`ReconDiff`（差异，含
  `pending` 纯工厂）、`ReconRun`（批次状态机 start/succeed/fail）。
- `repository/JdbcReconRepository.java`：`NamedParameterJdbcTemplate` 实现；R1-R4 账本只读 SQL、
  批次写入/收尾、差异批量写入（`ON CONFLICT DO NOTHING`）、差异条件查询。
- `rule/`：`ReconRule`（纯函数契约）、`AbstractReconRule`（final evaluate 模板）、
  `ReconRuleCode`（8 条目录）、`R1/R2/R3/R4*Rule`（实现）、`ExactValueReconRule`（R5–R8 共用精确相等判定）、
  `ReconRuleSet` + `ReconRuleConfig`（固定 R1..R8 执行顺序）。
- `service/ReconApplicationService.java`：开跑 → 逐规则取数判定 → 差异落库 → 收尾；
  异常路径把 RUNNING 改 FAILED 并保留已执行规则数。
- `controller/ReconController.java`：`POST /api/recon/run`、`GET /api/recon/diffs`。
- `src/test/.../ReconRuleTest.java`：22 个参数化/普通单测，不连库、不启 Spring。

## 2. 8 条规则状态

| 编号 | 名称 | 状态 | 对拍口径 / 差异类型 |
|---|---|---|---|
| R1 | 垫款余额 vs 还款核销累计 | **已实现** | `advances.amount` = `vehicle_units.principal_balance + SUM(repayments.principal)`，按 VIN；`BALANCE_MISMATCH` |
| R2 | 在库车辆状态 vs 垫款未结清 | **已实现** | 债权存续车辆状态（FINANCED/IN_STOCK/SOLD_PENDING/OVERDUE/DISPOSING）编码 1 vs 存在未终结垫款编码 1，按 VIN；`STATE_MISMATCH` |
| R3 | 额度占用 vs 在途垫款合计 | **已实现** | `credit_lines.used_amount` = `SUM(advances.amount WHERE status IN PENDING/PROCESSING/PAID)`，按额度；`CREDIT_MISMATCH` |
| R4 | 银行流水入账 vs 还款核销 | **已实现** | 已匹配且金额>0 的 `payment_transactions` 合计 = `SUM(repayments.total_amount)`，按 VIN，FULL OUTER JOIN 覆盖单侧有记录；`CASH_MISMATCH` |
| R5 | 利息计提 vs 计息流水累计 | **已实现** | `vehicle_units.accrued_interest` = `SUM(interest_ledger + interest_ledger_archive.daily_interest)`，按 VIN；`INTEREST_MISMATCH` |
| R6 | 担保登记状态 vs 担保物台账 | **已实现** | V10 有效登记编码 1/0 = `collateral_ledger.security_status='REGISTERED'` 编码 1/0，按 VIN；`COLLATERAL_MISMATCH` |
| R7 | 库龄递减计划 vs 实际还款进度 | **已实现** | `aging_plans.paid_periods` = `COUNT(curtailment_payments)`，按 VIN；`CURTAILMENT_MISMATCH` |
| R8 | 处置损失 vs 账务核销 | **已实现** | 处置 CLOSED_* 编码 1/0 = 借据 DEFAULTED/WRITTEN_OFF 编码 1/0，按 VIN；`WRITEOFF_MISMATCH` |

实现规则均为纯函数判定（`AbstractReconRule.evaluate` 为 final，禁止 IO/时钟），
SQL 只负责取数。**2026-11-22 起 8 条全部真实实现**：占位类 `PlaceholderReconRule` 已删除，
`ReconApplicationService` 去掉 `implemented=false → continue` 短路，`ruleCount` 恒为 8。
口径见 `docs/ADR-业务口径总定源.md` §10。

## 3. 本地验证结果

```
mvn -q -pl recon-service -am test      → EXIT 0
  Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
mvn -q -pl recon-service -am package   → EXIT 0
  产物：recon-service/target/floorplan-recon-service-1.0.0-SNAPSHOT.jar
```
未依赖数据库即可完成单测；未做真实 PG 集成验证（环境无数据库），V20 SQL 需在
集成环境执行 Flyway 迁移后回归。

## 4. 留了哪些 TODO

1. ~~R5-R8 规则细节待业务确认~~ **已闭环（2026-11-22）**：按《业务口径总定源》ADR 裁决，
   R5–R8 已补真实取数 SQL + `ExactValueReconRule` 精确相等判定，8 条全 `implemented=true`。
   原「待业务确认」表述作废。
2. **R4 账期时差**：**已裁决**（ADR §0.2 / §1.5）——不引入时间窗与容差猜测，
   精确相等才无差异，不等即报 CASH_MISMATCH，由人工复核判断是否属于在途。
3. **人工闭环接口**：`recon_diffs.status` 的 CONFIRMED/IGNORED/RESOLVED 流转尚无接口，
   当前只对拍写 OPEN；需与权限/工作流方案一并确认。
4. **审计留痕**：`ReconApplicationService.run` 的 `actor` 参数目前未写 `audit_events`
   （需要 `SupportRepository.writeAudit`，属其他服务地盘，按边界留待集成）。
5. **批次并发**：未加分布式锁；重复触发会生成不同 batchNo 并对同一批账本重复对拍。
   若需互斥，用 common 的 `DistributedLock` 契约在集成阶段补上。
6. **事件发布**：未发布任何 outbox 事件；若将来需要通知差异，使用
   `OutboxPublisher.EVENT_*` 常量，不得自定义事件名。

## 5. 依赖的外部接口（只读，未改动）

- 账本表：`advances`、`vehicle_units`、`credit_lines`、`repayments`、
  `payment_transactions`（V1/V2/V10/V12/V14/V15 既有 schema）。
- 共享基础设施：`common` 的 `ApiResponse`、`RequirePermission`、`JwtAuthInterceptor`、
  `GlobalExceptionHandler`、`WebMvcConfig`、`OutboxPublisher`（契约常量）；
  `domain` 的 `Permission`、`VehicleStatus`、`AdvanceStatus`（语义参考，无代码改动）。
- `NamedParameterJdbcTemplate` 由 Spring Boot `JdbcTemplateAutoConfiguration` 基于
  `application.yml` 现有 DataSource 自动装配，无需新增配置。

## 6. 任务卡与设计文档的冲突说明

任务卡「开工前自检」要求 `git log -1` 必须是 wave0 的 `aab5295`，但实际 HEAD 是
`8173fc0 chore: 派发 AGENT_TASK.md 任务卡`——`aab5295` 在历史中且是当前 HEAD 的父提交。
处理：确认 wave0 地基存在且工作树干净后继续开发，**未执行任何 reset/branch/merge**
等改变仓库结构的命令，也未改动任务卡本身。除此之外未发现任务卡与代码/设计文档的冲突：
`Permission.JOB_TRIGGER`、`OutboxPublisher.EVENT_*`、V15 建表风格均按契约使用。
