# AGENT TASK · W0-FOUNDATION（事件机制 + 本地直连 + 契约冻结）

## 你的身份

你是 **W0-FOUNDATION** agent，**在主干直接工作**（不切 worktree、不建分支），中文交流。
你是唯一的前置波次：**你合并并打 tag 之后，五个服务 agent 才允许开分支**。
所以你的第一优先级不是"写得多"，而是"把地基做对、做窄、冻结"。

## 硬性边界（违反即合并失败）

1. **只写**：
   - `common/src/main/java/com/company/floorplan/common/event/**`（新增）
   - `common/src/main/java/com/company/floorplan/common/contract/**`（新增 + 给已有 `CollateralClient` / `CreditLineClient` 补 `url` 属性）
   - `infrastructure/src/main/java/com/company/floorplan/infrastructure/outbox/**`（新增）
   - `infrastructure/pom.xml`（加 `floorplan-common` 依赖，仅此一行）
   - `infrastructure/src/main/resources/db/migration/V26__event_consumption.sql`（新增）
   - 全部 10 个启动类（`*ServiceApplication.java` / `FloorplanApplication.java`，只加 `@EnableScheduling` 与 `@Import` 新 bean）
   - `run-microservices.sh`（只注入直连环境变量）
   - `loan-service/src/main/java/.../loan/consumer/LoanCreatedConsumer.java`（新增，**只做骨架**）
   - `scripts/agent_smoke_lib.py`、`scripts/smoke_w0.py`（新增）
   - `docs/接口契约-事件与Feign.md`、`docs/事件机制实现说明.md`（新增）
2. **绝不修改**：父 `pom.xml`、`gateway/`、`application/`、`domain/` 任何文件、`web/src/main/java/` 业务代码、
   `smoke-test.py`、`PROGRESS.md`、任何 `V1–V25` 迁移、其他服务的业务代码。
3. 不新增任何业务 HTTP 端点（地基不做业务）。
4. 不执行 `git merge` / `rebase`；完成后 commit + 打 tag `wave0b-baseline`。

## 开工前自检

```bash
cd C:/Users/Administrator/Documents/GitHub/floorplan-system
git log -1 --format='%h %s'     # 应为 d1c90e8 或其后
git status --short              # 必须干净（只允许 PROGRESS.md 有他人改动时先问 PM）
mvn -B -q package -DskipTests   # 基线必须能过
```

## 背景（只看这三条就够）

1. `outbox_events` 表和 `OutboxPublisher` 接口已经在（V21），但**没有实现、没有调度、没有消费者、没有幂等表**。
   所有跨服务任务（借据、回款、解押、回补）都卡在这里。
2. 9 个服务 `discovery.fail-fast=false`，网关靠 `ROUTE_*` 直连；Feign 只有 `lb://` 且无 simple discovery。
   **本地不启 Nacos 时 Feign 解析不到实例**，agent 只能对着存根测。必须补一个"本地直连"开关。
3. 你已经知道 `mvn clean package` 并发会抢文件锁、Flyway 撞号会让全部服务起不来。所以号段与共享文件必须先冻结。

## 任务

### W0-1 事件模型（`common/.../event/`）

```java
public record DomainEvent(long eventId, String eventType, String aggregateKey,
                          String payload, Instant occurredAt) {}

public interface EventConsumer {
    String name();                      // 消费方标识，用服务名，如 "loan-service"
    Set<String> eventTypes();           // 订阅的事件类型
    void consume(DomainEvent event);    // 业务处理，抛异常即视为失败
}
```

- `OutboxPublisher` **接口签名不许改**（已有调用方与注释都依赖它）。
- 事件名常量沿用 `LOAN_CREATED` / `PAYMENT_MATCHED` / `COLLATERAL_CHANGED`，需要新增时先在契约文档登记。

### W0-2 发布端实现（`infrastructure/.../outbox/JdbcOutboxPublisher.java`）

