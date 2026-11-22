# 接口契约：事件与 Feign（wave0b-baseline 冻结）

> **这份文档是 W1/W2 全部 agent 的编码依据。** W0 已把它描述的实现落地并冒烟验证：
> 33 条断言全绿（`scripts/smoke_w0.py`，2026-11-22 实测）。
>
> **变更规矩**：本文任何字段/键名/状态语义的改动，都要 (1) 改 `common` 里的契约代码；
> (2) 同步更新本文；(3) 通知全部并行 agent 并在 commit 里写明。
> 只改文档不改代码 = 死契约；只改代码不改文档 = 其他人按旧契约编码。两种都是本项目反复踩过的坑。

---

## 0. 一句话架构

```
业务写库 ──同一本地事务──> outbox_events(PENDING)
                                  │
                    EventDispatcher 每秒轮询（按 consumer 游标 + event_type 订阅）
                                  │
                                  ├─> event_consumptions (event_id, consumer) 幂等登记
                                  ├─> EventConsumer.consume(领域事件)   ← 各服务业务在这里
                                  └─> consumer_offsets.last_event_id 单调前进
```

- **投递语义是 at-least-once，不是 exactly-once**。成功与游标推进之间存在宕机窗口，
  同一条事件会被重投；幂等靠"框架层 + 业务层"两层挡（见 §3）。
- 机制细节与取舍理由见 `docs/事件机制实现说明.md`。

---

## 1. 事件信封：`outbox_events`（V21，列已冻结）

| 列 | 类型 | 谁写 | 语义 |
|---|---|---|---|
| `id` | BIGSERIAL | DB | **全局单调递增，是消费游标与框架幂等的主键**。消费端只按它排序与断点续读 |
| `event_type` | VARCHAR(64) | 发布方 | 只能用 §2 登记过的事件名，新增先登记再生成 |
| `aggregate_key` | VARCHAR(128) | 发布方 | 幂等锚点，**必须"一次发生一个值"**（见 §3.2 的 vin 禁令） |
| `payload` | TEXT | 发布方 | JSON，**必含 `vin` + `traceId`**；缺这两个字段发布端直接抛 `IllegalArgumentException` |
| `status` | VARCHAR(16) | 框架 | `PENDING` 待投递 → `SENT` 已被某消费者投递过 → `FAILED`/`DEAD`（见 §4） |
| `attempts` / `last_error` | INT / TEXT | 发布端重试计数（当前实现未使用） | 消费侧的重试计数在 `event_consumptions`，**不要看这张表** |
| `created_at` / `sent_at` | TIMESTAMPTZ | DB / 框架 | `sent_at` 由 `EventDispatcher.markSent` 写 |

**没有"读之前先锁行"**：一条事件可能被多个服务的消费者订阅，先处理完的把它标成 `SENT`，
慢的那个仍然要能读到 —— 所以轮询 SQL 只按 `id > 游标 AND event_type IN (订阅集)` 过滤，
**不按 status 过滤**。写新消费者时不要"优化"成按 status 取，那样慢消费者会永久漏读。

---

## 2. 三个事件的契约（生产方 / 消费方 / 载荷）

事件名常量在 `common/contract/OutboxPublisher`，**签名不许改**（已有注释与调用依赖它）。

| 事件 | 生产方（触发时机） | 消费方（`EventConsumer.name()`） | `aggregate_key` | payload 字段 |
|---|---|---|---|---|
| `LOAN_CREATED` | core（银行回调 PAID 之后） | `loan-service` | `advanceId` | vin, advanceId, dealerId, creditLineId, principal, dueDate, gracePeriodDays, traceId |
| `PAYMENT_MATCHED` | bank-service（`AUTO_MATCHED` 落库同事务） | `release-service`、`credit-service` | `paymentRef`(=transaction_id) | vin, loanId, advanceId, dealerId, paymentRef, amount, settled, matchedAt, traceId |
| `COLLATERAL_CHANGED` | collateral-reg-service | core（只读投影，可后置） | 待定源：建议 `regNo` + 变更序号 | vin, status, regNo, ownerName, changedAt, traceId |

