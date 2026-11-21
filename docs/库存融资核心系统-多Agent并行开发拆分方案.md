# 库存融资核心系统 · 多 Agent 并行开发拆分方案

> 生成时间：2026-11-21　｜　配套：《任务进度与演进路线图.md》  
> 目标：把 W0–W5 的任务切成**写入文件互不重叠**的包，供多个 agent 在各自 git worktree 并行开发，合并零冲突。  
> 拆分第一原则：**按写入集互斥切分，而不是按业务模块切分**。同一文件只允许一个 agent 写。



---

## 0. 前置条件：已满足（2026-11-21 更新）

> ⚠️ **本节原写"当前不是 Git 仓库"，已过时。** 实测 `git status` 正常、`git tag` 已有
> `wave0-baseline`，4 个 worktree 已建。以下内容仅作历史留档，**无需再执行**。

~~实测：`git status` → `fatal: not a git repository`，无 `.git` 目录。**worktree 用不了，必须先建库**~~

**当前真实状态**：Git 仓库已建、`baseline` 与 `wave0` 两次提交已完成、`wave0-baseline` tag 已打、
4 个 worktree（wt-recon / wt-loan / wt-collateral / wt-release）已切出。
**实时状态以仓库根 `PROGRESS.md` 为准**，本节不再维护。

---

## 1. 冲突热点分析（为什么不能随手切）

实测文件结构：4 个共享模块 + 5 个全局热点文件。这些若被多个 agent 同时改，合并必冲突：

| 热点                                               | 实测情况                           | 归属                            |
| ------------------------------------------------ | ------------------------------ | ----------------------------- |
| `pom.xml`（父）                                     | 10 个 module 声明，新增服务都要加         | **Wave 0 一次加齐所有 module**      |
| `gateway/.../application.yml`                    | 6 条路由，精确顺序（specific 在前）        | **Wave 0 一次加齐所有路由**           |
| `run-microservices.sh`                           | 端口 8080-8084 + ROUTE\_* + 就绪探测 | **Wave 0 一次加齐所有端口**           |
| `infrastructure/.../db/migration/`               | 现有 V1–V15，新任务需 V16+            | **Wave 0 分配号段，各 agent 只用自己号** |
| `domain`/`application`/`infrastructure`/`common` | 91/26/39/7 个文件，所有服务依赖          | 现有文件**禁止并行改**；新文件按包隔离         |

关键约束：**现有共享层的类（如 `AdvanceApplicationService.java`）是 core agent 的独占写入区**，其他 agent 只读、通过 Feign 调，不许改。这是避免"两个 agent 改同一个 application service"的硬规矩。

---

## 2. Wave 0 · 单 agent 串行（前置，约 1–2 周）

由 1 个 agent 在主干完成，**产出基线后各并行 agent 才能切 worktree**。

| #    | 任务                       | 交付物                                                                                                              |
| ---- | ------------------------ | ---------------------------------------------------------------------------------------------------------------- |
| W0-1 | 父 pom 预留所有新 module       | `loan-service` / `collateral-reg-service` / `release-service` / `recon-service` / `payment-service`（即使暂时空模块也先声明） |
| W0-2 | 网关预留路由                   | 5 条新 `Path=...` → `lb://...`（顺序放在 core 兜底之前）                                                                     |
| W0-3 | 启动脚本预留端口                 | 8085–8089 + `ROUTE_*` + `wait_one` 探测行                                                                           |
| W0-4 | **迁移号段分配表**（最关键）         | V16=loan、V17=payment、V18=collateral-reg、V19=release、V20=recon、V21=eng(outbox)；各 agent 只在自己号段建文件                  |
| W0-5 | `outbox_events` 表 + 投递骨架 | V21 迁移 + `common` 里的 `OutboxPublisher` 接口                                                                        |
| W0-6 | Feign 契约骨架               | `common` 里定义 `CollateralClient` / `CreditLineClient` 接口（只定义，实现归各服务）                                              |
| W0-7 | 空模块可编译                   | 每个新 service 建最小 `XxxServiceApplication` + pom，保证 `mvn package` 全绿                                                |

> Wave 0 完成后打 tag：`git tag -a wave0-baseline -m "并行开发基线"`。所有 worktree 从此处切出。

---

## 3. Wave 1 · 7 个 agent 并行（各自独立 worktree）

每个 agent 收到**相同的任务模板**（见 §5），差别只在"独占写入集"。以下写入集经实测文件结构确认互斥。

### Agent CORE · 核心账务与断点（关键路径，最后合并）

- **worktree**：`../wt-core`，分支 `agent/core`
- **独占写入**：`web/` 全部；`domain`/`application`/`infrastructure` 中**已存在**的账务相关文件；`smoke-test.py`
- **任务**：B1 担保门禁、B1b、B3 处置冻结、B4 盘点、M6 库龄、M8 法律清算、M9 登记编号、M11 支付确认、M12 授信审批（对应任务文件 T1-1~T1-7、T2-4、T2-6、T2-8）
- **只读调用**：`CollateralClient`（权属）、`CreditLineClient`（授信）— 由 W0-6 定义
- **验证**：`bash run-local.sh` + `python3 smoke-test.py` 182 项全绿
- **注意**：唯一可改共享层已有文件的 agent；B1 依赖 Q27 业务定源，未定则先做 B3/B4/B1b