- `implements OutboxPublisher`，用 `NamedParameterJdbcTemplate`，**必须在调用方事务内写入**（不加 `@Transactional` 到本类，跟随调用方）。
- 去重：同 `(event_type, aggregate_key)` 已存在 `PENDING`/`SENT` 记录时**不重复插入**（V21 注释已约定），
  用 `INSERT ... SELECT ... WHERE NOT EXISTS` 实现，不要靠唯一索引（V21 只有普通索引，且不能改）。
- payload 必须含 `vin` 与 `traceId`，缺失直接抛 `IllegalArgumentException`，不要静默写空。
- `infrastructure/pom.xml` 加 `floorplan-common` 依赖（infra 目前只依赖 domain，common 不反向依赖 infra，无环）。

### W0-3 消费端框架（`infrastructure/.../outbox/EventDispatcher.java`）

- `@Scheduled(fixedDelay = 1000)` 拉取：`SELECT * FROM outbox_events WHERE id > :offset AND event_type IN (:types) ORDER BY id LIMIT 100`。
- 每个消费者维护自己的游标 `consumer_offsets(consumer, last_event_id)`。
- 幂等：`event_consumptions(event_id, consumer)` 主键冲突即跳过（同一事件同一消费者只处理一次）。
- **失败语义（明确写进注释）**：
  - 消费抛异常 → 该事件 `attempts + 1`、`last_error` 落库，游标**不前进**，下轮重试；
  - `attempts >= 3` → 置 `DEAD` 并**跳过**（游标前进），避免一条坏事件卡死整个消费者；
  - `DEAD` 的重放留 TODO，由 W3 处理。
- 每个服务只装配自己的 `EventConsumer` 实现（用 `List<EventConsumer>` 注入，按 `eventTypes()` 聚合订阅集合）。
- 消费者必须能在**没有 Nacos**的情况下工作（纯 DB 轮询，不发起服务间调用）。

### W0-4 迁移 V26

```sql
CREATE TABLE IF NOT EXISTS event_consumptions (
    event_id   BIGINT      NOT NULL,
    consumer   VARCHAR(64) NOT NULL,
    status     VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING/DONE/DEAD
    attempts   INT         NOT NULL DEFAULT 0,
    last_error TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);
CREATE TABLE IF NOT EXISTS consumer_offsets (
    consumer      VARCHAR(64) PRIMARY KEY,
    last_event_id BIGINT NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

只新增文件，**绝不改 V1–V25**。

### W0-5 本地直连（Feign）

- 给已有 `CollateralClient` / `CreditLineClient` 的 `@FeignClient` 加 `url = "${floorplan.clients.<name>.url:}"`（空串即回退 lb，不影响生产）。
- 新增两个契约（放 `common/.../contract/`，只定义接口 + DTO，不写实现）：
  - `LoanQueryClient`：`GET /api/loans/by-vin/{vin}` → `LoanSnapshotDto`（字段与 `loans` 表契约一致：id/vin/advanceId/dealerId/status/principal/dueDate）
  - `CreditLineQueryClient`：`GET /api/credit-lines/{id}/available` → `long`（可用额度，整数日元）
- 两个 DTO 就是 W1-LOAN / W1-CREDIT 的验收标准，**字段名写定后不许改**。
- `run-microservices.sh` 注入：`FLOORPLAN_CLIENTS_LOAN_URL`、`FLOORPLAN_CLIENTS_CREDIT_URL`、`FLOORPLAN_CLIENTS_COLLATERAL_REG_URL` 等，
  值与已有 `ROUTE_*` 保持一致（8085/8083/8086…）。yml 里对应 `${floorplan.clients.loan.url:}`。

### W0-6 启动类装配

10 个启动类各加 `@EnableScheduling`，`@Import` 里补 `JdbcOutboxPublisher.class`、`EventDispatcher.class`。
**只加这两项**，不动其他装配。

### W0-7 参考消费者（只做骨架，不做业务）

在 `loan-service/.../consumer/LoanCreatedConsumer.java` 实现：
- `name()` 返回 `"loan-service"`，`eventTypes()` 返回 `Set.of("LOAN_CREATED")`；
- `consume()` 只做两件事：解析 JSON 校验 `vin`/`advanceId` 必填、`log.info` 打印 `eventId/vin/traceId`，
  并留 `// TODO(W1-LOAN)：调用 LoanApplicationService.createFromLoanCreated() 建借据`；
