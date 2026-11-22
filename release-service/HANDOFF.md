# HANDOFF · RELEASE agent（T2-9 出库指令服务）

分支：`agent/release`　|　基线：`9f91101`　|　交付 tag：`agent/release-done`

## 1. 改了哪些文件

### 新增（迁移，V19 + V24）
- `infrastructure/src/main/resources/db/migration/V19__release_orders.sql`
  —— 六张表 + `release_order_no_seq` 序列。未触碰 V16/V17/V18/V20/V21。
- `infrastructure/src/main/resources/db/migration/V24__release_custodian_scope.sql`
  —— Q9/Q10（ADR 已定稿口径）落地：三列新增 + 覆盖旧注释。**V19 已执行、保持字节不变**，
  改 V19 会让 Flyway checksum 失配、服务启动失败。

### 新增（release-service）
| 包 | 文件 | 职责 |
|---|---|---|
| `release.model` | `ReleaseStatus.java` | 六态枚举：`PENDING_RELEASE / SENT / CONFIRMED / RELEASED / LIEN_DISCHARGED / CANCELLED`，后两者为终态 |
| `release.model` | `ReleaseItemType.java` | `VEHICLE / CERTIFICATE / KEY` + 逗号串解析（未知类型直接抛，不静默丢弃） |
| `release.model` | `ReleaseOrder.java` | 指令聚合根（对应 `release_orders`） |
| `release.model` | `ReleaseOrderItem.java` | 释放项明细（对应 `release_order_items`） |
| `release.model` | `ReleaseStateHistory.java` | 状态流转历史（对应 `release_state_history`） |
| `release.statemachine` | `ReleaseStateMachine.java` | 纯函数状态机：`canTransit` / `requireTransition` / `nextStates` |
| `release.channel` | `ReleaseChannel.java` | 下发通道接口（Q9/Q10 收敛后的可扩展接缝） |
| `release.channel` | `ManualReleaseChannel.java` | 起步实现：MANUAL 人工转交交接单（替换原日志占位 `LoggingReleaseChannel`） |
| `release.model` | `CustodianType.java` | 接收方类型：两类主用 + 两类须业务书面启用 |
| `release.model` | `CustodianResolver.java` | 按释放项解析实际接收方（纯函数） |
| `release.repository` | `JdbcReleaseOrderRepository.java` | `NamedParameterJdbcTemplate` 读写六表中的三张运行时表 |
| `release.service` | `CreateReleaseCommand.java` / `ReleaseDetail.java` | 命令对象与查询结果对象 |
| `release.service` | `ReleaseApplicationService.java` | 创建 / 确认 / 放车 / 解抵 / 查询，每步状态机校验 + 落历史 |
| `release.controller` | `ReleaseController.java` | 五个 HTTP 端点 |
| `release.controller.dto` | `CreateReleaseRequest.java` / `ReleaseActionRequest.java` | 请求体 |
| `release-service/src/test/...` | `ReleaseStateMachineTest.java` | 参数化穷举单测（45 例） |
| `release-service/` | `HANDOFF.md` | 本文件 |

### 修改（均在允许边界内）
- `release-service/pom.xml`：新增 `junit-jupiter`（test scope，仅用于状态机单测）。
- `release-service/src/main/java/.../ReleaseServiceApplication.java`：`@Import` 增加
  `ManualReleaseChannel` / `JdbcReleaseOrderRepository` / `ReleaseApplicationService`；
  更新 Javadoc。`ReleaseController` 带 `@RestController`，由原有 `scanBasePackages` 扫描装配，
  未重复加入 `@Import` 以免生成两个 bean。
- V24 配套改造：`ReleaseOrderItem` / `ReleaseStateHistory` / `CreateReleaseRequest` /
  `CreateReleaseCommand` / `ReleaseActionRequest` / `ReleaseController` /
  `ReleaseApplicationService` / `JdbcReleaseOrderRepository` / `ReleaseItemType`。