### Agent LOAN · 借据与贷后分层

- **worktree**：`../wt-loan`，分支 `agent/loan`
- **独占写入**：`loan-service/` 全部（新模块）；迁移仅 `V16__*.sql`
- **任务**：M3/M4 借据+还款计划（ADR-008 五表）、DPD 计算、经销商画像（T2-2、T3-1）
- **依赖**：W0 的 module 声明 + V16 号段；借据表结构需与 core agent **事先对齐接口**（见 §5 契约冻结）
- **验证**：`mvn -pl loan-service test` + 该服务独立启动健康检查

### Agent PAYMENT · 回款匹配

- **worktree**：`../wt-payment`，分支 `agent/payment`
- **独占写入**：`bank-service/` 全部；迁移仅 `V17__*.sql`
- **任务**：M5 后半段——流水匹配到 VIN、自动解押、额度回补（T2-3）；采集侧已有代码不许重写
- **依赖**：loan 的借据表（VIN→借据映射）；可先用接口 mock 并行，集成时接真
- **验证**：bank-service 启动 + 构造一条 payment_transaction 跑通匹配

### Agent COLLATERAL · 担保权属登记

- **worktree**：`../wt-collateral`，分支 `agent/collateral`
- **独占写入**：`collateral-reg-service/` 全部（新模块）；迁移仅 `V18__*.sql`
- **任务**：担保权威源服务——登记/顺位/抹消，解决三源不一致（T1-6 技术实现 + T3-4）
- **依赖**：Q27 业务定源（阻塞项）——**未定源前只能建表和服务骨架，不能实现门禁判定**
- **对外**：实现 W0-6 的 `CollateralClient`，供 core 调用

### Agent RELEASE · 出库指令

- **worktree**：`../wt-release`，分支 `agent/release`
- **独占写入**：`release-service/` 全部（新模块）；迁移仅 `V19__*.sql`
- **任务**：B2 出库指令——`release_orders` + 确认/放车/解抵状态机（T2-9）
- **依赖**：设计稿已完成（《放车放证指令设计》）；Q9/Q10 接收方未定不影响先建表
- **验证**：六张表建表 + 状态机单测

### Agent RECON · 对账清算

- **worktree**：`../wt-recon`，分支 `agent/recon`
- **独占写入**：`recon-service/` 全部（新模块）；迁移仅 `V20__*.sql`
- **任务**：8 条对拍规则 + `recon_diffs` 表 + 差异报告（T3-2）
- **依赖**：弱——只读账本表，几乎可立即开工；最适合作为并行度的"试验田"

### Agent ENG · 工程化

- **worktree**：`../wt-eng`，分支 `agent/eng`
- **独占写入**：`.github/workflows/`（或 CI 配置）、`docs/性能与排障指南.md`（新建）、`scripts/` 压测脚本、各服务 `src/test/` 下的**测试基类**
- **任务**：CI 流水线、单测框架搭建、actuator 配置模板、压测脚本、JVM/GC 参数模板（T0-7、T4-3~T4-7 的工具部分）
- **注意**：**不许改任何服务的 pom.xml / application.yml**——这些是 Wave 0 的地盘；配置模板写成文档，由 Wave 2 集成时统一套用

---

## 4. 写入集互斥校验表（合并前必查）

| 文件/目录                      | core | loan | payment | collateral | release | recon | eng |  W0 |
| -------------------------- | :--: | :--: | :-----: | :--------: | :-----: | :---: | :-: | :-: |
| `pom.xml`（父）               |      |      |         |            |         |       |     |  ●  |
| `gateway/application.yml`  |      |      |         |            |         |       |     |  ●  |
| `run-microservices.sh`     |      |      |         |            |         |       |     |  ●  |
| `common/`                  |      |      |         |            |         |       |     |  ●  |
| `web/`                     |   ●  |      |         |            |         |       |     |     |
| 共享层**已有**文件                |   ●  |      |         |            |         |       |     |     |
| `loan-service/`            |      |   ●  |         |            |         |       |     |     |
| `bank-service/`            |      |      |    ●    |            |         |       |     |     |
| `collateral-reg-service/`  |      |      |         |      ●     |         |       |     |     |
| `release-service/`         |      |      |         |            |    ●    |       |     |     |
| `recon-service/`           |      |      |         |            |         |   ●   |     |     |
| 迁移 V16/V17/V18/V19/V20/V21 |      |   ●  |    ●    |      ●     |    ●    |   ●   |  ●  |  ●  |
| `.github/` `scripts/`      |      |      |         |            |         |       |  ●  |     |

**判定规则**：每一列最多一个 ●（迁移号段例外，因为 V16–V21 是不同文件）。任一格出现两个 ●，就是合并冲突点，必须回到 Wave 0 重新分配。

