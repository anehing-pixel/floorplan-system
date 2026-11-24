# AGENTS.md

> 在本仓库工作的 AI agent 操作手册，人类协作者同样适用。
>
> **分工**：本文件只讲「怎么在这个仓库里干活」；「做到哪一步了」看 `PROGRESS.md`；
> 「为什么这么设计」看 `docs/`。三者不互相替代。
>
> **协作模式**：本仓库由**单个 agent 串行开发**，不再做多 agent 并行拆分。
> 原先的波次（W0–W3）、任务卡、写入集、schema/端口隔离那一套已经废弃，
> 相关文档与脚本（`docs/AGENT_TASK-*.md`、`docs/并行开发-*.md`、`scripts/agent_env.sh`、
> `scripts/agent_smoke_lib.py`、`scripts/smoke_w*.py`）已删除。看到残留引用时按删除处理，不要照做。

---

## 0. 三条铁律

1. **先读 `PROGRESS.md` 再动手。** 它比任何聊天记录、路线图都新，是本仓库唯一事实来源。
2. **验证判据是「冒烟全绿」，不是「编译通过」。** `mvn package` 成功只说明语法对；
   这个项目历史上多次出现「编译过了、端点存在、网关 404」的情况。
3. **不猜业务口径，但允许按采纳默认值实现。** 未签字的口径一律走这条链：
   取 `docs/二手车库存垫资改造-01-业务定源ADR草案.md` 的「采纳默认值」→ 写成配置键而不是硬编码 →
   落点标 `[待定源 D-xx]` → 汇总进 `PROGRESS.md` 的待签字清单。
   **四条红线不适用这条链**（见 §8）：它们没有「先按默认值实现」的余地，也不许留任何绕过开关。

---

## 1. 项目定位

日本汽车经销商**库存融资（floorplan）核心系统**：经销商以在库车辆做让渡担保，从资金方取得垫款，
车辆售出后回款解押。原为 Rails 单体，正在重写为 Java 17 + Spring Boot 3.2.5 并做微服务化。
当前主线是**二手车库存垫资改造（USED 域）**，设计基准见 `docs/二手车库存垫资改造-00-设计基线与命名定源.md`。

**当前真实状态**：核心（新车）链路后端主干可运行、全 Maven 模块全绿（模块数以父 `pom.xml` `<modules>` 为准）；
事件机制（发布/轮询/游标/消费幂等/死信）已落地，两条业务线已接上：
放款确认 → `LOAN_CREATED` → 建借据；回款匹配 → `PAYMENT_MATCHED` → credit-service 真实额度回补（2026-11-24）。
但**权属变更 `COLLATERAL_CHANGED` 仍零调用方**、**工单表没有 Java 类**；
回款匹配的解押已由 core（`floorplan-core`）消费同一事件落地（2026-11-24），
但 release-service 的指令域消费与「银行匹配 vs 直接还款」的共用 `settle_seq` 幂等仍未接。
USED 域目前**只有库结构与字典层**（V40–V74 + 状态机枚举），业务编排层在重写中。
不要假设「表建了就是功能有了」。

`ai-service/` 是 Python deep-agent 模板骨架，**不在本期范围**，不要改它。

---

## 2. 开工前阅读顺序

| 顺序 | 文件 | 读它是为了 |
|---|---|---|
| 1 | `PROGRESS.md` | 当前断点、阻塞项、未开发需求全量清单（§8）、踩坑记录 |
| 2 | `docs/库存融资核心系统-代码导读.md` | 代码结构导航 |
| 3 | `docs/库存融资核心系统-十一环节闭环核查报告.md` | 业务闭环基准，断点 B1–B4 / M1–M12 的定义 |
| 4 | `docs/库存融资核心系统-目标微服务架构设计.md` | 服务边界与职责划分 |
| 5 | `docs/接口契约-事件与Feign.md` | 事件载荷 / DTO 字段 / Feign 直连地址的**冻结源**，碰契约前必读 |
| 6 | `docs/二手车库存垫资改造-00-设计基线与命名定源.md` | USED 域的表名/枚举/错误码/权限点/配置键/幂等键定源，做 USED 前必读 |
| 7 | `docs/二手车库存垫资改造-07-接口文档.md` · `-08` · `-09` | USED 接口契约、估值与 LTV 定价、放车放证闭环的规格源 |