**未修改**：父 `pom.xml`、任何 `application.yml`、`common/`、`run-microservices.sh`、
其他服务目录、`web/`、`domain/`、`application/`、`infrastructure/` 的既有 Java 文件、
V16/V17/V18/V20/V21 迁移、根目录 `AGENT_TASK.md` / `PROGRESS.md`。

## 2. 六张表实现情况（V19）

按《放车放证指令设计》建模，任务卡要求的「明细表 + 状态历史表」另行补上：

| # | 表 | 设计稿出处 | 说明 |
|---|---|---|---|
| ① | `release_orders` | §1.2⑤ | 指令主表：六要素 + 状态 + 通道；`status VARCHAR(24) DEFAULT 'PENDING_RELEASE'` |
| ② | `release_order_items` | 任务卡补充 | 逐项记录 VEHICLE/CERTIFICATE/KEY 的实际释放，支持「放车未放证」 |
| ③ | `release_state_history` | 任务卡补充 | 只追加的审计轨迹，记录 who/when/from/to |
| ④ | `release_receipts` | §1.2⑦ | 回执，含 `REJECTED/PARTIAL/DELAYED` 异常类型 |
| ⑤ | `custodians` | §1.2⑥ | 接收方四类 `custodian_type` 全建模 |
| ⑥ | `redemptions` | §2① | 赎车申请，本服务只按 ID 引用，不写其状态 |

风格照抄 V15：`CREATE TABLE IF NOT EXISTS`、金额 `BIGINT`、时间 `TIMESTAMPTZ`、索引 + `COMMENT ON`。
主键/序号：`release_order_no_seq` 序列生成 `RO-{YYYY}-{6位序号}`，并发安全、生成后不复用。

## 3. 状态机实现情况

规则只有一份，位于 `ReleaseStateMachine`（纯函数，无 Spring / 无 I/O）：

```
PENDING_RELEASE → SENT | CANCELLED
SENT            → CONFIRMED | CANCELLED
CONFIRMED       → RELEASED | CANCELLED
RELEASED        → LIEN_DISCHARGED
LIEN_DISCHARGED → （终态）
CANCELLED       → （终态）
```

- **未经 CONFIRMED 不得 RELEASED**：`PENDING_RELEASE→RELEASED`、`SENT→RELEASED` 均被拒绝。
- **未 RELEASED 不得 LIEN_DISCHARGED**：只有 `RELEASED→LIEN_DISCHARGED` 合法。
- 非法流转抛 `BusinessException(ErrorCode.VEHICLE_002)` → HTTP 422（复用既有错误码；
  新增 RELEASE_001 需改 `domain/`，超出本 agent 边界，见 TODO）。
- 自迁移、null 状态一律拒绝。

**动作 → 状态映射**（服务层只调 `requireTransition`，不写 if-else）：

| 端点 | 迁移 |
|---|---|
| `POST /api/releases` | 落单 `PENDING_RELEASE` → `ReleaseChannel` 下发 → `SENT` |
| `POST /api/releases/{id}/confirm` | `SENT → CONFIRMED`（回执确认） |
| `POST /api/releases/{id}/release` | `CONFIRMED → RELEASED`（并标记明细已释放） |
| `POST /api/releases/{id}/discharge-lien` | `RELEASED → LIEN_DISCHARGED` |
| `GET /api/releases/{id}` | 指令 + 明细 + 状态历史 |

> 说明：任务卡只给了 confirm/release/discharge 三个推进端点，而状态链有四个推进步骤，
> 故把 `PENDING_RELEASE→SENT` 放在创建时随下发完成（与下发失败即回滚的语义一致）。
> `CANCELLED` 由状态机支持但未提供端点（任务卡明确要求五个端点，未加 `/cancel`）。