### 2.1 `LOAN_CREATED` 字段口径

与 `loan-service` 的 `LoanApplicationService.LoanCreatedEvent` record **逐字段对齐**（W1-LOAN 接线时直接构造它）：

| 字段 | 类型 | 必填 | 口径 |
|---|---|---|---|
| `vin` | string | ✅ | 车架号。发布端 `JsonPayloadReader.requireVinAndTraceId` 校验 |
| `traceId` | string | ✅ | 全链路追踪，落 `loan_status_history.remark` |
| `advanceId` | number | ✅ | **垫款 ID，同时是幂等锚点**。为空表示银行未 PAID —— 建据被拒（"钱没到，债不成立"） |
| `dealerId` / `creditLineId` | number | 建议 | 画像与额度归属 |
| `principal` | number（整数日元） | ✅ | 借据本金。**金额一律 BIGINT 整数日元，禁止小数** |
| `dueDate` | string(ISO `yyyy-MM-dd`) | 建议 | 到期日。为空时 `generateRepaymentPlan` 会跳过并 WARN（"期限未录入"） |
| `gracePeriodDays` | number | 建议 | 宽限期，负数按 0 处理 |

`loan_no` 不是载荷字段，由消费方按 `"LN-" + advanceId` 生成 —— 这就是它的业务幂等键。

### 2.2 `PAYMENT_MATCHED` 字段口径

| 字段 | 类型 | 必填 | 口径 |
|---|---|---|---|
| `paymentRef` | string | ✅ | `payment_transactions.transaction_id`，**回补/解押的业务幂等键** |
| `settled` | boolean | ✅ | 是否结清。`credit-service` 只在 `settled=true`（或事件显式带 `restoreAmount`）时回补额度 |
| `amount` | number（整数日元） | ✅ | 本次匹配金额 |
| `vin` / `loanId` / `advanceId` / `dealerId` | — | 建议 | 下游定位借据用；`loanId` 是 `loans.id` |
| `matchedAt` | string(ISO) | 建议 | 匹配成功时间（业务时间，不等于 `outbox_events.created_at`） |
| `traceId` | string | ✅ | 同上 |
| `restoreAmount` | number | **可选** | 若不给，回补方**不许按比例推算**（ADR Q24：部分还款不回补额度）。给了就以它为准 |

未匹配 / 候选待确认 / 人工绑定前的挂起：**一律不发事件**。

### 2.3 `COLLATERAL_CHANGED` —— 口径未完全定源，先按最小集

`aggregate_key` 用哪个键、`status` 取值域是否与 `CollateralClient.RegistrationVO.status`
（`REGISTERED / PENDING / CANCELLED / NONE`）同源，**属于业务口径，W0 不猜**（AGENTS 铁律 3）。
本事件在 W0 只有常量与表结构可用，**接线前由 owner 在本文补一节 §2.3.1 定源**。
消费方（core）本期不实现。

---

## 3. 幂等规则：两层，缺一不可

### 3.1 框架层（自动，`event_consumptions`）

`EventDispatcher` 对每条事件先
`INSERT INTO event_consumptions (event_id, consumer, status, attempts) VALUES (...,'PENDING',0) ON CONFLICT DO NOTHING`，
读到 `DONE`/`DEAD` 就跳过并推进游标 —— **`consume()` 不会被二次调用**。
所以"同一 `event_id` 重复投递"由框架兜住，消费者不用自己处理。

### 3.2 业务层（必须自己写）

框架只认 `event_id`。**同一次业务事实被重新发布成另一行 outbox（新 id）时，框架层挡不住**，
必须由消费方按业务唯一键去重：