`docs/00-文档索引.md` 有全量文档目录，按需查。

---

## 3. 技术栈与模块地图

**技术栈**：Java 17 · Spring Boot 3.2.5 · Spring Cloud Gateway · Spring Cloud Alibaba Nacos（本地不启）·
PostgreSQL 16（Docker 容器）· Flyway · 原生 JDBC（`JdbcTemplate`，**没有用 JPA/MyBatis**）· JUnit 5 + Mockito。

### 3.1 分层模块（依赖方向严格自上而下）

```
web (core 单体, 端口 8080)  ──┐
gateway (18080)              │
auth/bank/credit/risk/       ├──► application ──► domain
loan/collateral-reg/         │         │            ▲
release/recon (各自独立进程)  ──┘         └──► infrastructure ──┘
                                     common（横切：鉴权、异常、契约）
external-api (DMZ, 18090)  ──► 只连 integration schema，不连 public
```

| 模块 | 职责 | 注意 |
|---|---|---|
| `domain` | 实体、状态机、纯业务策略（如 `RoleSeparationPolicy`、`DpdCalculator`）、`ErrorCode` | **不依赖 Spring 容器**，保持可纯单测 |
| `application` | 用例编排、事务边界、跨聚合协调 | `@Transactional` 只出现在这一层 |
| `infrastructure` | 仓储实现（JDBC）、Flyway 迁移、外部适配 | 迁移文件全在这里 |
| `common` | `JwtAuthInterceptor`、`@RequirePermission`、`ApiResponse`、`GlobalExceptionHandler`、Feign 契约 | 跨服务契约放这里，别在服务里各写一份 |
| `web` | core 单体，仍承载绝大多数业务端点 | 拆分尚未完成，新端点优先放对应服务 |
| `gateway` | 统一入口、路径路由、`RequestIdFilter` | 新增内部端点不经网关；要加路由必须配一条从网关打进去的断言 |
| 9 个 `*-service` | 独立进程，各自 `application.yml` + 启动类 | 见下方端口表 |
| `external-api` | USED 外部接入面（DMZ） | 现只有骨架（pom + 启动类 + yml），五件套能力见 D02 §3.3；「模块存在」不等于「外部接口已通」 |

**跨服务通信规则**：优先 Feign 契约（`common/contract/`）或事件；
**禁止新代码直接读别的服务的表**——现在共库让这件事「能跑」，但物理拆库时它会立刻失效。

---

## 4. 本地环境事实（照抄，不要自己发明）

| 项 | 值 |
|---|---|
| 仓库根 | `C:/Users/Administrator/Documents/GitHub/floorplan-system` |
| 主分支 | `master`；USED 改造在 `used-floorplan-dev` |
| 数据库 | `jdbc:postgresql://localhost:5434/floorplan`（**5434**，5432 被占用；库/用户/密码均 `floorplan`） |
| 容器 | `docker exec floorplan-db2 psql -h 127.0.0.1 -p 5434 -U floorplan -d floorplan`（容器内没有 `psql` 于宿主 PATH） |
| 编译 | Windows JDK 17 + Maven 3.9.16 |
| 注册中心 | 本地**不启 Nacos**，各服务 `fail-fast=false`，注册失败只告警 |

`floorplan` 库是 2026-11-23 从仓库迁移链**干净重建**的（56 版一路跑到 V74）。
重建前的旧库改名保留为 `floorplan_legacy_20261123`，整库备份在 `backups/pre-used-rebuild-20261123-102807.sql.gz`。
旧库的 Flyway history 记到 V27，而 `V27__release_payment_ref.sql` 只存在于未合并的 `agent/w1-release` 分支——
那是并行期遗留的仓库↔库漂移。**要查历史数据去 legacy 库，不要把它接回标准连接串。**

### 端口分配

| 服务 | 端口 | 服务 | 端口 |
|---|---|---|---|
| web (core) | 8080 | loan | 8085 |
| auth | 8081 | collateral-reg | 8086 |
| bank | 8082 | release | 8087 |
| credit | 8083 | recon | 8088 |
| risk | 8084 | gateway | **18080**（统一入口） |
| external-api | **18090** | USED 的 DMZ 进程，**不经网关**、不连 `public` | — |

