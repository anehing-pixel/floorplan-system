# AGENT TASK · W2-CORE（放款确认处发 `LOAN_CREATED` · 第一个真生产者）

## 你的身份

你是 **W2-CORE** agent，承接 `docs/并行开发-剩余需求拆分方案.md §11` 表里第 5 行交付
「**放款成功后发 `LOAN_CREATED`（同事务）**」。那张表还有另外五项（readiness 三要素、6 个端点、
`notify`/`legal-action` Controller），**都不在本卡范围内**，别顺手扩。

- worktree：`C:/Users/Administrator/Documents/GitHub/wt-w2-core`，分支 `agent/w2-core`
- 基线：**取最新的 `base-*` 标签**，不要写死 commit 号——

  ```bash
  git for-each-ref --sort=-creatordate --format='%(refname:short)' refs/tags/base-* | head -1
  ```

  派单时（2026-11-22）该标签是 `base-w0-20261122`。**旧的 `wt-core` worktree / `agent/core` 分支停在
  比基线旧一整波的位置（事件机制落地之前），不要复用它。**
- 中文交流与注释，类/方法英文。

## 硬性边界（违反即合并失败）

1. **只写这些**：
   - `application/src/main/java/com/company/floorplan/application/service/AdvanceApplicationService.java`
   - `application/src/main/java/com/company/floorplan/application/event/`（本卡新增，端口 + command）
   - `web/src/main/java/com/company/floorplan/web/`（Controller、适配器、启动类 `@Import`）
   - `scripts/smoke_w2_core.py`、`web/HANDOFF-W2-CORE.md`
2. **绝不修改**：`common/`、`infrastructure/`、`domain/`、父 `pom.xml`、
   **任何 `V1`–`V26` 迁移**、`smoke-test.py`、`PROGRESS.md`、`AGENTS.md`、`run-microservices.sh`、
   `scripts/agent_env.sh`、`scripts/agent_smoke_lib.py`、`gateway/src/main/resources/application.yml`、
   `web/src/main/resources/static/js/app.js`、`ai-service/`、**其他任何服务目录**。
3. 特别点名 **`loan-service/` 禁改**：消费侧建据（`createFromLoanCreated`）现在是**刻意注释态**，
   归 W1-LOAN。你把它取消注释 = 越权 + 合并冲突。
4. **本项不需要新迁移**（`outbox_events` 是 V21，载荷全在 TEXT 里，无新列）。
   `V28` 是那张表里「客户支付确认凭证」那一项的号段，**别为这件事建表**。
5. 不 `git merge` / `git rebase`；完成后打 tag `agent/w2-core-done`，由收口方合并。
6. **提交一律 pathspec 形式** `git commit -m "…" -- <路径>`：master 上现在有两个会话在同一个工作树、
   同一个 `.git/index` 上交替提交，`git commit` 不带路径会把对方刚 stage 的文件卷进你这一笔
   （`AGENTS.md §7.2`，2026-11-22 实测）。

## 开工前

```bash
cd C:/Users/Administrator/Documents/GitHub/wt-w2-core
cat docs/接口契约-事件与Feign.md        # §2 生产方、§2.1 字段口径、§3.3 vin 禁令、§5 消费端规则
bash scripts/agent_env.sh init  w2_core
eval "$(bash scripts/agent_env.sh env w2_core 600)"   # 8680/8681/8685 + 18680 + FLOORPLAN_CLIENTS_*
bash scripts/agent_env.sh check w2_core                # 基准仍是 67；报 -1 = 还没起 web 迁过
```

## 背景一：接线点是 `confirmAdvance`，**不是 `disburseAdvance`**

拆分方案 §11 那行原文写的是「`disburseAdvance` 成功后发 `LOAN_CREATED`」，
而**冻结契约** `docs/接口契约-事件与Feign.md §2` 写的是「生产方触发时机 = core（**银行回调 PAID 之后**）」。
两者不成立同一件事，代码把它们摊开了：

| 方法 | 位置 | 状态推进 | 钱到账了吗 |
|---|---|---|---|
| `disburseAdvance` | `AdvanceApplicationService:344` | → `PROCESSING`（提交支付指令） | **没有**，只是把指令交给通道 |
| `submitAdvance` | 同上 `:509` | `PENDING` → `PROCESSING` | 没有 |
| **`confirmAdvance`** | 同上 `:530` | → **`PAID`** + 车辆 `IN_STOCK` + 台账建档 | **是**，银行 webhook 确认回填 |