| 事件 | 业务幂等键 | 实现方式 |
|---|---|---|
| `LOAN_CREATED` | `advanceId` | `loan_no = 'LN-' + advanceId` + `ON CONFLICT`，命中则复用未结清借据 |
| `PAYMENT_MATCHED`（回补） | `paymentRef` / `matchId` | `uk_credit_restore_match` 唯一键冲突 → 直接返回，不重复扣减 |
| `PAYMENT_MATCHED`（解押） | `paymentRef` | release 侧同理，指令表按 `paymentRef` 唯一 |

> **W1-CREDIT 卡面里"再插同 matchId 事件 → `used_amount` 不再变化"测的就是这一层。**
> 新插一行 outbox 会拿到新 `event_id`，框架层视为新事件、一定会调 `consume()`；
> 挡在它前面的是你自己的唯一键约束。**不要指望 `event_consumptions` 帮你做业务去重。**

### 3.3 `aggregate_key` 禁令：**不许用 `vin`**

`JdbcOutboxPublisher` 的去重条件是"同 (`event_type`, `aggregate_key`) 存在 `PENDING` **或 `SENT`** 的行就不插"，
而 `SENT` 行永久保留 ⇒ **同一聚合键实际上只能发布一条事件**。
所以 `aggregate_key` 必须"一次发生一个值"（`advanceId` / `paymentRef`）：

- 用 `vin` ⇒ 同一台车的第二次合法事件（二次放款、多笔回款）被永久抑制，债与额度都漏记；
- 这是"看起来去重了，实际是把业务吞了"的典型。

⚠️ **两处历史措辞作废**（代码未改，以本文为准）：
`V21` 迁移注释写"aggregate_key：vin 或借据号"，`OutboxPublisher` javadoc 写"通常是 vin 或借据号"。
两处均在 W0 之后由实现证明不成立（V1–V25 迁移冻结不可改；javadoc 待 W3 一并修正）。

---

## 4. 消费侧两张表（V26 新建）

### 4.1 `event_consumptions`

| 列 | 类型 | 语义 |
|---|---|---|
| `event_id` | BIGINT | 主键之一，= `outbox_events.id` |
| `consumer` | VARCHAR(64) | 主键之二，= `EventConsumer.name()`，**用服务名不用类名** |
| `status` | VARCHAR(16) | `PENDING`（已登记未成功）→ `DONE` / `FAILED` / `DEAD` |
| `attempts` | INT | 第几次尝试。**成功那一次也 +1**，所以"首次即成功"= 1 |
| `last_error` | TEXT | 异常摘要，超 500 字符截断；成功时置 NULL |
| `updated_at` | TIMESTAMPTZ | 每次状态变更都刷新（跳过已终结事件时**不刷新**，可用来判"是否真的重放过"） |

状态机：

```
（首次遇到）PENDING/attempts=0
   ├─ consume 成功 ────────> DONE/attempts+1，last_error=NULL，游标前进
   ├─ consume 抛异常 ×1~2 ─> FAILED/attempts+1，last_error 落库，游标不前进，下轮重试同一条
   └─ attempts 达 3 ───────> DEAD，游标前进（跳过）
```

`DEAD` 的含义：**不是"这条不重要"，而是"这条我已经放弃自动处理，等人来"**。
排障入口：`SELECT * FROM event_consumptions WHERE status IN ('FAILED','DEAD') ORDER BY updated_at DESC;`
（V26 为这个查询建了部分索引。）人工重放在本期不做，接缝留给 W3。

### 4.2 `consumer_offsets`

| 列 | 语义 |
|---|---|
| `consumer` | 主键，服务名 |
| `last_event_id` | 该消费者**已处理完**的最大 `outbox_events.id`；首次启动自动建档为 0（从最早事件读起，不丢历史） |
| `updated_at` | 最近一次推进 |