### 四个必须知道的坑

1. **`SERVER__PORT` 污染**：宿主注入的环境变量会被 Spring 宽松绑定成 `server.port`，覆盖 yml 端口。
   命令行启动一律 `env -u SERVER__PORT java -jar ...`（`run-microservices.sh` 已处理）。
2. **后台 JVM 会随 shell 回收**：Git Bash 下用 `&` 起的进程在父 shell 退出时被杀。
   所以「启动 → 跑测试 → 清理」必须在**同一条命令**内完成，不要分两次调用。
3. **只有 `web` 跑 Flyway**：其余服务 `flyway.enabled=false`，避免并发迁移。新增迁移只放
   `infrastructure/src/main/resources/db/migration/`。
4. **`mvn clean` 可能被文件锁挡住**：运行中的服务会占用 `target/*.jar`。
   遇到 `Failed to delete` 先 `jps -l` 看有没有别的 Java 进程，改用 `mvn -B package`（不带 clean）。

### Windows 终端的两个取证陷阱

- **不要把 mvn 输出接管道给 `tail`**：`mvn ... | tail` 的退出码是 `tail` 的，
  实测出现过 BUILD FAILURE 而 `$?` 报 0。要截断就重定向到文件再 `Read`，或 `grep -E "BUILD|ERROR" file`。
- **`python` 不是 `python3`**（Git Bash + miniconda 里只有 `python`）。冒烟一律
  `PYTHON=python PYTHONIOENCODING=utf-8 bash run-microservices.sh smoke`，
  否则解释器找不到（EXIT 127）或 stdout 一重定向就回落 cp936，`smoke-test.py` 的带圈数字（⑪–㉕、✅）崩栈，
  屏幕刷半屏绿但只打了 51 条断言。

---

## 5. 构建与验证（硬门槛）

```bash
# 1) 全量编译 + 全量单测（全部 build unit 必须 SUCCESS；
#    数 = 父 pom `<modules>` 条目数 + 父本身，**不要在文档或脚本里写死**，看 FAIL 就行）
mvn -B clean package

# 2) 单模块快速验证（改 domain/application 时用这个，快）
mvn -B -pl domain,application -am test

# 3) 起全部服务走网关跑冒烟，自动清理（一条命令内完成）
PYTHON=python PYTHONIOENCODING=utf-8 bash run-microservices.sh smoke

# 其他
bash run-microservices.sh start   # 前台保活，Ctrl+C 停
bash run-microservices.sh stop    # 另开窗口停
bash run-microservices.sh redis-smoke  # Redis 锁实例验证（需 Docker；先 bash run-redis.sh）
bash run-local.sh                 # 只起 web 单体
```

### 交付判据

> `bash run-microservices.sh smoke` **EXIT 0，且 PASS 数 = 断言总数、FAIL = 0**。

- 断言数会随开发增长（历史值 182 / 204 / 211），**不要把数字写死在文档或脚本里**，看 FAIL 就行。
- 只跑 `-DskipTests` 的编译**不算验证**，不能作为「已完成」的依据。
- 改了某个能力却没在冒烟里加对应断言 → 视为未完成。端到端断言写进根目录的
  `smoke_used.py` / `smoke_payment_matched.py`（或新开一个 `smoke_*.py`），
  并收进 `run-microservices.sh smoke` 的调用链里。

---

## 6. 数据与迁移规则

### 6.1 已落库迁移永不修改

`V1`–`V74` 已在重建后的 `public` 上落库（`flyway_schema_history` 56 行，现位于 v74）。
**改这些文件 = Flyway checksum 校验失败 = 全服务启不动。** 要改结构就新增一版。
同理：不要建空迁移文件——Flyway 会把它当成一次真实迁移执行。

### 6.2 号段

| 号段 | 用途 | 状态 |
|---|---|---|
| `V1`–`V26` | 核心链路（新车）基线 | 已落库 |
| **`V40`–`V74`** | **二手车 USED 域**：V40–V54 资质/车辆/証書/担保，V55–V64 估值/LTV/拍卖/库龄，V65–V69 监管仓/放车放证/登记委托，V70–V74 集成层 | 已落库 |
| `V75`–`V79` | USED 域预留（D04 §2 留给处置与报表） | 未用 |
| `V32`–`V39` | 有意留的空隙 | 别占 |

