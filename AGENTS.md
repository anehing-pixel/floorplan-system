# AGENTS.md

> 在本仓库工作的 AI agent 操作手册，人类协作者同样适用。
>
> **分工**：本文件只讲「怎么在这个仓库里干活」；「做到哪一步了」看 `PROGRESS.md`；
> 「为什么这么设计」看 `docs/`。三者不互相替代。

---

## 0. 三条铁律

1. **先读 `PROGRESS.md` 再动手。** 它比任何聊天记录、任务卡、路线图都新，是本仓库唯一事实来源。
2. **验证判据是「冒烟全绿」，不是「编译通过」。** `mvn package` 成功只说明语法对；
   这个项目历史上多次出现「编译过了、端点存在、网关 404」的情况。
3. **不猜业务口径。** `PROGRESS.md §6` 列的是等业务方签字的问题，签字前不许按自己的理解写代码。
   写不了就如实标注阻塞，不要用「先按默认值实现」蒙过去。

---

## 1. 项目定位

日本汽车经销商**库存融资（floorplan）核心系统**：经销商以在库车辆做让渡担保，从资金方取得垫款，
车辆售出后回款解押。原为 Rails 单体，正在重写为 Java 17 + Spring Boot 3.2.5 并做微服务化。

**当前真实状态**：后端主干功能可运行、14 个 Maven 模块全绿、冒烟 200+ 断言通过；
但**事件驱动层未落地**（outbox 只有表没有实现、借据没有生产者、工单表没有 Java 类），
所以 11 个业务环节里所有跨服务的衔接仍靠人工搬数据。不要假设「表建了就是功能有了」。

`ai-service/` 是 Python deep-agent 模板骨架，**不在本期范围**，不要改它。

---

## 2. 开工前阅读顺序

| 顺序 | 文件 | 读它是为了 |
|---|---|---|
| 1 | `PROGRESS.md` | 当前断点、阻塞项、未开发需求全量清单（§8）、踩坑记录 |
| 2 | `docs/库存融资核心系统-代码导读.md` | 代码结构导航 |
| 3 | `docs/库存融资核心系统-十一环节闭环核查报告.md` | 业务闭环基准，断点 B1–B4 / M1–M12 的定义 |
| 4 | `docs/库存融资核心系统-目标微服务架构设计.md` | 服务边界与职责划分 |
| 5 | `docs/AGENT_TASK-<你的名字>.md` | 你的任务卡与写入集 |

`docs/00-文档索引.md` 有全量文档目录，按需查。

---

## 3. 技术栈与模块地图

**技术栈**：Java 17 · Spring Boot 3.2.5 · Spring Cloud Gateway · Spring Cloud Alibaba Nacos（本地不启）·
PostgreSQL 16（WSL/Docker 容器）· Flyway · 原生 JDBC（`JdbcTemplate`，**没有用 JPA/MyBatis**）· JUnit 5 + Mockito。

### 3.1 分层模块（依赖方向严格自上而下）

```
web (core 单体, 端口 8080)  ──┐
gateway (18080)              │
auth/bank/credit/risk/       ├──► application ──► domain
loan/collateral-reg/         │         │            ▲
release/recon (各自独立进程)  ──┘         └──► infrastructure ──┘
                                     common（横切：鉴权、异常、契约）
```

| 模块 | 职责 | 注意 |
|---|---|---|
| `domain` | 实体、状态机、纯业务策略（如 `RoleSeparationPolicy`、`DpdCalculator`）、`ErrorCode` | **不依赖 Spring 容器**，保持可纯单测 |
| `application` | 用例编排、事务边界、跨聚合协调 | `@Transactional` 只出现在这一层 |
| `infrastructure` | 仓储实现（JDBC）、Flyway 迁移、外部适配 | 迁移文件全在这里 |
| `common` | `JwtAuthInterceptor`、`@RequirePermission`、`ApiResponse`、`GlobalExceptionHandler`、Feign 契约 | 跨服务契约放这里，别在服务里各写一份 |
| `web` | core 单体，仍承载绝大多数业务端点 | 拆分尚未完成，新端点优先放对应服务 |
| `gateway` | 统一入口、路径路由、`RequestIdFilter` | 路由文件只有 W3 能改 |
| 9 个 `*-service` | 独立进程，各自 `application.yml` + 启动类 | 见下方端口表 |

**跨服务通信规则**：优先 Feign 契约（`common/contract/`）或事件；
**禁止新代码直接读别的服务的表**——现在共库让这件事「能跑」，但物理拆库时它会立刻失效。

---

## 4. 本地环境事实（照抄，不要自己发明）

| 项 | 值 |
|---|---|
| 仓库根 | `C:/Users/Administrator/Documents/GitHub/floorplan-system` |
| 主分支 | `master` |
| 数据库 | `jdbc:postgresql://localhost:5434/floorplan`（**5434**，5432 被占用；库/用户/密码均 `floorplan`） |
| 编译 | Windows JDK 17 + Maven 3.9.16 |
| 注册中心 | 本地**不启 Nacos**，各服务 `fail-fast=false`，注册失败只告警 |

### 端口分配