写入带 `WHERE last_event_id < :id` ⇒ **游标只会单调前进**，重放/并发倒灌不会把它调回。
某条事件 `FAILED` 时游标停在上一条 ⇒ 顺序保证（不跳号），代价是一条坏事件会卡住该消费者的后续，
所以有 3 次上限后 `DEAD` 放行。

**多实例限制（已知，未解决）**：游标一行按 `consumer` 存，两个进程用同一个 consumer 名会互相覆盖游标，
导致漏读或重复读。本地冒烟与当前部署都是单实例，本期不加分布式锁；多实例部署前必须改成
`FOR UPDATE SKIP LOCKED` 或按实例名分片。

---

## 5. 消费端三条硬规则（写新 consumer 前读）

`common/event/EventConsumer` 的接口约定，改动会波及所有服务：

1. **必须幂等** —— 同一条事件被投两次，库里的结论只能有一次（§3）。
2. **不许吞异常** —— 校验不通过就 `throw`。`return` 会让残缺事件被标 `DONE`，
   等于把生产方的错误记成"已成功处理"。这是本项目反复出现的假绿源头。
3. **不许在服务间调用** —— 消费逻辑只写自己域内的表。要读别的域，走 §6 的 Feign 契约，
   且不要在 `consume()` 里同步调外部服务把投递线程堵死（重试会放大成雪崩）。

`name()` 与 `eventTypes()` 的规矩：
- `name()` 是 `event_consumptions.consumer` 与 `consumer_offsets.consumer` 的键，**上线后改名 = 换了一个消费者**，
  历史事件会被当新事件重读。固定用服务名（`loan-service`、`credit-service`、`release-service`）。
- `eventTypes()` 返回订阅的事件类型集合。没订阅的类型既不会消费、也不会推进游标。

接线清单（照 `loan-service/consumer/LoanCreatedConsumer.java` 抄）：
```java
@Component                                  // 只扫自身包的微服务能扫到；web 单体也扫得到
public class XxxConsumer implements EventConsumer {
    @Override public String name() { return "xxx-service"; }
    @Override public Set<String> eventTypes() { return Set.of("EVENT_NAME"); }
    @Override public void consume(DomainEvent e) {
        long advanceId = e.longField("advanceId");      // 取数只走 DomainEvent 的 helper
        String vin = e.textField("vin");
        // 业务写在这里（同事务、幂等）
    }
}
```
`DomainEvent.textField/longField` 内部走 `JsonPayloadReader`（全仓 payload 取数的单一口径）。
payload 不是合法 JSON、字段缺失 → 抛 `IllegalArgumentException`，**不要 catch 掉**。

---

## 6. Feign 契约（跨服务同步读）

### 6.1 本地直连开关（不启 Nacos 也能真调通）

契约统一写成 `url = "${floorplan.clients.<key>.url:}"`：

| 属性键 | 环境变量 | 本地值 | 对端服务 |
|---|---|---|---|
| `floorplan.clients.loan.url` | `FLOORPLAN_CLIENTS_LOAN_URL` | `http://127.0.0.1:8085` | loan-service |
| `floorplan.clients.credit.url` | `FLOORPLAN_CLIENTS_CREDIT_URL` | `http://127.0.0.1:8083` | credit-service |
| `floorplan.clients.collateral.url` | `FLOORPLAN_CLIENTS_COLLATERAL_URL` | `http://127.0.0.1:8086` | collateral-reg-service |

- 给了值 → 直连；不给（生产）→ 空串回退 `lb://服务名`，**行为零变化**。
- **属性键只能用小写单个单词**（`loan`/`credit`/`collateral`，不要 `collateral-reg`）：
  Spring 宽松绑定把环境变量里的下划线解析成点，`FLOORPLAN_CLIENTS_COLLATERAL_REG_URL` 会被读成
  `floorplan.clients.collateral.reg.url`，带连字符的键无法用环境变量稳定表达。