在 `PROCESSING` 就发事件，等于让借据、额度、DPD、对账四条线围着一笔**还没到账的钱**记账——
而契约 §2.1 对 `advanceId` 的口径写得很直白：「为空表示银行未 PAID —— 建据被拒（钱没到，债不成立）」。
**按契约接到 `confirmAdvance`。** 如果你判断应该按拆分方案那行做，停下来问用户，不要自己选。

好消息是幂等已经现成：`confirmAdvance` 在 `:533-537` 对已终态**直接早返回**，
所以重复回调根本走不到发布那一行——这条正是「重复放款回调不重复发事件」那条验收的实现基础。

## 背景二：为什么不能直接注入 `OutboxPublisher`（派单时逐个 pom 核过，别再试一遍）

| 事实 | 出处 | 后果 |
|---|---|---|
| `application` **不依赖** `common` | `application/pom.xml` 依赖只有 domain + spring-context/tx + jjwt + crypto + poi | `AdvanceApplicationService` 里 `import ...common.contract.OutboxPublisher` **编译不过** |
| `common` **反过来依赖** `application` | `common/pom.xml:23-26`（`JwtAuthInterceptor` 要用 `AuthApplicationService.verify`） | 给 application 加 common 依赖 = **Maven 循环**，`clean package` 直接炸 |
| `infrastructure` 只依赖 domain + common | `infrastructure/pom.xml` | 「端口写 application、适配器放 infrastructure」这条也走不通 |
| `web` 同时依赖 application + infrastructure + common | `web/pom.xml:19-31` | **适配器只能放 `web/`** —— 恰好就是拆分方案声明的写入集 |

所以结构是：**端口在 application（纯 POJO，不引 Jackson）→ 适配器在 web（用 Jackson 序列化，转调 `common` 的 `OutboxPublisher` bean）**。
`JdbcOutboxPublisher` 这个 bean 已由 web 启动类 `@Import` 注册好了，不用你装配。

## 任务

### 1. 端口（application 层，新增 2 个文件）

`application/event/LoanCreatedCommand`：字段就是契约 §2.1 那七个，`long advanceId` / `String vin` /
`Long dealerId` / `Long creditLineId` / `long principal` / `String traceId`（`dueDate`、`gracePeriodDays` 见任务 3）。
**保持可纯单测**：不注入 `JdbcTemplate`、不读配置、不 `new` 客户端（`AGENTS §10`）。

`application/event/LoanCreatedPublisher`：`void publish(LoanCreatedCommand cmd)`。

### 2. 适配器（web 层，新增 1 个类）

`web/adapter/OutboxLoanCreatedPublisher implements LoanCreatedPublisher`，注入 `common.contract.OutboxPublisher`，调

```java
publisher.publish(OutboxPublisher.EVENT_LOAN_CREATED, String.valueOf(cmd.advanceId()), json);
```

三条硬规矩：

- **`aggregate_key` = `advanceId`，禁止用 `vin`**。契约 §3.3：发布端去重条件是「同
  (`event_type`, `aggregate_key`) 存在 `PENDING` **或 `SENT`** 行就不插」，而 `SENT` 行永久保留
  ⇒ 同一聚合键实际上只能发布**一条**。用 vin 会把同车的第二笔合法垫款永久吞掉。
- **不要手拼 JSON 字符串**。用 `ObjectMapper` 序列化（web 侧有 Jackson）。
  `paymentRef` 来自银行回调请求体（`AdvanceController:129-130`），是外部字符串，手拼遇到引号就是坏 JSON
  或字段注入，而坏 JSON 会在**放款确认的事务里**抛异常。
- **不许 catch 发布异常**。`JdbcOutboxPublisher.publish` 在写库前就校验 payload（缺 `vin`/`traceId`
  抛 `IllegalArgumentException`），同事务回滚 = 这笔确认失败。这是契约 §5 的 fail-closed，
  不是 bug——所以 `traceId` 必须真的传进来（任务 3）。

### 3. `traceId` 的取法与透传（本卡最容易踩空的一环）

`application` 层拿不到 `HttpServletRequest`，全仓 **MDC 零使用**（`grep -rl MDC --include=*.java .` 为空），
所以 traceId 只能**显式传参**，口径与仓库现有的 `AuditActor.resolve(request)` 传 actor 是同一个套路：

1. `AdvanceController.confirm`（`:123`）从请求属性取：
   `String traceId = String.valueOf(request.getAttribute(RequestLoggingFilter.ATTR_TRACE_ID));`
   （`RequestLoggingFilter` 对 `/api/**` 每个请求都写这个属性，`:120-121`。）