---

## 5. 每个 agent 收到的任务模板（可直接复制）

```markdown
## 你的身份
你是 [AGENT_NAME] agent，在独立 worktree [PATH] 工作，分支 [BRANCH]。

## 硬性边界（违反即合并失败）
1. 只写「独占写入集」内的文件。其余文件一律只读。
2. 绝不修改：父 pom.xml、gateway/application.yml、run-microservices.sh、
   common/、其他 agent 的服务目录、不属于你的迁移编号。
3. 需要别人的能力时，通过 W0 定义的 Feign 接口调用，或写 TODO 留待集成。

## 开工前
git worktree list 确认路径；git log -1 确认在 wave0-baseline 上。

## 任务
[逐条列出该 agent 的任务编号 + 验收标准，引用《任务进度与演进路线图.md》]

## 交付
1. 每个任务一个 commit，信息格式：[AGENT] T1-1 一句话说明
2. 完成后在本 agent 分支打 tag：git tag agent/[name]-done
3. 写一份 HANDOFF.md 到你的服务目录：改了哪些文件、留了哪些 TODO、
   依赖哪些外部接口、本地怎么验证。

## 验证
[该 agent 的验证命令]
```

---

## 6. Worktree 操作手册

```bash
cd C:/Users/Administrator/Documents/GitHub/floorplan-system

# --- Wave 0 完成后 ---
git tag -a wave0-baseline -m "并行开发基线"

# --- 为每个 agent 建 worktree（主仓库执行一次）---
git worktree add ../wt-core      -b agent/core      wave0-baseline
git worktree add ../wt-loan      -b agent/loan      wave0-baseline
git worktree add ../wt-payment   -b agent/payment   wave0-baseline
git worktree add ../wt-collateral -b agent/collateral wave0-baseline
git worktree add ../wt-release   -b agent/release   wave0-baseline
git worktree add ../wt-recon     -b agent/recon     wave0-baseline
git worktree add ../wt-eng       -b agent/eng       wave0-baseline

# --- 各 agent 在自己 worktree 里 ---
cd ../wt-loan
git checkout -b agent/loan   # worktree add 已自动建好
# ... 开发、commit ...
git tag agent/loan-done

# --- 合并顺序（主仓库，core 最后合）---
git merge agent/recon agent/eng agent/release agent/collateral agent/payment agent/loan
git merge agent/core
mvn clean package
bash run-microservices.sh smoke

# --- 清理 ---
git worktree remove ../wt-loan
```

⚠️ Windows 注意：worktree 路径别放在主仓库目录内（用 `../wt-*`），否则 Maven 模块扫描和 `.gitignore` 容易出问题。

---

## 7. 契约冻结（并行开始前必须做的三件事）

这三个不冻结，7 个 agent 各做各的，集成时接口对不上：

1. **表结构契约**：loan 的 `loans` 表必须有 `vin`、`advance_id`、`dealer_id`、`status`、`due_date` 字段——payment 的 VIN 匹配要用。由 loan agent 在 Wave 0 结束后先写一份 `docs/接口契约-V16借据.md`，payment agent 据此编码。
2. **Feign 接口契约**：`CollateralClient.getRegistration(vin)` 返回 `RegistrationVO{status, regNo, registeredAt}`；`CreditLineClient.getAvailable(dealerId)` 返回 `BigDecimal`。Wave 0 已定死，实现方不许改签名。
3. **事件契约**：outbox 事件名统一 `LOAN_CREATED` / `PAYMENT_MATCHED` / `COLLATERAL_CHANGED`，payload 里必带 `vin` + `traceId`。

---

## 8. 已知风险与对策

| 风险                                  | 对策                                                                        |
| ----------------------------------- | ------------------------------------------------------------------------- |
| Q27 未定源，collateral 和 core 的 B1 卡住   | 两 agent 先建表/骨架；core 先做 B3/B4/B1b；定源后再补判定逻辑                                |
| loan 与 payment 的借据表依赖               | loan 先交付表契约文档；payment 用 mock 并行，集成时接真                                     |
| 7 个 worktree 同时 `mvn package` 抢本地仓库 | Maven 本地仓库是读多写少，一般无碍；若锁冲突，各 worktree 加 `-Dmaven.repo.local=<独立路径>`        |
| 合并后全量冒烟失败                           | 按 agent 逐个 `git merge` + 每次跑一次 `mvn -pl <module> test`，定位到具体 agent 再修     |
| 有人违规改了共享文件                          | 合并前用 §4 校验表逐个 diff：`git diff wave0-baseline..agent/xxx --name-only` 对照独占集 |

---

## 9. 推荐启动顺序

不要 7 个一起开。**先开 2 个验证流程**：

1. `recon`（依赖最弱，验证 worktree 流程是否跑得通）
2. `loan`（验证契约冻结是否有效）

这两个合并成功、冒烟全绿后，再一次性开出剩余 5 个。这样最坏情况下只浪费一个 agent 的工作量，而不是 7 个。
