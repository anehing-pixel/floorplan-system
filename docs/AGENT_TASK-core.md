# AGENT TASK · CORE（核心账务与断点修复）

> **⚠️ 状态注记（2026-11-22）**：本任务卡是 Wave 0 时点的派发快照，其中「Q27 未定源 / 不能自己定哪张表为准」的约束**已失效**——ADR-Q27 已裁决 V10 为唯一权威源并完成落地，B1 门禁已实现（生产开关默认关闭）。本卡保留作审计轨迹，实时口径以 `docs/ADR-Q27-担保权属权威源裁决.md` 与 `docs/ADR-业务口径总定源.md` 为准。

## 你的身份
你是 CORE agent，在独立 worktree `C:/Users/Administrator/Documents/GitHub/wt-core` 工作，分支 `agent/core`。所有命令先 `cd` 到该 worktree。中文交流。

## 协作协议（重要，先读）
你在一个团队里运行，有项目经理（PM，即 team-lead）在监管。遇到以下情况，主动用 SendMessage 给 `team-lead` 发消息，不要闷头卡住：
1. Q27 相关业务口径问题（门禁判定源）——这是明确阻塞项，遇到就上报，绝不自己拍板定判定规则。
2. 契约对不上、依赖服务拿不到、设计稿自相矛盾。
3. 编译/测试环境问题（无 PostgreSQL 等）自己解决不了。
4. 每完成一个逻辑块，发一条简短进度：`[CORE] 已完成xxx，验证xxx，下一步xxx`。
PM 也会主动发消息调整任务，收到后按新指令执行并回复确认。

## 硬性边界（违反即合并失败）
1. **只写**：`web/` 全部；`domain/`、`application/`、`infrastructure/` 中**已存在**的 java 文件（含它们的 `src/test/`）；根目录 `smoke-test.py`。
2. **绝不修改**：父 `pom.xml`、任何 `application.yml`、`common/`、`gateway/`、`run-microservices.sh`、任何 `*-service/` 目录、迁移文件（V1–V21 一个都不碰）、`PROGRESS.md`、`AGENT_TASK.md`、其他 agent 的任务卡。
3. **只读调用**：`common/contract/CollateralClient.java`（担保权属）、`CreditLineClient.java`（授信）——通过 Feign 调用，不改契约签名。
4. **B1 依赖 Q27 未定源**：必须做成接缝 + 默认关闭的开关（`floorplan.core.guarantee-gate.enabled:false`）+ TODO(Q27) 标注。默认关闭不参与放款判定，绝不能拍脑袋定判定规则、绝不能默认开启。  ⚠️ **本约束已失效（见文首状态注记）：ADR-Q27 / 《业务口径总定源》已裁决定稿。**
5. 需要别的服务能力写 TODO，不跨目录改代码。
6. 不要执行 `git worktree/branch/merge/reset/commit --amend` 等改仓库结构的命令——PM 统一管合并。你只写代码 + commit。

## 开工前自检
```
cd C:/Users/Administrator/Documents/GitHub/wt-core
git branch --show-current        # agent/core
git log -1 --format='%h %s'      # 应为 31024c2（派发时的 master HEAD）
git status --short               # 应为空
```

## 背景
汽车库存融资核心账务 web 不拆，你负责在现有 web/domain/application/infrastructure 上修复断点与业务缺口。你是**唯一获准改共享层已有文件**的 agent——这是信任，也是责任：改坏会影响所有服务，务必每个逻辑块单独 commit、单独验证。

注意：loan/collateral/release/recon 四个外围服务已交付并合并到 master，你的分支从 master 切出，能看到它们的代码。它们通过 Feign 调你，你通过 `CollateralClient`/`CreditLineClient` 调它们。不要改它们。

## 你的任务（对应 T1-1~T1-7、T2-4、T2-6、T2-8；按序做，每块一 commit）

**业务断点修复（P0）**：
1. **T1-1 B3 处置冻结额度**：处置单创建时冻结对应敞口，可用量同步下降。
2. **T1-2 B4 盘点缺失改状态**：盘点缺失触发 `IN_STOCK → OVERDUE` 或新增 `MISSING`，能进处置流程。
3. **T1-3 M9 登记编号落库**：老接口传入的 `regNo`/`ownerName` 落到 V10 批次表，可查。
4. **T1-4 M12 授信审批链**：建额度改 initiate/complete 双段，未经 complete 不可用于放款。
5. **T1-5 B1 担保门禁**：放款前查该 VIN 是否有 REGISTERED 登记批次，无则硬阻断 + 强制工单。**通过 `CollateralClient` 查询**，按经销商可配放宽。**Q27 未定源 → 做成默认关闭的开关 + TODO(Q27)，不能默认开启、不能自己定哪张表为准**。  ⚠️ **本约束已失效（见文首状态注记）：ADR-Q27 / 《业务口径总定源》已裁决定稿。**
6. **T1-7 B1b 提前还本金误伤**：修复提前还款对担保/额度的错误处理，不误触发担保消灭。

**业务增强（P1/P2，P0 全做完再做）**：
7. **T2-4 M6 库龄递减增强**：分层规则 + 动态重算 + 回款抵扣 + 豁免。
8. **T2-6 M8 法律清算阶段**：状态机补三态 + 撤销通道。
9. **T2-8 M11 终端客户支付确认环节**。

**T1-6（担保门禁的 collateral 侧）由 COLLATERAL agent 已完成**，你不要重复实现权属登记，只做 core 侧的调用与门禁编排。

任务详情与验收标准见 `docs/库存融资核心系统-任务进度与演进路线图.md` 对应 T 编号行；设计依据见 `docs/核查报告*` 与相关设计稿。

## 验证（必须自己跑通再交付）
- `mvn -q -pl web,domain,application,infrastructure -am test` 必须通过。
- `mvn -q -pl web,domain,application,infrastructure -am package` 必须通过。
- `bash run-local.sh` 启服务后 `python3 smoke-test.py`（182 项）——无 PostgreSQL 环境跑不起来时，记录具体原因，并在 HANDOFF 说明哪些修复点做了单测覆盖。编译与单测必须过。
- 新写的 B1/B1b 等修复点要有针对性单测（证明"修复前会失败"的场景现在通过）。

## 交付
1. 每个逻辑块一个 commit，格式 `[CORE] T1-x 一句话说明`。
2. `web/HANDOFF.md`（core 无独立服务目录，手写交付说明到 web/ 下）：改了哪些文件、9 个修复点各做到什么程度、B1 开关与 TODO(Q27) 位置、验证结果、哪些 smoke 项未跑及原因、TODO。  ⚠️ **本约束已失效（见文首状态注记）：ADR-Q27 / 《业务口径总定源》已裁决定稿。**
3. 打 tag：`git tag agent/core-done`。
4. `git status` 确认干净，发消息给 team-lead 报完成。

## 抗中断纪律
模型可能随时切换/会话可能暂停。每完成一个逻辑块立刻 commit，不要攒着。完成后一段话总结：改了什么、验证结果、tag、工作区是否干净。