2. 一路传下去：`confirmByAdvanceNo(advanceNo, ref, actor, traceId)` → `self.confirmAdvance(id, ref, actor, traceId)`。
3. 派单时核过引用面：`confirmAdvance` 全仓**只有 1 个调用方**（`AdvanceApplicationService:700`）+
   接口注释若干，**没有任何测试引用**；`confirmByAdvanceNo` 只有 `AdvanceController:132` 一处。
   改签名是安全的——但你要先自己 `grep` 复核一遍，别信这句话。
4. **顺带统一 `confirm` / `fail` 两个回调端点的响应 traceId**：它们现在返回
   `UUID.randomUUID().toString()`（`AdvanceController:133`、`:149`），和过滤器生成的那个**不同源**，
   前端拿到的是对不上日志的号。只改这两行，别把整个 Controller 扫一遍——
   同样的写法在 `:81 :97 :114 :180` 也有（那是别人的端点，记进 HANDOFF 交给收口方，不在本卡范围）。

若 `traceId` 取到 `"null"`（过滤器没跑到的路径，例如内部直接调用），**让它抛出去**，不要兜底造一个 UUID——
那等于把「跨服务串不上链」这件事伪装成成功。在 HANDOFF 里记下这个决定。

### 4. `dueDate` / `gracePeriodDays` 本期**不发**（等业务定源）

契约 §2.1 把这两个字段列为「建议」，而 core 侧**没有权威源**：唯一像样的出处是
`financing_terms`（`V11__postlending_monitoring.sql:74-90`，列 `new_maturity_date` / `new_grace_days` /
`term_source`），但派单时实查**全仓零写入方**——唯一的 Java 引用是
`infrastructure/persistence/SqlStructureSelfCheck`，它只数表存不存在。也就是说这张表现在是空的。

后果要说清楚，别当"缺失即失败"：`LoanApplicationService.generateRepaymentPlan`
（`loan-service/.../service/LoanApplicationService.java:94-98`）对 `dueDate == null` 是
`log.warn("借据 {} 期限未录入，跳过还款计划生成")` + `return 0`，**不抛异常、不阻断建据**。
所以本期发出去的借据会有、还款计划不会生成，这比造一个假到期日好。
**不许为了「看起来完整」填 `paidAt + 90 天` 之类的默认值**（AGENTS 铁律 3）。
要接 `financing_terms`，得先有人裁决「谁写它、以什么为准」，那是一条独立的定源项，用户点头后才能做。

### 5. 调用点与装配

- 调用点：`confirmAdvance` 里 `advanceRepository.save(a)`（`:553`）之后、`return a`（`:561`）之前，
  **同一个 `@Transactional` 内**（契约 §0：业务写库与 outbox 同一本地事务）。
  不要放在 `markFailed` / `voidAdvance` 里——FAILED 不发事件。
- 装配：`FloorplanApplication` 的 `@Import({JdbcOutboxPublisher.class, EventDispatcher.class})`（`:30`）
  里加上你的适配器类。**适配器不要加 `@Component`**——同一个类的注释写了原因：web 的
  `@ComponentScan` 覆盖整个 `com.company.floorplan`，「扫描 + `@Import`」会注册出两份同类型 bean，
  按类型注入直接 `NoUniqueBeanDefinitionException`。

## 任务 6：冒烟断言 `scripts/smoke_w2_core.py`

用 `scripts/agent_smoke_lib.py`（不要改它）。**判据一律查库，不看 HTTP 返回码**——
「200 + 没发事件」是这个项目最经典的假绿。最少这几条：

1. **发得出**：走完整真实链路（录入 → 申请 → 复核 → 放款 → HMAC 回调 confirm，参考
   `smoke-test.py` 里 `ingest_and_advance()` 的走法）之后，`outbox_events` 里该
   `event_type='LOAN_CREATED' AND aggregate_key=<该 advanceId>` **恰好 1 行**；
   payload 里 `vin`/`advanceId`/`traceId` 齐、`principal` = 垫款金额（整数日元，不许是小数或字符串）。
2. **业务幂等**：同一 `advanceNo` 用合法签名再回调一次 → 该键仍 1 行、垫款仍 `PAID`。
   这条走的是 `confirmAdvance` 的终态早返回，**不是**发布端去重——别在交付说明里把它写成「已验 `NOT EXISTS`」。