**核心侧下一可用号 = `V27`**（V27 出库指令撤销字段、V28 客户支付确认凭证、V29 工单 SLA、
V30 经销商画像快照、V31 `dpd_stage_rules` 这几个用途还没实现；逐号实时台账以 `PROGRESS.md §2` 为准）。
注意 `V27` 这个名字在 legacy 库里已被 `agent/w1-release` 用过一版，新建时先看那份内容是否要沿用。

### 6.3 数据约定

- **金额一律 `BIGINT` 整数**（日元最小单位），禁止 `double`/`float` 参与金额计算。
- 并发更新用 `version` 乐观锁列（`vehicle_units` / `credit_lines` 已有），不要靠 `SELECT ... FOR UPDATE` 兜全局。
- 关键动作写 `audit_events`（操作人从 `AuditActor` 取，不要从请求体里读）。
- 状态变更走状态机方法，**不要直接 `UPDATE ... SET status = 'XXX'`**。
- **`outbox_events.aggregate_key` 标的是「这一次发生」，不是「这个对象」**：用 `advanceId` /
  `paymentRef` 这类每次新建的主键，**禁止用 `vin`、禁止用 `event_type` 单键**。发布端按
  `(event_type, aggregate_key)` 做 `WHERE NOT EXISTS` 去重，键选得太大（vin）会把同车的第二笔
  垫款直接吞掉；选得太小或每次不同（traceId）等于没去重。消费侧的幂等键是 `(event_id, consumer)`，
  和发布端去重是两套东西，不要混为一谈。

---

## 7. 提交纪律

- **提交一律写成 pathspec 形式**：`git commit -m "…" -- <路径1> <路径2>`，不要 `git commit` 裸提。
  `git commit`（不带路径）提交的是整个暂存区，不是你 `git add` 的那几个——本仓库实测过
  一次提交掺进三个工作流的改动。新增文件仍需先 `git add` 自己那几个。
- 提交前 `git status --short` 确认只包含本次工作的文件；发现不属于当前工作的脏文件，
  **不要 stash、不要移走、不要代提交**，原样留着。
- 每完成一个原子步骤**立刻**更新 `PROGRESS.md` 对应行并提交，不要攒着批量更新。
- **需要新迁移**：先在 `PROGRESS.md §2` 登记号段再建文件。同一号建两个文件的历史是
  两个 `V24` 撞号 → Flyway 拒绝启动 → 冒烟全断。

---

## 8. 业务口径红线（已裁决，改动需用户书面确认）

以下不是技术选择，是业务裁决。**代码必须与之一致，不许「顺手优化」回去。**

| 编号 | 裁决 | 落点 |
|---|---|---|
| **Q21** | 无 `REGISTERED` 担保登记时**硬阻断放款**，**不保留**按经销商放宽的白名单 | `RoleSeparationPolicy.evaluateGuarantee`（两参版本） |
| **Q27** | 担保权属唯一权威源 = `collateral_registration_batches`（V10 批次表）；`collateral-reg-service` 是只读投影 | `docs/ADR-Q27-担保权属权威源裁决.md` |
| Q14 | 见 `docs/ADR-业务口径总定源.md` | — |
| ADR-007 | 上游直付与收款账户 | `docs/库存融资核心系统-ADR007-*.md` |
| ADR-008 | 逾期状态归属借据 | `docs/库存融资核心系统-ADR008-*.md` |
| ADR-009 | 外部接入安全边界 | `docs/库存融资核心系统-ADR009-*.md` |
| ADR-010 | 同 VIN 多笔垫款防线 | `docs/库存融资核心系统-ADR010-*.md` |

**USED 域四条红线**（`docs/二手车库存垫资改造-01` 定源，语义上不可配置化）：

1. 没有资金结清事实 → 不生成/不下发放车指令（`RELS_002`）。
2. 没有 partner 权威回执 → 不进 `SETTLED`、不回补额度（人工代录永久只能是 `INTERNAL_WITNESS`）。
3. 車検証质押未成立 → 不放款（`CERT_005`）。
4. 账实不符（系统有记录、仓库快照没有）→ 不出库。

