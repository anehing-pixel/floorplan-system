# AGENT TASK · ENG（工程化：CI / 压测 / 调优模板）

## 你的身份
你是 ENG agent，在独立 worktree `C:/Users/Administrator/Documents/GitHub/wt-eng` 工作，分支 `agent/eng`。所有命令先 `cd` 到该 worktree。中文交流。

## 协作协议（重要，先读）
你在一个团队里运行，有项目经理（PM，即 team-lead）在监管。遇到以下情况，主动用 SendMessage 给 `team-lead` 发消息，不要闷头卡住：
1. CI 配置需要改 pom/application.yml 才能生效——这是明确禁区，上报 PM，不自己改。
2. 环境缺工具（Docker/act/JMeter 等）导致无法验证——记录并上报，不硬装。
3. 每完成一个逻辑块，发一条简短进度：`[ENG] 已完成xxx，验证xxx，下一步xxx`。
PM 也可能主动发消息调整任务，收到后按新指令执行并回复确认。

## 硬性边界（违反即合并失败）—— 特别注意，你的边界和其他 agent 最容易撞
1. **只写**：`.github/workflows/` 下新建 CI 文件；新建 `scripts/` 目录及其中压测脚本；新建 `docs/性能与排障指南.md`；新建 `docs/工程化配置模板.md`（放 application.yml / JVM 参数的**模板文本**，不是真的配置文件）。
2. **绝不修改**：父 pom 或任何 pom.xml、任何 application.yml、`common/`、`gateway/`、`web/`、`domain/`、`application/`、`infrastructure/`、任何 `*-service/` 目录（**包括它们的 `src/test/`**——原本任务卡说"各服务测试基类归你"，但那会和 PAYMENT 抢 `bank-service/src/test/`，PM 已裁决：**你不碰任何服务的 src/test/**，测试基类需求写成文档模板交 Wave2 集成）、任何迁移文件、`PROGRESS.md`、`AGENT_TASK.md`。
3. 配置模板写成文档，**不许真的改任何服务的配置文件**——Wave2 集成时统一套用。
4. 不要执行 `git worktree/branch/merge/reset/commit --amend` 等改仓库结构的命令——PM 统一管合并。你只写代码/文档 + commit。

## 开工前自检
```
cd C:/Users/Administrator/Documents/GitHub/wt-eng
git branch --show-current        # agent/eng
git log -1 --format='%h %s'      # 应为 31024c2（派发时的 master HEAD）
git status --short               # 应为空
```

## 背景
汽车库存融资系统正在微服务化，四个外围服务（loan/collateral/release/recon）已交付合并，core/payment 正在并行开发。你负责工程化底座：让"提交即构建、测试不过不能合并"生效，并给出可复用的压测/调优/排障模板。

**V21 outbox 迁移与 `OutboxPublisher` 接口已在 Wave0 建好**，你不要重复实现，也不要改它们。

jenkins/GitHub Actions 二选一，优先 GitHub Actions（仓库在 GitHub）。现有 `.github/` 目录内容先读，别覆盖已有工作流。

## 你的任务（对应 T0-7、T4-3~T4-7 的工具/模板部分；按序做，每块一 commit）
1. **T0-7 CI 流水线**：`.github/workflows/ci.yml`——checkout → setup JDK17 → `mvn -q -B package`（含单测）→ 静态检查（若引入需改 pom 则**只写文档说明**，不改 pom）。测试不过不允许合并。注意仓库是多模块，编译要覆盖全部模块。
2. **T4-3 JVM 调优模板**：`docs/工程化配置模板.md` 写 G1 + 堆参数 + GC 日志轮转的推荐值（附理由，不写死具体堆大小——留"按压测定容"占位）。
3. **T4-4 慢 SQL 治理模板**：同文档写 `EXPLAIN` 用法、索引补缺清单模板、执行计划归档规范。不实际改 SQL（越界）。
4. **T4-5 全链路压测脚本**：`scripts/` 下写压测脚本（可选 JMeter jmx / k6 / Python  locust，挑一个环境能装的；若都装不了写脚本 + README 说明如何运行）。报告模板含 TPS/RT/P99/容量结论。
5. **T4-6 灰度发布 + 回滚 Runbook**：`docs/性能与排障指南.md` 写一节——灰度步骤、一键回滚命令、演练检查表。
6. **T4-7 灾备 + 备份恢复 Runbook**：同文档写一节——备份策略、恢复步骤、RTO/RPO 验证方法。
7. **排障指南主体**：同文档写常见故障排查路径（服务起不来 / Feign 401 / DB 连接 / 迁移失败 / 队列积压）。

## 验证（必须自己跑通再交付）
- CI YAML 语法自检（能用 `actionlint` 就用，没有就用 Python yaml 库解析验证缩进/语法）。
- 压测脚本至少做语法检查（`python3 -m py_compile` 或对应工具），不需要真的压测。
- 你改的 YAML/MD 不破坏任何现有服务编译：`mvn -q -B -DskipTests package` 全仓库跑一次。
- 环境缺工具导致某项无法实测的，在 HANDOFF 逐项记录原因。

## 交付
1. 每个逻辑块一个 commit，格式 `[ENG] T0-7/T4-x 一句话说明`。
2. `docs/ENGINEERING_HANDOFF.md`：改了什么、CI 门禁是否生效、压测工具选型与验证情况、哪些只是模板需要 Wave2 集成、TODO。
3. 打 tag：`git tag agent/eng-done`。
4. `git status` 确认干净，发消息给 team-lead 报完成。

## 抗中断纪律
模型可能随时切换/会话可能暂停。每完成一个逻辑块立刻 commit，不要攒着。完成后一段话总结：改了什么、验证结果、tag、工作区是否干净。
