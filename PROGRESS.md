# PROGRESS · 单一事实来源（断点续跑）

> **任何模型/任何人接手，第一件事：读本文件。** 它比任何聊天记录、任务卡、路线图都新。
> 更新规则：**每完成一个原子步骤，立刻改本文件对应行 + commit**，不要攒着批量更新。
> 本文件记录"代码实查状态"，不记录"计划做什么"。计划见路线图，状态见本文件。

最后更新：2026-11-21 23:58（GMT+8） · master HEAD: `9304989`

---

## 0. 三句话现状

1. **Wave 0（地基）已完成**：父 pom 预留模块、网关路由、启动脚本端口、契约接口、V21 outbox、4 个空服务骨架全部落地。
2. **recon agent 已完整交付并合并进 master**：T3-2 对账清算服务，8 条规则（4 实现 + 4 占位），22 个单测通过，HANDOFF 齐全。
3. **loan agent 也已完整交付并合并进 master**：T2-2 借据与贷后分层，五张表 + DPD + 仓储 + 应用服务 + Controller + HANDOFF + tag，编译打包通过。合并时把两份 `AGENT_TASK.md` 冲突拆成 `docs/AGENT_TASK-{recon,loan}.md` 归档。
4. **collateral 已完整交付 + 独立验收通过 + 合并 master**（`9304989`）；**release 已交付，独立 verifier 审查中**——醒来查 release 验收结论，通过后合并。

## PM 监管机制（2026-11-21 新增）

- agent 在团队模式下运行，名字：`release-dev`、`collateral-dev`；PM 身份是 `team-lead`。
- **双向通信已建立**：agent 遇到阻塞/环境问题/完成节点会用 SendMessage 发给 `team-lead`；PM 也可发消息调整任务。
- 但通信**跨会话不保留**——模型切换/新会话醒来后，团队消息通道失效。此时靠本文件 + git 提交记录判断进度：
  `git -C ../wt-XXX log --oneline -3` 看提交、`git tag -l 'agent/*-done'` 看交付、`ls wt-XXX/*/HANDOFF.md` 看交付说明。
- **PM 职责**：派发（写 docs/AGENT_TASK-<name>.md）→ 巡检越界 → 验收编译 → 三方合并 → 更新本文件。agent 不自行合并，也不改根目录文件。

---

## 1. 全局环境事实（别再踩坑）

| 项 | 事实 |
|---|---|
| 仓库根 | `C:/Users/Administrator/Documents/GitHub/floorplan-system` |
| 分支 | `master`（主干）；4 个 agent 分支各自在独立 worktree |
| 数据库 | WSL 容器 `floorplan-db2`，端口 **5434**（5432 被占用），库/用户/密码均 `floorplan` |
| 编译 | Windows JDK 17 + Maven 3.9.16 |
| 启动坑 | 启动时一律 `env -u SERVER__PORT`（否则覆盖各服务端口）；`run-microservices.sh` 已处理 |
| 注册中心 | 本地不启 Nacos，`fail-fast=false`，注册失败仅告警 |
| 进程 | 后台 JVM 随 shell 回收 → 启动+测试+清理必须在同一条命令内 |
| 验证金标准 | `bash run-microservices.sh smoke`，退出码 0 且 182 项断言全绿 |
| 模型约束 | 当前 hy4 flash 限时免费，随时可能受限。**不能热切模型**，靠本文件断点续跑 |

---

## 2. Worktree / 分支断点表（核心）

| Agent | 分支 | worktree 路径 | 状态 | 提交数 | HANDOFF | tag | 是否已并入 master |
|---|---|---|---|---|---|---|---|
| RECON | `agent/recon` | `../wt-recon` | ✅ 完整交付 | 9 | ✅ | `agent/recon-done` | ✅ 已合并 `f052e7c` |
| LOAN | `agent/loan` | `../wt-loan` | ✅ 完整交付 | 9 | ✅ | `agent/loan-done` | ✅ 已合并 `e34d6ac` |
| COLLATERAL | `agent/collateral` | `../wt-collateral` | ✅ 完整交付 + 独立验收通过 | 7 | ✅ | `agent/collateral-done` | ✅ 已合并 `9304989` |
| RELEASE | `agent/release` | `../wt-release` | 🔄 开发中（23:45 派发） | 动态 | ❌ | ❌ | ❌ 未合并 |

> 迁移号段（写入集互斥，绝不越界）：V16=loan · V17=payment · V18=collateral · V19=release · V20=recon · V21=eng(outbox)。
> **硬规矩**：共享层已有 java 文件只有 CORE agent 能改；其余 agent 只写自己的服务目录 + 自己号段的迁移。

---

## 3. 每个断点的"下一步原子动作"

> 每个动作都必须做到"做完 → 更新本文件 → commit → 打 tag → 合并"的完整闭环，避免再次半途中断。

### 3.1 LOAN — ✅ 已完成，无需接手

- 已交付：V16 五表迁移、跨 agent 契约文档、8 个 model 类、`DpdCalculator` + 22 例单测、
  `JdbcLoanRepository`、`LoanApplicationService`、`LoanController`、启动类装配、HANDOFF、tag。
