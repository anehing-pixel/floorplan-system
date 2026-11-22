# AGENT_TASK · USED 多 agent 派发记录

| 项 | 内容 |
|---|---|
| 文档编号 | AGENT-USED-DISPATCH-001 |
| PM | 本会话（收口方兼任） |
| 日期 | 2026-11-22 |
| 基线 | `base-used-design-20261122` = `9136ac8`（引用基线一律取最新 `base-*`，勿写死标签名） |
| 当前轮次 | **审计轮**：只读、零代码、零迁移、不写任何文件。开发轮等 D-01~D-09 / U-01~U-09 签字 |
| 权威源 | 分工 = D12 §3；断言 = D11；号段与表数阶梯 = D04 §2 / §2.1；隔离套件 = `docs/并行开发-W1环境隔离.md` |

---

## 0. 两条 PM 裁决（先于一切）

1. **W-U0 不派 agent，由收口方（PM）担任。** 它的交付物全是共享文件（父 pom、`run-microservices.sh`、`gateway` yml、
   `common/contract`、`PROGRESS.md`）。这些文件一旦由并行 agent 各改一版，就是五次冲突 + 波及在途 NEW 分支。
   **结构性保证**：六包里只有一个人能动全局文件，其余五个的禁区就是那一个的写入集。
2. **W-U3 单人独占，不许拆包。** `integration` 五表（`inbound_messages`/`outbound_tasks`/`nonces`/`call_logs`/`partner_credentials`）
   与角色 `floorplan_dmz` 是**库级对象**，不随分支 schema 复制。并行跑 = 全局队列互踩（同 W0 的 `consumer_offsets` 踩踏同类问题）。

---

## 1. 派发表

| 包 | 角色 / 本轮模型 | 分支 · worktree | schema | 端口偏移 | 表数门槛（空基线→完成后） | 冒烟预算 | 上游 |
|---|---|---|---|---|---|---|---|
| W-U0 地基 | **PM 本人** / 审计=调研员 · Qwen4-Max | `master` 直干 | `public` | 0 | 67→89 | 0（无业务断言） | — |
| W-U1 车辆+资质 | 全栈 · DeepSeek-V4-Pro | `used/wu1` · `../wt-used-wu1` | `used_u1` | +700 | 67→**73** | 28（dealer 12 + vehicle 16） | W-U0 |
| W-U2 估值+拍卖+放款基准 | 全栈 · GLM-5.3 | `used/wu2` · `../wt-used-wu2` | `used_u2` | +800 | 67→**84** | 42（valuation 24 + auction 18） | W-U0，部分等 W-U3 |
| W-U3 DMZ 集成层 | 全栈 · Kimi-K3.1-Pro（**独占**） | `used/wu3` · `../wt-used-wu3` | `used_u3` | +900 | 67→**89** | 20（含 INV-1..5 证伪） | W-U0 |
| W-U4 凭证+WMS+放车放证 | 全栈 ×2 · Step-5-Preview | `used/wu4` · `../wt-used-wu4` | `used_u4` | +1000 | 67→**94** | 56（cert 20 + wms 10 + release 26 含 E2E-0） | W-U1、W-U3、在途 W2-WORKORDER |
| W-U5 处置+通知+报表 | 全栈 + 审查员 · Qwen3.8-Max | `used/wu5` · `../wt-used-wu5` | `used_u5` | +1100 | 67→**96** | 18（disposal 10 + reporting 8） | W-U4、W-U3 |

预算合计 **164** 条（D11 §9.1），其中 E2E-0 落在 W-U4。

## 2. 共同纪律（六包一致，违反 = 合并失败）

1. **一个 agent 一个 worktree + 一个 schema + 一个端口偏移**（U-R13）；偏移 0 只在「当前只有我一个人干活」时用。
2. **`FLOORPLAN_PUBLIC_TABLES` 按上表门槛设**，不许沿用默认 67 蒙过 `check`——少建一张表还跑绿，正是隔离假通过的形态。
   空基线 schema 的门槛就是 67；建完本包的表才升到 73/84/89/94/96。
3. 环境三条实测约束：`currentSchema=<schema>,public` 第二段不能省（V5 的 `gen_salt()` 装在 public）；
   启动一律 `env -u SERVER__PORT SERVER_PORT=<偏移后端口>`；**启动 → 冒烟 → 清理写在同一条命令里**。
4. **验收口径**：并行期在自己 schema 里的绿灯只算局部结论；**并发与幂等类断言（P1–P20、乐观锁、`FOR UPDATE SKIP LOCKED`、
   部分唯一索引、outbox 去重）必须在合并后 `public` 主干全量回归再跑一次才算**（D11 §11 DoD ⑦）。
5. 越界自检（提交前必跑）：`git diff --name-only $(git for-each-ref --sort=-creatordate --format='%(refname:short)' refs/tags/base-* | head -1)..HEAD | grep -v '^docs/'`，逐条对照本包「允许写」。
6. 共享禁区（AGENTS §7.1）：父 `pom.xml`、`run-microservices.sh`、`gateway` yml、`PROGRESS.md`、`smoke-test.py`、`app.js`、V1–V26 迁移、`common/contract`（归 W-U0）。
7. 业务红线不因「本包方便」而松动：Q21 硬阻断无白名单、Q27 V10 批次表为权属唯一权威源、Q11 解押先于发指令、
   ADR-008 逾期归借据、金额一律 BIGINT、状态变更走状态机、`guarantee-gate.enabled` 保持 `false`。

## 3. 审计轮任务（当前这一轮只做这个）