- 目的：证明机制能跑通，同时把 W1-LOAN 的落点固定下来。

### W0-8 冒烟脚手架

- `scripts/agent_smoke_lib.py`：登录拿 token（复用 `smoke-test.py` 的登录逻辑，复制不要 import）、
  `get/post/ok/assert_eq`、`psql()` 执行 SQL 并返回行、`wait_until(fn, sec)`。
- `scripts/smoke_w0.py`：
  1. 启 `web(8080)` + `loan-service(8085)`；
  2. `psql` 插一条 `LOAN_CREATED`（合法 payload）；
  3. 3 秒内 `event_consumptions` 出现 `(event_id,'loan-service','DONE')`；
  4. 再插一条**同 (event_type, aggregate_key)** 的 PENDING 事件 → 断言 outbox **没有多出第二条**（发布去重）；
  5. 直接往 `outbox_events` 插一条重复 id 不适用，改为：手动重置 offset 后重放同一 event_id → 断言 `event_consumptions` 仍只有一行（消费幂等）。

## 契约文档（必须交付，W1 五个 agent 照它编码）

`docs/接口契约-事件与Feign.md` 至少写清：

| 事件 | 生产者 | 消费者 | payload 字段（名/类型/必填） |
|---|---|---|---|
| `LOAN_CREATED` | core（放款 PAID 后） | loan-service | vin, advanceId, dealerId, creditLineId, principal, dueDate, gracePeriodDays, traceId |
| `PAYMENT_MATCHED` | bank-service | release-service, credit-service | vin, loanId, advanceId, dealerId, paymentRef, amount, settled, matchedAt, traceId |
| `COLLATERAL_CHANGED` | collateral-reg-service | core（只读投影，可后置） | vin, status, regNo, ownerName, changedAt, traceId |

外加：幂等键定义（`LOAN_CREATED` = advanceId；`PAYMENT_MATCHED` = paymentRef）、
`event_consumptions` 表结构、Feign 两个接口的完整响应 JSON 样例。

## 验证（必须自己跑通，贴输出）

```bash
mvn -B -q package -DskipTests          # 15/15
mvn -B -pl common,infrastructure -am test
bash run-microservices.sh start 2>/dev/null || true   # 或用你 smoke 脚本自带启停
python scripts/smoke_w0.py             # 必须全绿
```

## 交付

1. commit 按 `[W0] W0-1 事件模型` 这样的粒度，一任务一提交。
2. 打 tag：`git tag -a wave0b-baseline -m "W0 事件地基就绪：outbox 实现 + 消费幂等 + 本地直连 + 契约冻结"`。
3. 写 `docs/事件机制实现说明.md`：为什么用 DB 轮询而不是 MQ、失败/死信语义、拆库后怎么换成 HTTP/MQ（一段话即可）。
4. 在回复里给出：改了哪些文件、tag 号、smoke_w0 输出、留给 W1 的 TODO 清单。

## 禁止事项（再强调一次）

- 不要顺手去修业务缺陷（借据、回款、出库都不归你）。
- 不要打开 `floorplan.core.guarantee-gate.enabled`。
- 不要改 `smoke-test.py` 与 `PROGRESS.md`。
- 不要为"跑通"而在生产代码里造数据；测试数据只能由 `scripts/smoke_w0.py` 通过 psql 写入。