**两个开关不许擅自打开：**

- `floorplan.core.guarantee-gate.enabled` 默认 `false`。Q21 虽是硬阻断，但 Q27 的判定路径改造
  未完全落地前开启会**全量拒付**。要开必须单独提，并说明前置条件已完成。
- 任何「可配置绕过」（白名单、灰度名单、跳过标记）都要问一句：**它会不会把已裁决的硬策略退化成软放行。**
  Q21 当初就是被一个 `relaxedDealers` 白名单绕过的。红线同理：四条红线不写开关。

**仍等业务签字的问题**见 `PROGRESS.md §6`。按 §0 铁律 3 的链路处理：采纳默认值 + 配置外置 + 标 `[待定源]`。

---

## 9. 已知缺口（别把「发现缺口」当成果）

`PROGRESS.md §8` 是**全量实查清单**，分七类：真未开发 / 骨架占位 / 默认关闭 / 零覆盖 / 仅设计稿 /
业务定源 / **文档状态滞后造成的「假未完成」**。动手前先看，避免重复调查或误判。

当前最要命的五处断裂（P0）：

1. **借据的事件字段不全** — `LoanApplicationService.createFromLoanCreated()` 已由 `LoanCreatedConsumer` 调用，
   `loans` 表不再恒空；但 `dueDate`/`gracePeriodDays` 因 Q-EVT3 未签字仍不进事件载荷
   ⟹ 新建借据 `due_date IS NULL`、`repayment_plans` 零行，逾期 DPD 与对账仍转不起来。
2. **事件机制已通两条线，第三条仍未接** — 发布/轮询/游标/消费幂等/死信机制（`JdbcOutboxPublisher` /
   `EventDispatcher` / `@Scheduled` / `event_consumptions`+`consumer_offsets`）之上有两个真实调用方：
   `LOAN_CREATED`（`AdvanceApplicationService.confirmAdvance`，键 `advanceId`）与
   `PAYMENT_MATCHED`（bank `PaymentMatchApplicationService`，键 `paymentRef`；credit-service 消费后行锁回补）。
   `COLLATERAL_CHANGED`（聚合键与 status 取值域待 Q-EVT1）**仍零调用方**。
   回款匹配的**解押已接 core**：`floorplan-core` 消费同一事件，`settled=true` 时释放车辆/担保台账并生成抹消待办。
   **仍未接**：release-service 指令域消费；「银行匹配 vs 直接还款」共用 `settle_seq` 的双重释放防线。
   「调度器和表都在」不等于「闭环已通」，「两根线通了」也不等于「事件驱动层落地了」。
3. **回款匹配后不解押不回补** — `ReleaseOrchestrator` 解押只记日志，`availableAfter = before`。
4. **工单体系 0 Java** — `work_orders` / `work_order_actions` 表齐全，零实体零 Service。
   门禁拒付说「生成 HIGH 工单」，实际只写 `risk_alerts`。
5. **前端 8 个模块 0 页面** — 垫款申请/复核、还款核销、出库指令、对账、借据贷后、违约处置、
   担保登记批次、回款匹配全缺。后端 110+ 端点，前端实际调用约 1/4。

USED 域的三份规格源别绕过自己发明口径：
`docs/二手车库存垫资改造-07-接口文档.md`（端点/权限点/错误码）、`-08-估值与LTV定价设计.md`、
`-09-放车放证闭环设计.md`。

---

## 10. 编码约定

| 项 | 约定 |
|---|---|
| 错误码 | 全部走 `domain/exception/ErrorCode` 枚举，新增码要同步前端提示文案；不要抛裸 `RuntimeException` |
| 权限 | 端点上标 `@RequirePermission(...)` / `@RequireAdmin`；**新服务也必须标**，不要指望网关兜底 |
| 响应包装 | 统一 `ApiResponse`，异常由 `GlobalExceptionHandler` 收口，Controller 里不要 try-catch 成 200 |
| 事务 | `@Transactional` 只在 `application` 层；一个用例一个事务边界 |
| 幂等 | 任何会被重试的写操作（放款、回款匹配、事件消费、对账导入）必须有幂等键 + 唯一约束兜底 |
| 日志 | 关键状态迁移打结构化日志，带业务主键（VIN / 借据号 / 出库单号）；跨服务要能把 traceId 串起来 |
| 时间 | 统一 `TIMESTAMPTZ`，业务日期用 `DATE`；不要在 Java 里 `new Date()` 后直接入库 |
| 命名 | 类/方法英文，注释与提交信息中文；业务术语沿用文档（垫款/让渡担保/解押/出库指令/对账） |
| 依赖 | 新依赖先看父 pom 有没有管理版本；服务内自用依赖写自己服务的 pom |
| 注释 | 生产代码要写「为什么」——尤其是对某个业务裁决或跨包冲突的处置理由（见 `Permission`/`UserRole` 里的行内注释风格） |