| 服务 | 端口 | 服务 | 端口 |
|---|---|---|---|
| web (core) | 8080 | loan | 8085 |
| auth | 8081 | collateral-reg | 8086 |
| bank | 8082 | release | 8087 |
| credit | 8083 | recon | 8088 |
| risk | 8084 | gateway | **18080**（统一入口） |

### 四个必须知道的坑

1. **`SERVER__PORT` 污染**：宿主注入的环境变量会被 Spring 宽松绑定成 `server.port`，覆盖 yml 端口。
   命令行启动一律 `env -u SERVER__PORT java -jar ...`（`run-microservices.sh` 已处理）。
2. **后台 JVM 会随 shell 回收**：Git Bash 下用 `&` 起的进程在父 shell 退出时被杀。
   所以「启动 → 跑测试 → 清理」必须在**同一条命令**内完成，不要分两次调用。
3. **只有 `web` 跑 Flyway**：其余服务 `flyway.enabled=false`，避免并发迁移。新增迁移只放
   `infrastructure/src/main/resources/db/migration/`。
4. **`mvn clean` 可能被文件锁挡住**：并行构建或运行中的服务会占用 `target/*.jar`。
   遇到 `Failed to delete` 先 `jps -l` 看有没有别的 Maven/Java 进程，改用 `mvn -B package`（不带 clean）。

---

## 5. 构建与验证（硬门槛）

```bash
# 1) 全量编译 + 全量单测（15 个 build unit 必须全 SUCCESS）
mvn -B clean package

# 2) 单模块快速验证（改 domain/application 时用这个，快）
mvn -B -pl domain,application -am test

# 3) 起全部服务走网关跑冒烟，自动清理（一条命令内完成）
bash run-microservices.sh smoke

# 其他
bash run-microservices.sh start   # 前台保活，Ctrl+C 停
bash run-microservices.sh stop    # 另开窗口停
bash run-local.sh                 # 只起 web 单体
```

### 交付判据

> `bash run-microservices.sh smoke` **EXIT 0，且 PASS 数 = 断言总数、FAIL = 0**。

- 断言数会随开发增长（历史值 182 / 204），**不要把数字写死在文档或脚本里**，看 FAIL 就行。
- 只跑 `-DskipTests` 的编译**不算验证**，不能作为「已完成」的依据。
- 改了某个服务却没在冒烟里加对应断言 → 视为未完成。各 agent 写 `scripts/smoke_<agent>.py`，
  由收口方合并进 `smoke-test.py`。

---

## 6. 数据与迁移规则

### 6.1 已落库迁移永不修改

`V1`–`V25` **全员禁改**（Flyway checksum 会变，启动直接失败）。要改结构就新增一版。
同理：不要建空迁移文件——Flyway 会把它当成一次真实迁移执行。

### 6.2 号段预分配

| 号段 | 用途 | 归属 |
|---|---|---|
| `V26` | 消费幂等 `event_consumptions` + 游标 `consumer_offsets` | W0 |
| `V27` | 出库指令撤销字段（现有列够用则不建） | W1-RELEASE |
| `V28` | 客户支付确认凭证表 | W2-CORE |
| `V29` | 工单 SLA / 升级字段（够用则不建） | W2-WORKORDER |
| `V30` | 经销商画像每日快照 | 预留 |
| `V31` | `dpd_stage_rules` / `default_events` / `deduction_failures` | 预留 |

**开新分支前必须先在 `PROGRESS.md` 登记号段。** 教训：两个并行工作流各建了一个 `V24__*.sql`，
Flyway 直接拒绝启动，冒烟全断。

### 6.3 数据约定

- **金额一律 `BIGINT` 整数**（日元最小单位），禁止 `double`/`float` 参与金额计算。
- 并发更新用 `version` 乐观锁列（`vehicle_units` / `credit_lines` 已有），不要靠 `SELECT ... FOR UPDATE` 兜全局。
- 关键动作写 `audit_events`（操作人从 `AuditActor` 取，不要从请求体里读）。
- 状态变更走状态机方法，**不要直接 `UPDATE ... SET status = 'XXX'`**。

---

## 7. 并发开发规则

多 agent 并行时，冲突成本远高于开发成本。违反写入集 = 合并失败。

### 7.1 共享禁区（任何 agent 不得修改）

| 文件 | 为什么 |
|---|---|
| `PROGRESS.md` | 5 个分支各写一版必然冲突，收口方统一更新 |
| `smoke-test.py` | 同上，各 agent 只写 `scripts/smoke_<agent>.py` |
| `web/src/main/resources/static/js/app.js` | 导航表是单文件，多人改必冲突 |
| 父 `pom.xml` | 需要新依赖写进自己服务的 pom |
| `gateway/src/main/resources/application.yml` | 新增内部端点不经过网关，不需要加路由 |
| `run-microservices.sh` | 由地基任务一次性注入 `CLIENT_*` 直连地址后冻结 |
| 任何 `V1`–`V25` 迁移 | checksum |

### 7.2 分支纪律