3. **机制层（W0-3 在 `LOAN_CREATED` 上的复验）**：把两种去重分开钉，取法如下——
   - psql 手工 `INSERT` 一行 `(event_type='LOAN_CREATED', aggregate_key='<新 advanceId>', status='SENT')` 作**夹具**；
   - psql 把那条 advance 的 `status` 退回 `PROCESSING`（绕过状态机，仅测试夹具，脚本里写明这句）；
   - 再走一次真实 confirm 回调 → 断言该键**仍只 1 行**且没变成 2 行 ⇒ 拦下来的是发布端 SQL 里的
     `WHERE NOT EXISTS`（抑制条件实查为 `status IN ('PENDING','SENT')`），不是表约束、不是业务幂等。
   - cleanup 里删掉夹具行与这条测试造的数据（`atexit` 注册，崩溃也要删）。
   - ⚠️ 别用「同 VIN 再申请一笔」来验这条——那会先被 ADR-010 的唯一垫款防线挡住，判因不唯一。
4. **键选对了**：断言本 agent 发出的行 `aggregate_key` **等于 `advanceId` 字符串**、且不等于 VIN
   （一条 `WHERE aggregate_key = '<vin>'` 计数为 0 的断言就够，防的是以后有人"顺手优化"回 vin）。
5. **边界自证**：断言 `loans` 表**仍然为空**，并打印一行提示「建据归 W1-LOAN，`createFromLoanCreated` 仍是注释态」。
   这条是防止你越界改 loan-service，不是让你去做。

## 交付

- `web/HANDOFF-W2-CORE.md`：改动清单（文件:行）、traceId 透传链路、
  **为什么接线点选 `confirmAdvance`**（把 §11 那行的冲突写明，便于收口方改方案）、
  机制层那条断言的**实测输出**（贴 PASS 原文，不要写计划）、
  `financing_terms` 缺写入方这条定源需求的处置建议。
- `scripts/smoke_w2_core.py`。主干 `smoke-test.py` 由**收口方**合并，你不要碰。

## 验证（三道，缺一不可）

```bash
# 1) 编译 + 全量单测
mvn -B clean package                        # 全 SUCCESS 才算过（并行时用 -B package 不带 clean，见 AGENTS 坑 4）

# 2) 隔离语义验证（schema w2_core + 偏移 600，启动→跑→清理必须在同一条命令里）
env -u SERVER__PORT PYTHONIOENCODING=utf-8 bash -c '…起 web/auth/loan/credit…; python3 scripts/smoke_w2_core.py; …kill…'

# 3) 交付判据：回 public 跑主干全量，确认零回归
unset FLOORPLAN_DB_SCHEMA SPRING_DATASOURCE_URL
env -u SERVER__PORT PYTHONIOENCODING=utf-8 bash run-microservices.sh smoke
```

- 第 3 道看 **FAIL=0 且主干既有断言零回归**（派单时 211 条，这个数字会随开发变，别写死）。
  ⚠️ 本卡有个真实回归风险：放款确认事务里新增了一次 outbox 写，**payload 校验失败会让回调 500**，
  于是主干所有走 `confirm` 的断言一起红。这就是任务 3 把 traceId 讲透的原因。
- 隔离 schema 里跑绿**不等于**交付；只有回到 `public` 那一次才算（AGENTS §5 / 隔离手册 §5）。

## 禁止

- 改 `loan-service` / `bank-service` / `credit-service` 的任何文件；取消 `LoanCreatedConsumer` 里那行注释。
- 打开 `floorplan.core.guarantee-gate.enabled`（默认 `false`，开启条件是「Q27 判定路径改造全部落地」，归用户单独裁决）。
- `aggregate_key` 用 `vin`；catch 发布异常；给 `dueDate`/宽限期造默认值；手拼 JSON 字符串。
- 为本卡建迁移；新建空迁移文件。
- 为了「让 `loans` 表不再空」去跨服务直写别人的表（AGENTS §3 明令禁止，物理拆库那天当场失效）。

## 依赖与顺序（如实说明，别承诺你给不了的东西）

本项做完 = **事件发得出了**。`loans` 表照旧为空，因为消费侧建据仍归 W1-LOAN
（`docs/AGENT_TASK-w1-loan.md`，`createFromLoanCreated` 全仓无调用方）。
两张卡可以并行开发，但「回调后 `loans` 有行」这种端到端断言**只能在 W1-LOAN 合并之后**加，
不要写进本卡验收，否则你会为了凑绿去碰 loan-service。

W1-BANK 侧的 `PAYMENT_MATCHED` 机制层断言（那张卡任务 6 第 3 步）与本卡任务 6 第 3 步是同一个机制的两半，
**两边都要交实测输出**，收口方才会把 W0-3 那条挂账翻成已闭环。