**domain 层保持可纯单测**：不注入 `JdbcTemplate`、不读配置文件、不 `new` 外部客户端。
`RoleSeparationPolicy`、`DpdCalculator` 是正面样例。

---

## 11. 文档规矩

- 设计文档放 `docs/`，中文命名，并在 `docs/00-文档索引.md` 登记。
- 删除文档时同步删掉索引与文内引用；**留悬空引用比留旧文档更糟**（下一个人会去找）。
- 不写「文档说已完成但代码零引用」的文档 —— 这个仓库有过 `financing_terms` 表建了、
  文档说落地了、全仓 0 引用的先例。

---

## 12. 反模式清单（本项目真实踩过，逐条都付过代价）

| 反模式 | 实际后果 | 正确做法 |
|---|---|---|
| 建了目录和启动类就算「拆出服务」 | 4 个服务 0 Controller，网关 5 条路由指向空端点 | 拆服务 = 拆能力 + 迁数据 + 通链路，缺一不算 |
| 写了 Feign 契约就算「通了」 | 无 `@EnableFeignClients`、无调用方、契约路径对不上实现 → 死代码 | 契约必须有一个真实调用方 + 一条通过的断言 |
| 网关路由加了就算「接上了」 | `/api/payments/**` 被 core 兜底路由接走 → 404，`web.log` 三条铁证 | 每条路由都要有从网关打进去的端到端断言 |
| 表建了就算「功能有了」 | `work_orders` 零 Java，`financing_terms` 零引用 | 以「有端点 + 有断言」为准，不以建表为准 |
| 用可配置白名单「放宽」安全策略 | Q21 硬阻断被 `relaxedDealers` 绕成软放行 | 硬策略不许留可配置旁路；要留必须写成显式业务裁决 |
| 靠共库跨域直写维持原子性 | 现在能跑，物理拆库那天闭环当场断 | 新代码禁止跨域直写；补偿机制（outbox）补齐前不拆库 |
| 用「编译通过」当验收 | 端点存在但网关不通、字段写错列 | 验收只认冒烟全绿 |
| 分两次命令「先起服务、再跑测试」 | 后台 JVM 随 shell 回收，第二次调用全是连接失败 | 启动 + 测试 + 清理写在同一条命令 |
| 事件键用 `vin` / 每次换新值 | 前者把同车第二笔垫款吞掉，后者等于没去重 | `aggregate_key` = 这一次发生的业务主键（`advanceId`/`paymentRef`） |
| 拿工具回执当落盘证据 | Edit 回了 success、`mvn` 管道返回 0，磁盘上却没有那个文件/那次成功 | 写完当场 `grep`/`ls`/`git status` 现查；退出码要自己 `$?`，别经管道 |

---

## 13. 开工检查单

- [ ] 读过 `PROGRESS.md`，知道当前 HEAD 和断点
- [ ] 需要新迁移 → 号段已在 `PROGRESS.md §2` 登记，且没撞上 USED 的 V40–V79
- [ ] 涉及业务口径 → 查过 §8 红线；未签字的按「采纳默认值 + 配置外置 + 标 `[待定源]`」处理
- [ ] 写完有端到端断言（根目录 `smoke_*.py`，并已进 `run-microservices.sh smoke` 调用链），且能在网关侧打通
- [ ] `mvn -B clean package` 全绿（`$?` 自己取）
- [ ] `PYTHON=python PYTHONIOENCODING=utf-8 bash run-microservices.sh smoke` EXIT 0、FAIL 0
- [ ] 提交信息带验证结论，且用 pathspec；别人的脏文件原样留着
