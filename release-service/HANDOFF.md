# HANDOFF · RELEASE agent（T2-9 出库指令服务）

分支：`agent/release`　|　基线：`9f91101`　|　交付 tag：`agent/release-done`

## 1. 改了哪些文件

### 新增（迁移，唯一号段 V19）
- `infrastructure/src/main/resources/db/migration/V19__release_orders.sql`
  —— 六张表 + `release_order_no_seq` 序列。未触碰 V16/V17/V18/V20/V21。

### 新增（release-service）
| 包 | 文件 | 职责 |
|---|---|---|
| `release.model` | `ReleaseStatus.java` | 六态枚举：`PENDING_RELEASE / SENT / CONFIRMED / RELEASED / LIEN_DISCHARGED / CANCELLED`，后两者为终态 |
| `release.model` | `ReleaseItemType.java` | `VEHICLE / CERTIFICATE / KEY` + 逗号串解析（未知类型直接抛，不静默丢弃） |
| `release.model` | `ReleaseOrder.java` | 指令聚合根（对应 `release_orders`） |
| `release.model` | `ReleaseOrderItem.java` | 释放项明细（对应 `release_order_items`） |
| `release.model` | `ReleaseStateHistory.java` | 状态流转历史（对应 `release_state_history`） |
| `release.statemachine` | `ReleaseStateMachine.java` | 纯函数状态机：`canTransit` / `requireTransition` / `nextStates` |
| `release.channel` | `ReleaseChannel.java` | 下发通道接口（Q9/Q10 接缝） |
| `release.channel` | `LoggingReleaseChannel.java` | 默认实现：只打日志，TODO 接真实 WMS |
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
  `LoggingReleaseChannel` / `JdbcReleaseOrderRepository` / `ReleaseApplicationService`；
  更新 Javadoc。`ReleaseController` 带 `@RestController`，由原有 `scanBasePackages` 扫描装配，
  未重复加入 `@Import` 以免生成两个 bean。

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

## 4. Q9/Q10 接缝在哪

- **接口**：`release.channel.ReleaseChannel#dispatch(ReleaseOrder, List<ReleaseOrderItem>)`。
- **默认实现**：`release.channel.LoggingReleaseChannel`——只打日志（含指令号/VIN/接收方/通道），
  不连任何外部系统；类注释里有编号 TODO。
- **装配**：`ReleaseServiceApplication` 的 `@Import` 注册 `LoggingReleaseChannel`。
  将来接真实 WMS/监管门户时，**只需新增一个 `ReleaseChannel` 实现并替换 `@Import` 中的类型**，
  `ReleaseApplicationService` 一行不改。
- **相关表字段**：`release_orders.delivery_channel`（MANUAL/PORTAL/API/MQ）、`custodian_id`；
  `custodians.api_endpoint / api_secret_ref` 为外部对接预留（密钥只存引用）。
- **ADR-009**：真实通道必须走 DMZ 入口签名鉴权，回执确认人记 `partner_code` 而非自然人——已写入 TODO。

## 5. 单测与验证结果

```
cd C:/Users/Administrator/Documents/GitHub/wt-release
mvn -q -pl release-service -am test       → 退出码 0
mvn -q -pl release-service -am package    → 退出码 0
```

- `ReleaseStateMachineTest`：**Tests run: 45, Failures: 0, Errors: 0, Skipped: 0**。
  - 6×6=36 种状态对由 `canTransit` 反向生成合法/非法两组，全部覆盖；
  - 专项红线用例：`releaseWithoutConfirmationIsRejected`（跳步放车）、
    `dischargeLienBeforeReleaseIsRejected`（未放车先解抵）；
  - 终态/自迁移 + null 入参场景。
- 产物：`release-service/target/floorplan-release-service-1.0.0-SNAPSHOT.jar`。
- **未做数据库集成测试**：本服务 `application.yml` 中 `spring.flyway.enabled=false`，
  当前环境未启动 PostgreSQL；单测是纯状态机测试，不依赖数据源。
  编译、单测、打包均已通过；V19 SQL 需在有 PG 的环境由运维手工执行/开启 Flyway 验证。

## 6. TODO（跨边界，未改代码）

1. **Q9/Q10 确认后**接真实下发通道（监管门户 / API / MQ），实现签名（ADR-009 §2.5）、
   失败重试 + 降级人工 + 告警，回执落 `release_receipts` 与 `integration_calls`。
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