- 三个值已在 `run-microservices.sh` 里全局 export，且与网关 `ROUTE_*` 严格一致（两处不一致必有一处骗人）。
- **调用方启动类必须有 `@EnableFeignClients(basePackages = "com.company.floorplan.common.contract")`**，
  否则接口不生成 bean，写好的 client 是死的。W1 新增调用方时先确认这条。

### 6.2 四个契约接口

| 接口 | 方法 → 路径 | 返回 | 状态 |
|---|---|---|---|
| `LoanQueryClient` | `GET /api/loans/by-vin/{vin}` | `ApiResponse<LoanSnapshotDto>` | ✅ **W0 新增，实现方已存在且冒烟验过** |
| `CreditLineQueryClient` | `GET /api/credit-lines/{id}/available` | `ApiResponse<Long>` | ⚠️ **W0 新增，实现方待 W1-CREDIT** |
| `CollateralClient` | `GET /api/collateral-reg/registration?vin=` | `RegistrationVO`（裸 VO） | 已有，W0 只加 `url` |
| `CreditLineClient` | `GET /api/credit-lines/available?dealerId=` | `BigDecimal` | ❌ **死契约，无实现方** |

> **为什么新增的都返回 `ApiResponse<T>` 而不是裸对象**：实现方 `LoanController` 返回的就是
> `ApiResponse` 包装。若契约声明裸 `LoanSnapshotDto`，Jackson 会把 `{code,message,data,traceId}`
> 往 DTO 上贴，**所有字段全 null 且 HTTP 200** —— 调用方拿到"查到了但都是空"，比报错更难发现。
> `CollateralClient`/`CreditLineClient` 返回裸对象是 Wave 0 之前的历史形状，其实现方也按裸对象返回，
> 所以那两个保持不动。**新契约一律 `ApiResponse<T>`。**

### 6.3 `LoanQueryClient` 真实响应样例（2026-11-22 实测捕获）

命中：

```json
{
  "code": "OK",
  "message": "OK",
  "data": {
    "id": 2,
    "loanNo": "LN-900777",
    "advanceId": 900777,
    "vin": "W0DOC-VIN-0001",
    "dealerId": 12,
    "creditLineId": 34,
    "principal": 5400000,
    "dueDate": "2026-12-31",
    "status": "ACTIVE",
    "createdAt": "2026-11-22T12:55:53.720677",
    "gracePeriodDays": 3,
    "termSource": "CONTRACT",
    "principalOutstanding": 5400000,
    "interestOutstanding": 0,
    "penaltyOutstanding": 0,
    "feeOutstanding": 0,
    "currentDpd": 0,
    "maxDpdEver": 0,
    "overdueCount": 0,
    "deductionFailStreak": 0,
    "deductionFailTotal": 0,
    "defaultFlag": false,
    "version": 0,
    "updatedAt": "2026-11-22T12:55:53.720677"
  },
  "traceId": "22fec440-f73b-42e3-9556-6f7555d38094"
}
```

未命中：

```json
{
  "code": "NOT_FOUND_001",
  "message": "未找到该 VIN 的未结清借据: vin=NO-SUCH-VIN",
  "traceId": "b369ce68-c427-4155-bd11-695460afee20"
}
```
HTTP 状态码 **404**（不是 200 + `data=null`）。字段两条实测确认：
- **未命中的字段不出现在 JSON 里**（序列化省略 null），DTO 侧对应字段就是 null；
- `details` 为 null 时整个键省略，所以响应可能是 3 个键而不是 5 个。

`LoanSnapshotDto` 只声明 7 个字段：`id, vin, advanceId, dealerId, status, principal, dueDate`。
**这是投影，不是全量**：`currentDpd`、`principalOutstanding` 等响应里有但 DTO 里没有，
调用方**不要依赖未声明字段**（实现方哪天裁掉不会通知你）。需要更多字段 → 改 DTO + 本文 + 通知全体。
`smoke_w0.py` 第 5 步会用真实响应逐字段校验这 7 个字段的存在与取值，改一边忘另一边会红。