- 已合并 master（`e34d6ac`）。细节见 `loan-service/HANDOFF.md` 和 `docs/AGENT_TASK-loan.md`。
- 后续增强（非断点）：`loan-service` 的真实 PG 集成验证（HANDOFF 记录了未做，环境无数据库）。

### 3.2 COLLATERAL（T1-6 + T3-4 担保权属权威源）

- **阻塞**：依赖 Q27 业务定源。未定源前**只建表和服务骨架，不实现门禁判定**。
- **下一步**：建 V18 迁移（`registrations` 登记/顺位/抹消表）+ `CollateralClient` 实现骨架 + HANDOFF + tag。门禁判定逻辑标注 TODO 等 Q27。
- **恢复起点**：`cd ../wt-collateral && git log --oneline -1` 应为 `05684d0`。

### 3.3 RELEASE（T2-9 出库指令）

- **前置**：设计稿已完成（《放车放证指令设计》），六张表结构已定；Q9/Q10 接收方未定不影响先建表。
- **下一步**：建 V19 迁移（六张表）+ `release_orders` 状态机（确认→放车→解抵）+ 单测 + HANDOFF + tag。
- **恢复起点**：`cd ../wt-release && git log --oneline -1` 应为 `2af1bc9`。

### 3.4 RECON — 已完成，无需接手

- 8 条规则中 R5–R8 是占位，等业务确认口径后再补（见 `recon-service/HANDOFF.md` §4），属后续增强，不是断点。

---

## 4. 合并规则（恢复后必须遵守）

1. **合并顺序**：外围先合、CORE 最后（`recon → eng → release → collateral → loan → core`）。
   已合的 recon、loan 跳过；当前只剩 collateral / release（若之后派了 eng/core 再加进来）。
   注意 master 已因文档提交产生分叉，合并用普通三方合并（`--no-ff`），不再用 `--ff-only`；
   若出现 `AGENT_TASK.md` 的 add/add 冲突，按本次做法拆到 `docs/AGENT_TASK-<name>.md` 归档。
2. 合并前查越界：`git -C ../wt-XXX diff --name-only wave0-baseline..HEAD`，对照第 2 节写入集；出现共享禁区文件就是冲突源。
3. 用 `--ff-only`；若失败说明主干已分叉，停下来人工判断，不要强推。
4. 每合一个，跑一次 `mvn -q -pl <module> -am package`，全绿再合下一个。
5. 全部合并后跑 `bash run-microservices.sh smoke`（182 项断言）。

---

## 5. 恢复流程（换模型 / 新会话醒来照做）

```
1. 读本文件 → 看第 0 节三句话 + 第 2 节断点表，确定自己在哪。
2. cd 仓库根 && git status && git log --oneline -3   # 核对真实状态，别信记忆
3. 找到状态为 ⚠️ 的 agent，按第 3 节"下一步原子动作"接手。
4. 做完一个原子步骤，立刻更新本文件 + commit，再进入下一步。
5. 全部走完 → 按第 4 节合并 → 跑 smoke → 更新本文件为全绿。
```

**关键纪律**：宁可每一步都写文件，也不要"我心里有数"。上下文会被压缩、模型会被切换，
只有落盘的文件是可靠的。本文件就是唯一事实来源。

---

## 6. 已知阻塞项（非技术，等外部确认）

| 编号 | 问题 | 卡住谁 |
|---|---|---|
| Q27 | 担保门禁以哪张表为准？三源如何归一？ | T1-6、T1-5（collateral 门禁判定） |
| Q21 | 无 REGISTERED 批次时硬阻断还是工单+预警？ | T1-5 |
| Q9/Q10 | 出库指令接收方是谁？有无外部 WMS？ | T2-9（不挡建表，挡对接） |
| Q14 | M1/M2/M3 按自然日还是营业日？ | T2-1 |
| R5–R8 | 对账规则口径 | recon 占位规则补全 |

---

## 7. 集成 TODO（代码里留了接缝，合并后必须处理）

| 项 | 问题 | 处理方 | 状态 |
|---|---|---|---|
| **Feign JWT 传播** | `WebMvcConfig` 拦截全部 `/api/**` 且无放行。core 经 Feign 调 `GET /api/collateral-reg/registration` 时若不传播 `Authorization`，会被 401 挡下 → 走 NONE fallback → 拒付。**必须在 core 侧加 Feign 请求拦截器透传 JWT**，否则担保门禁形同虚设 | core agent（未派发） | ⬜ 待处理 |
| **V18 表名改名** | V10 第185行已建 `collateral_registration_items`（batch_id/ledger_id/vin，批次↔车辆明细），任务卡要求的"登记流水/历史"同名会撞。PM 已批准改名为 `collateral_registration_history`，理由写进 V18 迁移注释与 collateral HANDOFF | collateral agent | ✅ 已批准，实现中 |
| **独立验收 agent** | 开发 agent 自测有"自证"盲区。两个 agent 交付后派一个**只读** verifier，验状态机非法迁移 / CollateralClient 契约对齐 / V18 越界。**verifier 不改代码**，问题上报 PM，由 PM 让开发 agent 修——保持写入集互斥 | PM | ⬜ 待两个 agent 交付后派发 |
