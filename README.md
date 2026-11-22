# 库存融资（Floorplan）核心系统 · 可运行原型

一个**可直接启动并跑通业务闭环**的 Java 实现，对应《库存融资核心系统-项目设计文档.md》的结构设计。
定位：**金融公司内部系统 · 新车渠道 · B2B 循环授信**，Rails → Java 等价重写的技术骨架。

现已是 **DDD 四层 + Spring Cloud 微服务**架构：网关统一入口，核心账务留在单体守住事务红线，外围按限界上下文拆成独立服务，走网关的冒烟测试**全绿（当前 200+ 项断言）**。

> 🤖 **在本仓库写代码前先读 [`AGENTS.md`](AGENTS.md)**：环境坑、构建与验收判据、迁移号段、
> 共享文件禁区、已裁决的业务口径红线、反模式清单都在里面。
> **当前进度以 [`PROGRESS.md`](PROGRESS.md) 为准**（本文档的模块与断言数会滞后）。

> ⚠️ 业务参数（结算窗口、库龄递减、垫款比例、利率）均为**预设值**，在 `application.yml` 中集中配置，需向客户核实后替换。

---

## 1. 架构：DDD 四层 + Spring Cloud 微服务

### 1.1 模块结构（Maven 多模块，共 14 个）

```
floorplan-system/
├── domain/           领域层：聚合根、不变量、状态机、计息规则 —— 零框架依赖（所有服务共享）
├── application/      应用层：用例编排 + @Transactional 事务边界（所有服务共享）
├── infrastructure/   基础设施层：JdbcTemplate 持久化、营业日历、Flyway 脚本（所有服务共享）
├── common/           公共层：统一响应、JWT 拦截器、权限注解、全局异常、审计操作人、Feign 契约（10 个类）
├── web/              核心账务服务 :8080（floorplan-core）—— 垫款/还款/计息/台账等有状态写入
├── gateway/          统一网关 :18080 —— 按路径切流 + 透传 X-Request-Id
├── auth-service/     认证服务 :8081 —— 登录 + JWT 签发
├── bank-service/     银行服务 :8082 —— 回款流水采集 + 回款匹配
├── credit-service/   授信服务 :8083 —— 经销商/额度/评级配置/年审
├── risk-service/     风控服务 :8084 —— 预警/盘点/违约处置/担保台账/权属核对
├── loan-service/     借据服务 :8085 —— 借据生命周期、DPD、逾期重算
├── collateral-reg-service/ 权属服务 :8086 —— 担保权属只读投影（权威源为 V10 批次表）
├── release-service/  出库服务 :8087 —— 放车放证指令
└── recon-service/    对账服务 :8088 —— 银企对拍与差异
```

> ⚠️ 后四个服务（loan / collateral-reg / release / recon）在 Wave 0 建了模块与启动类，
> 能力迁移尚未完成，当前业务仍主要走 `web`。详见 `PROGRESS.md §8`。

依赖方向：`web(各服务 controller) → application → domain ← infrastructure`。
**`domain` 不含任何 Spring 注解**——领域层不被框架污染，这一硬约束在拆分后依然成立。

### 1.2 为什么核心账务不拆

垫款、还款、计息、车辆台账这些是**有状态的账本写入**，正确性依赖三样东西：本地事务、`SELECT ... FOR UPDATE` 行锁、数据库唯一约束。它们留在 `web`（服务名 `floorplan-core`）单体里，用一把本地事务兜住。拆成多个服务就要引入分布式事务，对这个场景是倒退。

外围的 auth / credit / bank / risk 是读多写少、边界清晰的上下文，才适合拆出。**服务拆开，但第一版共用一个库**——先划清边界，数据隔离留到后面，避免一上来就处理跨库 join 与分布式一致性。

### 1.3 服务内部：精确装配，而非全包扫描

每个服务的启动类只扫自己的包，再用 `@Import` 按「类」精确注入所需的 ApplicationService 与仓储实现。例如 auth-service：

```java
@SpringBootApplication(scanBasePackages = "com.company.floorplan.auth")
@Import({AuthApplicationService.class, JdbcAuthUserRepository.class,
         GlobalExceptionHandler.class})
@EnableDiscoveryClient
public class AuthServiceApplication { ... }
```

其余十几个 bean 一律不加载。这样 `application` / `infrastructure` 虽是共享 jar，但每个服务只装自己那几个类——ddd 四层被复用，隔离性靠装配实现，不靠复制代码。

### 1.4 网关切流与鉴权

- 网关（WebFlux）按路径前缀路由，**精确规则在前、兜底 `/api/**` 在后**，首个匹配即命中：
  - `/api/vehicles/*/lien-check` → risk（必须抢在 `/api/vehicles/**` 前）
  - `/api/jobs/bank-fetch` → bank（必须抢在 jobs 其他端点前）
  - `/api/dealers|credit-lines|rating-configs|annual-reviews/**` → credit
  - `/api/inventory|alerts|disposals|collateral/**` → risk
  - `/api/auth/**` → auth；其余 `/api/**` → floorplan-core