- 每个 agent 一个 worktree/分支，**禁止自己 `git merge` / `git rebase` 到 master**，由收口方统一合并。
- 合并前查越界：`git diff --name-only <baseline>..HEAD`，对照任务卡写入集。
- 提交前 `git status --short` 确认没有把别人的 WIP 卷进来——这个项目踩过，一次提交掺进三个工作流的改动。

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

**两个开关不许擅自打开：**

- `floorplan.core.guarantee-gate.enabled` 默认 `false`。Q21 虽是硬阻断，但 Q27 的判定路径改造
  未完全落地前开启会**全量拒付**。要开必须单独提，并说明前置条件已完成。
- 任何「可配置绕过」（白名单、灰度名单、跳过标记）都要问一句：**它会不会把已裁决的硬策略退化成软放行。**
  Q21 当初就是被一个 `relaxedDealers` 白名单绕过的。

**仍等业务签字的问题**见 `PROGRESS.md §6`。这些做不了就是做不了，标注阻塞，不要自造口径。

---

## 9. 已知缺口（别把「发现缺口」当成果）

`PROGRESS.md §8` 是**全量实查清单**，分七类：真未开发 / 骨架占位 / 默认关闭 / 零覆盖 / 仅设计稿 /
业务定源 / **文档状态滞后造成的「假未完成」**。动手前先看，避免重复调查或误判。

当前最要命的五处断裂（P0）：

1. **借据没有生产者** — `LoanApplicationService.createFromLoanCreated()` 全仓无调用方；`loans` 表恒空，
   DPD、画像、逾期、对账四条线一起空转。
2. **事件机制 0 实现** — `outbox_events` 表 + `OutboxPublisher` 接口都在，无实现类、无 `@Scheduled`、
   无消费者幂等、无死信。所有跨服务衔接都卡在这里。
3. **回款匹配后不解押不回补** — `ReleaseOrchestrator` 解押只记日志，`availableAfter = before`。
4. **工单体系 0 Java** — `work_orders` / `work_order_actions` 表齐全，零实体零 Service。
   门禁拒付说「生成 HIGH 工单」，实际只写 `risk_alerts`。
5. **前端 8 个模块 0 页面** — 垫款申请/复核、还款核销、出库指令、对账、借据贷后、违约处置、
   担保登记批次、回款匹配全缺。后端 110+ 端点，前端实际调用约 1/4。

并行拆分方案见 `docs/并行开发-剩余需求拆分方案.md`（四波：W0 地基 → W1 五服务 → W2 共享层+前端 → W3 收口）。

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

**domain 层保持可纯单测**：不注入 `JdbcTemplate`、不读配置文件、不 `new` 外部客户端。
`RoleSeparationPolicy`、`DpdCalculator` 是正面样例。

---

## 11. 提交与文档

### 提交信息

中文，第一行说清「做了什么裁决/修复」，正文写改动点 + **验证结论**。示例：

```
Q21 定源：采用 A 硬阻断，移除经销商白名单绕过

RoleSeparationPolicy.evaluateGuarantee 改两参并删除 dealerRelaxed 放行分支；
AdvanceApplicationService 删除 relaxedDealers 配置与 isRelaxedDealer()；
GuaranteeGatePolicyTest 补 noDealerBypass / unknownStatus，共 7 项。

验证：mvn -B clean package 15/15 SUCCESS；smoke EXIT 0 全绿。
```

### 文档规矩

- 每完成一个原子步骤，**立刻**更新 `PROGRESS.md` 对应行并提交，不要攒着批量更新。
- 任务卡写到 `docs/AGENT_TASK-<name>.md`，**不要写根目录 `AGENT_TASK.md`**（历史冲突源）。
- 设计文档放 `docs/`，中文命名，并在 `docs/00-文档索引.md` 登记。
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
| 并行各建一个同号迁移 | 两个 `V24` → Flyway 拒绝启动，冒烟全断 | 号段先登记再开分支 |
| 用可配置白名单「放宽」安全策略 | Q21 硬阻断被 `relaxedDealers` 绕成软放行 | 硬策略不许留可配置旁路；要留必须写成显式业务裁决 |
| 靠共库跨域直写维持原子性 | 现在能跑，物理拆库那天闭环当场断 | 新代码禁止跨域直写；补偿机制（outbox）补齐前不拆库 |
| 用「编译通过」当验收 | 端点存在但网关不通、字段写错列 | 验收只认冒烟全绿 |
| 分两次命令「先起服务、再跑测试」 | 后台 JVM 随 shell 回收，第二次调用全是连接失败 | 启动 + 测试 + 清理写在同一条命令 |

---

## 13. 给新 agent 的开工检查单

- [ ] 读过 `PROGRESS.md`，知道当前 HEAD 和断点
- [ ] 确认自己的写入集，没碰共享禁区
- [ ] 需要新迁移 → 号段已在 `PROGRESS.md` 登记
- [ ] 涉及业务口径 → 查过第 8 节红线，没签字的不动手
- [ ] 写完有 `scripts/smoke_<agent>.py` 断言，且能在网关侧打通
- [ ] `mvn -B package` 全绿
- [ ] `bash run-microservices.sh smoke` EXIT 0、FAIL 0
- [ ] 提交信息带验证结论，`git status` 干净