禁止：写文件、改文件、`mvn`、起服务、连库写、建 schema、跑冒烟。
产出 = 问题清单，**作为消息返回给 PM**，由 PM 汇总进 `docs/二手车库存垫资改造-13-多agent审计汇总.md`。
格式 `[WU<n>-<序号>] S0..S4 | 文档坐标（D0x §x） | 问题 | 建议`，≤20 条，宁缺毋滥；查不出问题就写明覆盖了哪些条目。

- **W-U0 审计**：① D12 §2 的 F1–F9 摩擦点与在途 NEW 分支现状是否还成立（必须实测 `ahead/behind`，不许数 worktree）；
  ② D04 §2 号段映射 ↔ D12 §5 登记文本 ↔ `PROGRESS.md` 实登三处一致性；③ 加 `USED_*` 常量会不会破坏 V26 的 payload 约定（必含 `vin`+`traceId`）；
  ④ 15→17 构建单元对 AGENTS §5 硬门槛文案的影响；⑤ D11 §7 挡板矩阵够不够跑完 INV 证伪。
- **W-U1 审计**：① 7 张新表逐张对 D04 列/约束 ↔ D06 R1/R2/R3.1，找出「需求要表达但表里没有列」（`mileage UNKNOWN`、证照有效期、`admission-state` 判定输入）；
  ② `vehicle_type` 双域共表对 LEGACY 建账的冲击面（L4）；③ 与 W-U4 的表所有权边界是否唯一（谁建列、谁写状态）；④ 28 条断言能否从网关打通。
- **W-U2 审计**：① D08 公式 ↔ D04 列 ↔ D06 条目 ↔ D11 A6 数值**四方对表**，逐步验 2,000,000/4.5/1,600,000 → 审慎值 1,600,000 × 0.70 = **1,120,000** 每一步落到哪一列；
  ② 放款基准切换四步（D08 §5.1）会不会留下「实现了但没人调」的窗口；③ 分档唯一命中无兜底的数据表达；
  ④ `AEF:*` 幂等键与 V26 `aggregate_key` 会不会互相吞；⑤ V64 豁免规则表在 D00 §7 无定名（已知缺，确认影响面）。
- **W-U3 审计**：① D02 §5.6 的 INV-1..5 逐条找到 D11 §4 的证伪实验，缺一即 S0；② HMAC 串签原文在 D07 12 个对外端点上是否一致引用（有没有哪个改了字段序）；
  ③ 密钥轮换宽限期与 `X-Key-Version` 的状态表达；④ `inbound_messages` 幂等/防重放与 outbox 去重的边界会不会双吞；
  ⑤ 凡写成「分支 schema 可验」的 INV-1/A5 断言全部标出来（它们只能在合并后主干算）。
- **W-U4 审计（关键路径）**：① D09 §11 八条证据链逐条找「端点 + 事件 + 断言」三个落点（D11 §2.4 是闭合条件表），缺一个报 S0；
  ② 两段式解押（RL-1a/RL-1b）与 Q11「解押先于发指令」的一致性；③ `ReleaseStatus` 10 态与 LEGACY 6 态迁移表会不会互染；
  ④ 回款路径与放车路径共用 `CLR:{vin}:{settle_seq}` 会不会互相顶掉；⑤ `collateral-reg-service` 从只读投影到「转正」对 Q27 权威源裁决的影响。
- **W-U5 审计**：① 冲抵顺序分歧（需求书 §11.1「息→本」vs R8.2「费→息→本」）在本包落在哪一步、谁兜底；
  ② V31 预留（`dpd_stage_rules`/`default_events`/`deduction_failures`）与 USED 处置表会不会重造同一能力；
  ③ D07 §7.9 七个报表端点的数据源是否全部可达、逐日与全量对平能否被证伪；④ 通知「无静默失败」的落库证据在哪张表哪一列。

## 4. 隔离实测记录（2026-11-22，PM 实跑）

静态面：迁移文件硬编码 `public.` 命中 **0** → schema 隔离在结构上成立；PG 16.15，`public` 基准 67 表。

三路并发（模拟 W-U1/W-U2/W-U3 同时在跑）：`agent_env.sh init` 三个 schema → 同一 PG 实例并发起 3 个 web 进程（各自 datasource）：

| 判据 | used_u1 | used_u2 | used_u3 |
|---|---|---|---|
| Flyway | 26 迁移全成功（并发，~13s） | 同 | 同 |
| `check` | 67/67、V25+V26=2/2、`gen_salt`=2 | 同 | 同 |
| `Tomcat started on port` | **8780** | **8880** | **8980** |
| 致命错误行 | 0 | 0 | 0 |

`public` 侧：67 表 / `flyway_schema_history` 26 行 —— **一行未动**；三个 schema 各自持有独立的 26 行历史。

未测与限制：① 六路未实测（本机同时只起了 3 个 JVM，`-Xmx384m`，其余 9 个服务未参与）；
② 全量冒烟 ×N 未跑——按 §2 第 4 条，那类断言本来就不在并行期判定；
③ 测试用 jar 是从主 worktree **拷出去**跑的，避免 JVM 句柄占住 `web/target/*.jar` 挡住别人 `mvn package`（AGENTS 坑 4）；
④ `agent_env.sh init` 只建 schema，迁移由 web 启动时的 Flyway 完成——所以「半成品 schema」的风险真实存在，`check` 必须跑。

## 5. 回填区

| 包 | 审计轮结论 | 开发轮开工条件 |
|---|---|---|
| W-U0…W-U5 | 待 6 份清单回收后写入 D13 | D-02/D-04/D-06/U-01/U-09 签字；号段已登记（`375fb14`） |