设计稿 §1.2⑤ 的 `ISSUED/ACKNOWLEDGED/REJECTED/EXPIRED` 取值**未采用**：任务卡明确指定六态枚举，
以任务卡为准；设计稿的 `issued_at / acknowledged_at / delivery_channel / rejected_reason / receipt_id`
等字段在 `release_orders` 中均保留，后续要扩充状态只需改枚举 + 状态机白名单 + 单测。

## 4. Q9/Q10 口径收敛与接缝（2026-11-22 落地，已由《业务口径总定源》ADR §2 定稿）

### 结论：不再四选一，按释放项分两条线

《业务流程文档》§6 已由用户确认本系统面向**日本新车渠道**：物理监管只做实地盘点，
不做扣押钥匙 / GPS / 封签 / 驻场监管（那是中国第三方监管公司对二手车、高风险车商的做法），
兜底靠厂家回购承诺 + 经销商信用 + 让与担保。车物理上就在经销商自己的库里，
金融公司没有第三方替它看车，真正握住的控制手段是权属凭证。

| 释放项 | 接收方类型 | 说明 |
|---|---|---|
| `VEHICLE` / `KEY` | `DEALER_WAREHOUSE` | 放车线：经销商库执行交付 |
| `CERTIFICATE` | `CERTIFICATE_DEPOSITORY` | 放证线：凭证保管点交出权属凭证 |
| `THIRD_PARTY_WAREHOUSE` / `ON_SITE_SUPERVISOR` | 须业务书面启用 | 与已确认口径冲突，枚举保留但不作为默认 |

### 落地点

- **表**：V24 给 `release_order_items` 加 `custodian_id`（NULL 继承主表
  `release_orders.custodian_id`），给 `release_state_history` 与 `release_receipts`
  加 `confirmation_authority`；覆盖 `custodians` 等 5 处旧注释。
- **模型**：`CustodianType`（`isPrimary()` / `requiresWrittenApproval()`）、
  `ReleaseItemType.defaultCustodianType()`、纯函数 `CustodianResolver`
  （显式按项覆盖 > 继承主表 > null，键名大小写不敏感）。
- **HTTP**：`POST /api/releases` 可传 `custodianByItem`；`confirm` 可传
  `confirmationAuthority`（默认 `INTERNAL_WITNESS`）。
- **通道**：`release.channel.ManualReleaseChannel` 起步实现——打印逐项交接单
  （指令号 / VIN / 该项接收方 / 期望接收方类型），不连任何外部系统；
  `ReleaseServiceApplication` 的 `@Import` 注册它，替换原 `LoggingReleaseChannel`。
- **回执闭环**：`confirm` 时落 `release_receipts`（`source=MANUAL`、
  `confirmed_by`=我方担保管理岗、`confirmation_authority`），并把同一权威写进状态历史。
  **执行接收方与权威确认方解耦**，回应设计稿 §7 #10「回执不能由我方代填 / 经销商自证」。
- **Q10 同步收敛**：起步无外部 WMS 可直连，MANUAL 为主（设计稿 §7 #6、
  ADR-009「人工路径一等公民」）；PORTAL/API/MQ 待真有 partner 系统再实现。

### 接缝仍在

`ReleaseChannel#dispatch(ReleaseOrder, List<ReleaseOrderItem>)` 接口不变：
将来接真实 WMS/监管门户时只需新增一个实现并替换 `@Import`，
`ReleaseApplicationService` 一行不改；签名走 ADR-009 DMZ 入口，
回执 `source` 改为对应通道、确认人记 `partner_code`。

## 5. 单测与验证结果（2026-11-22 收口）

**全量构建**：`mvn -B package` → **BUILD SUCCESS，15/15 模块**，退出码 0。
（`mvn -B clean package` 会被 VSCode Java 语言服务器锁住
`infrastructure/target/classes/db/migration` 而失败，属环境干扰；按项目既定绕法
用增量 `package`，必要时清掉单个模块的 `target` 再跑即可全绿。）