- URI 写成双模 `${ROUTE_AUTH:lb://auth-service}`：生产走 Nacos 服务发现 + 负载均衡；本地无 Nacos 时用 `ROUTE_*=http://127.0.0.1:<port>` 覆盖成直连。
- JWT 密钥全服务一致（`floorplan.auth.secret`），auth 签发的 token 四个服务都能验。**网关本阶段不验签**，只透传 `X-Request-Id`；鉴权下沉在各服务，服务脱离网关直连也不裸奔（零信任）。

---

## 2. 环境事实（本机实测，重要）

| 项 | 实测结果 |
|---|---|
| 数据库 | 容器 `floorplan-db2`（postgres:16-alpine），端口 **5434**（5432 被 WSL 本机 PostgreSQL 18 占用），库/用户/密码 `floorplan` |
| 编译 | Windows 侧 JDK 17 + Maven 3.9.16 |
| 注册中心 | 本地**不启 Nacos**：各服务 `discovery.fail-fast=false`，注册失败仅告警不影响启动 |
| `SERVER__PORT` 坑 | 本机注入 `SERVER__PORT=51136`，Spring 宽松绑定会解析成 `server.port` 覆盖各服务配置端口 → 启动时一律 `env -u SERVER__PORT`（`run-microservices.sh` 已处理） |
| 进程持久性 | 后台 JVM 随 shell 会话被回收 → 启动 + 测试 + 清理必须放在**同一条命令**内（`smoke` 模式已处理） |
| Flyway | 只有 web 建表，其余服务 `flyway.enabled=false`，避免重复迁移 |

端口分配：web=8080 · auth=8081 · bank=8082 · credit=8083 · risk=8084 · gateway=18080。

---

## 3. 启动步骤

### 3.1 启动数据库（WSL 内）

```bash
wsl -- bash -lc 'docker start floorplan-db2 2>/dev/null || docker run -d --name floorplan-db2 \
  -e POSTGRES_DB=floorplan -e POSTGRES_USER=floorplan -e POSTGRES_PASSWORD=floorplan \
  -p 5434:5432 postgres:16-alpine'
# 必须用 5434 —— 5432 已被 WSL 本机 PostgreSQL 18 占用
```

首次启动自动执行 Flyway 建表脚本（`infrastructure/src/main/resources/db/migration/V1__init.sql`）。

### 3.2 编译（Windows 侧）

```bash
cd floorplan-system
mvn -B -DskipTests clean package
```

产物：各模块 `target/floorplan-*-1.0.0-SNAPSHOT.jar`。

### 3.3 一键跑通（推荐）：启动 6 个服务 + 走网关冒烟 + 自动清理

```bash
bash run-microservices.sh smoke
```

这条命令在一个生命周期内完成：启动 web / auth / bank / credit / risk / gateway 六个服务 → 等待全部就绪 → 让冒烟测试打网关 `18080` → 跑完自动清理所有进程。成功标志：`冒烟退出码: 0`、`全部通过 ✅`。

单独控制：

```bash
bash run-microservices.sh start   # 只启动，保持运行（网关 http://127.0.0.1:18080）
bash run-microservices.sh stop    # 停止全部服务
```

手动走网关跑冒烟（配合 `start` 使用）：

```bash
SMOKE_BASE=http://127.0.0.1:18080 python3 smoke-test.py
```

> `smoke-test.py` 的 `BASE` 默认 `http://localhost:8080`（直连单体），设 `SMOKE_BASE` 可切到网关。

**单体模式**（不启动微服务，只跑 web）：`bash run-local.sh` 或 `bash run-docker.sh`，冒烟仍打 8080。适合只验证核心账务。

> 三个本地启动坑（`SERVER__PORT` 覆盖 / 无 Nacos / 后台进程被回收）已全部封装进 `run-microservices.sh`，无需手动处理。

---

## 4. 已实现的业务闭环（七环节）

| 环节 | 接口 | 实现要点 |
|---|---|---|
| ① 授予额度 | `POST /api/credit-lines` | 核定上限；可用 = 核定 − 占用 − 冻结（credit-service） |
| ② 车商收车·申请 | `POST /api/vehicles/batch-ingest` | 批量建账，**部分成功语义**（一台脏数据不阻塞整批） |
| ③ 垫款支付 | `POST /api/advances` | **原子**：行锁占额度 → 建台账 → 付款；失败整体回滚 |
| ④ 设定担保 | `POST /api/vehicles/{vin}/security` | 逐台；⚠️ 落地路径待客户确认 |
| ⑤ 在库计息 | `POST /api/jobs/interest?bizDate=` | 按日计息、**支持补算**、幂等（唯一约束兜底） |
| ⑥ 零售成交 | `POST /api/vehicles/{vin}/sold` | 售出即还；结算到期日 = 售出日 + 2 个营业日 |
| ⑦ 结清释放 | `POST /api/repayments` | **原子三件事**：核销本金 + 解除担保 + 额度回补 |