**调用方语义约定**（W1-BANK 已按此写单测）：
- 404 → `Optional.empty()`（"这个 VIN 没有未结清借据"是合法业务事实）；
- 5xx / 超时 / 连接失败 → **抛异常**，不许也映射成 empty（fail-closed：把"服务不可达"当成"没借据"会误放资金）。
  W0 未提供 fallback —— 把两者区分开的口径属于业务裁决，实现方（bank）在 W1 自己判。

### 6.4 `CreditLineQueryClient` 期望响应（实现方待 W1-CREDIT）

```json
{ "code": "OK", "message": "OK", "data": 41500000, "traceId": "..." }
```

- `data` 是**整数日元**（`total - used - frozen`，负数截 0），不是小数、不是字符串；
- 额度不存在 → 404 + `NOT_FOUND_001`（bank 侧按 fail-closed 处理）；
- **实测现状（2026-11-22）**：`GET /api/credit-lines/34/available` → **HTTP 500**
  `{"code":"SYS_001","message":"系统内部错误：No static resource api/credit-lines/34/available."}`
  —— 该路径在 credit-service 上没有控制器，落到了静态资源兜底。
  所以 W1-CREDIT 必须实现它（并加 `@RequirePermission` 读权限），或删除这条契约。
  `smoke_w0.py` 对此**只打印不断言**（断言"现在必须是 500"会在 W1 实现后反过来变红，两种都是假信号）。

---

## 7. W0 遗留与已知缺口（交给后续波次的清单）

| # | 缺口 | 归属 | 说明 |
|---|---|---|---|
| 1 | `OutboxPublisher` **零生产调用方** | W2-CORE（`LOAN_CREATED`）、W1-BANK（`PAYMENT_MATCHED`） | 发布端实现已完成但没有业务代码调它，"发布去重"因此无法端到端冒烟（见下） |
| 2 | 发布端去重端到端 0 验证 | **W1-BANK 收口**（W2-CORE 复验），W3 合并进 `smoke-test.py` | 卡面 smoke 第 4 步（"同 (`event_type`,`aggregate_key`) 已存在 PENDING 时断言不重复插入"）用 psql 验不了：psql 直插不走发布端，拦它的是发布端 SQL 里的 `WHERE NOT EXISTS`（抑制条件实查为 `status IN ('PENDING','SENT')`），不是表约束。**2026-11-22 用户裁决 = A：不扩权建 `infrastructure/src/test`**——不为一段尚无调用方的 SQL 引入全仓第一个连库测试，`mvn -B clean package` 保持不依赖 5434。改由第一个真实生产者接线时在冒烟里验；⚠️ 注意「同一 transaction_id 再匹配一次」这条断言**归因不唯一**（会先被 `uk_payment_match_txn` 挡住），要把机制单独钉住见 `PROGRESS.md §7` W0-3 行的取法。判据与风险登记同步在案，勿再重复申请扩权 |
| 3 | `CreditLineQueryClient` 无实现 | W1-CREDIT | §6.4 |
| 4 | `CreditLineClient`（`GET /api/credit-lines/available?dealerId=`，返回 `BigDecimal`）全仓无实现，只被 bank 的本地存根实现 | W1-CREDIT 处置 | 与 §6.4 是两条不同路径、两种不同金额类型。实现或删，别留死契约 |
| 5 | `COLLATERAL_CHANGED` 的 `aggregate_key` 与 `status` 取值域未定源 | W2-CORE / 权属 owner | §2.3 |
| 6 | DEAD 事件的人工重放 | W3 | 表结构与状态已就位，缺运维入口 |
| 7 | 多实例共游标互相覆盖 | 拆库/多实例前必须处理 | §4.2 |
| 8 | `OutboxPublisher` javadoc 仍写"通常是 vin 或借据号" | W3 顺手改 | 与 §3.3 冲突，实现已按 §3.3 |