**单测**：
- `ReleaseStateMachineTest`：**46 例全绿**（6×6 穷举 + 两条红线 + 终态/null）。
- `CustodianResolutionTest`（V24 新增）：**9 例全绿**——按项覆盖、继承主表、
  键名大小写不敏感、两类主用类型与书面启用标记。
- release-service 合计 **55 例，Failures 0 / Errors 0**；全仓各模块单测均全绿。

**数据库与端到端**（PostgreSQL 5434 已起，V24 由 web 的 Flyway 执行）：
- `flyway_schema_history` 实证 `24 release custodian scope` 已落库。
- `bash run-microservices.sh start` 10 服务全就绪（网关 18080），走网关冒烟
  **184 PASS / 0 FAIL / 全部通过 ✅**（`SMOKE_BASE=http://127.0.0.1:18080`）。
- **release 专项 13 项断言全绿**：结算岗登录 → 创建（带 `custodianByItem`）→
  查询逐项接收方 → confirm → release → discharge-lien → 跳步放车 422 红线 →
  未覆盖时继承主接收方。
- **直接查库核对**：`release_order_items` 三项 `custodian_id` =
  VEHICLE/KEY 101、CERTIFICATE 202；`release_state_history` 的 CONFIRM 行
  `confirmation_authority=INTERNAL_WITNESS`；`release_receipts` 落
  `RR-RO-2026-000001 / source=MANUAL / confirmed_by=settlement /
  confirmation_authority=INTERNAL_WITNESS`。
- 验证后已清理测试 VIN 数据并停止全部服务，端口全释放。

产物：`release-service/target/floorplan-release-service-1.0.0-SNAPSHOT.jar`。
本服务 `spring.flyway.enabled=false`，建表/加列由 web 服务 Flyway 完成（V19→V24）；
单测为纯函数，不依赖数据源。

## 6. TODO（跨边界，未改代码）

1. **Q9/Q10 已由 ADR 定稿**（V24 口径即最终口径，不再等书面确认）：若将来真存在 partner 系统，
   再实现 `PortalReleaseChannel` / `ApiReleaseChannel` / `MqReleaseChannel`
   （ADR-009 §2.5 签名、失败重试 + 降级人工 + 告警），回执 `source` 改为对应通道，
   确认人记 `partner_code`；当前 `source=MANUAL` 我方见证代录已闭环，
   但 `integration_calls` 尚未落库。
2. **新增错误码**：`RELEASE_001` 出库指令状态非法流转，需在 `domain/exception/ErrorCode.java` 加；
   当前复用 `VEHICLE_002`（HTTP 422 行为一致）。
3. **新增权限点**：设计稿建议 `RELEASE_ORDER_ISSUE`（担保管理岗），需在 `domain/model/Permission.java`
   加枚举并在权限初始化 SQL 授权；当前端点沿用 `SECURITY_MAINTAIN`。
4. **撤销端点**：设计稿有 `POST /api/release-orders/{no}/cancel`；任务卡只要求五个端点，
   未实现。状态机已支持任意未终结态 → `CANCELLED`。
5. **台账联动**（环节⑧）：车辆 `PENDING_RELEASE→SETTLED`、质押解除、额度回补、
   `inventory_releases` 与 `audit_events` 属于 core/担保上下文，需由相应服务消费事件完成，
   本服务未跨目录修改。指令 `RELEASED` 后如何通知下游（事件/outbox）待主 agent 定接口。
6. **V19 与其他迁移的表名协调**：`custodians` / `redemptions` 均用 `CREATE TABLE IF NOT EXISTS`；
   若其他 agent 在 V16/V17/V18/V20 中已建同名表且列不同，需主 agent 在合并时对齐列定义。
   本服务运行时只读写 `release_orders` / `release_order_items` / `release_state_history`，
   不依赖前两张表的具体列。
7. **账实缺口巡检**：设计稿 §4.3 的「SENT 超期未回执」「RELEASED 但台账未更新」巡检作业未实现，
   需调度服务支持。