②③⑤⑥⑦ 属核心账务，留在 web；① 在 credit-service。拆分后**接口路径全部保持不变**，调用方无感。

**并行与风控能力**：库龄推进、库龄递减计划生成、结算窗口扫描（超期 → 逾期 + 脱信销售告警）、实地盘点、预警查询、违约处置、担保台账管理。

---

## 5. 关键设计落点（对应设计文档）

| 设计约束 | 落地方式 |
|---|---|
| 金额一律整数日元 | 所有金额字段 `long` / `BIGINT`；比率用 `BigDecimal`，落到金额时量化 |
| 额度并发不得超额 | `SELECT ... FOR UPDATE` 行锁（`findByIdForUpdate`），禁止无锁读-改-写 |
| 批处理可重跑 | `interest_ledger(vin,biz_date)`、`batch_runs(job_name,biz_date)` 唯一约束 |
| 计息可追溯 | 每条流水存 `rate_snapshot`；结清时**以流水汇总为准**而非重算 |
| 状态与风险标记分离 | `status`（生命周期） vs `ootSuspected`/`overdueDays`/`riskLevel`（风险标记） |
| 规则版本化 | `aging_plans.rule_version`，规则变更不影响存量计划 |
| 审计留痕 | `audit_events` append-only；关键动作全写入，操作人取自 JWT 身份而非客户端头 |
| 外部变更隔离 | `PaymentGateway` 接口 + `MockPaymentGateway` 实现；换真实通道只替换实现 |
| 幂等 | `Idempotency-Key` 请求头 + 数据库唯一索引双重兜底 |
| 营业日口径 | `holidays` 表 + 默认"周一至周五"兜底，计息/结算按营业日 |
| 认证鉴权 | JWT（auth 签发，全服务同密钥验签）；RBAC 权限点 + 职责分离（SOD） |

---

## 6. 冒烟测试覆盖

`smoke-test.py` 共 **182 项断言**，按业务域分组：

1. **七环节闭环**：建账金额 = 发票 × 比例、额度占用/回补、状态流转、担保解除、幂等重放
2. **流程缺口 P0/P1**（⑧~⑱）：重复融资拦截、库龄递减、厂家回购、部分还款、保险理赔、展期、结清证明、额度年审、结算宽限期/OOT、违约处置闭环、评级动态配置、季度归档
3. **权限与角色矩阵**（⑲~㉒）：401 认证失败 vs 403 授权失败严格区分、职责分离（申请人≠支付人≠银行）、审计操作人不可伪造、经销商 CRUD 护栏、名下车辆聚合视图、一人多岗权限并集、一车一笔垫款（作废→重申请→赎回→二次融资）

走网关执行即验证**跨服务切流 + 统一鉴权 + 真实数据库全链路**。

---

## 7. 已知简化（诚实说明）

为在有限时间内做到"可运行且结构正确"，以下做了简化，**不等于生产方案**：

1. **持久化用 JdbcTemplate 而非 JPA**：原约定"简单实体用 JPA、复杂账务用 SQL"；本原型统一用 JdbcTemplate 减少映射层代码。
2. **支付为模拟实现**：`MockPaymentGateway` 默认成功（`floorplan.payment.simulate-failure=true` 可模拟失败验证整体回滚）。真实通道按外部集成文档替换。
3. **批处理为接口触发**（`POST /api/jobs/*`），未接 Quartz 定时。生产版接 Spring Batch + Quartz。
4. **本地不启 Nacos**：网关用环境变量把 `lb://` 覆盖成 http 直连；生产起 Nacos 后去掉覆盖即可走服务发现。
5. **共享单一数据库**：服务已拆但数据未分库，跨服务一致性暂由共享库 + 本地事务保证。
6. **计息未处理期间本金变化**：部分还款场景的本金分段计息未展开。

---

## 8. 配置参数（`application.yml`）

| 参数 | 预设值 | 含义 |
|---|---|---|
| `floorplan.settle-window-business-days` | 2 | 结算窗口（营业日） |
| `floorplan.advance.default-ratio` | 0.90 | 默认垫款比例 |
| `floorplan.advance.max-ratio` | 1.0 | 垫款比例上限 |
| `floorplan.aging.first-trigger-days` | 90 | 库龄递减首次触发 |
| `floorplan.aging.period-days` | 30 | 递减周期 |
| `floorplan.aging.reduce-ratio` | 0.10 | 每期偿还比例 |
| `floorplan.payment.simulate-failure` | false | 模拟付款失败 |
| `floorplan.auth.secret` | 环境变量注入 | JWT 签名密钥（生产必配，勿用默认值） |

数据库连接：`jdbc:postgresql://localhost:5434/floorplan`（各服务一致）。

---

## 9. 相关文档

完整设计、接口、ADR、面试解析见 `docs/`（入口：`docs/00-文档索引.md`）。
