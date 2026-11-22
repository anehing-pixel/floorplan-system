# AGENT TASK · RECON（对账清算服务）

> **⚠️ 状态注记（2026-11-22）**：本任务卡「其余 4 条以占位实现列出、规则细节需业务确认」的约束**已失效**——R5–R8 已按《业务口径总定源》ADR 全部落地为真实对拍规则，8 条全 `implemented=true`。本卡保留作审计轨迹。

## 你的身份
你是 recon agent，在独立 worktree `C:/Users/Administrator/Documents/GitHub/wt-recon` 工作，分支 `agent/recon`。
所有命令先 `cd` 到该 worktree。中文交流。

## 硬性边界（违反即合并失败）
1. **只写**：`recon-service/` 目录下所有文件 + 迁移文件 `infrastructure/src/main/resources/db/migration/V20__*.sql`（只有这一个迁移号是你的）。
2. **绝不修改**：父 `pom.xml`、任何 `application.yml`（网关/其他服务）、`common/`、`run-microservices.sh`、其他三个服务目录（loan-service/collateral-reg-service/release-service）、`web/`、`domain/`、`application/`、`infrastructure/` 的已有 java 文件、V16/V17/V18/V19/V21 迁移。这些是别人或 Wave0 的地盘。
3. 需要别的服务的能力时，写 TODO 注释留待集成，不要跨目录改代码。
4. 不要执行 `git worktree` / `git branch` / `git merge` 等改变仓库结构的命令——主 agent 统一管合并。你只负责在自己目录里写代码并 commit。

## 开工前自检
```
cd C:/Users/Administrator/Documents/GitHub/wt-recon
git branch --show-current        # 必须是 agent/recon
git log -1 --format='%h %s'      # 必须是 wave0 那条 aab5295
```

## 背景（这是真实项目，不是玩具）
汽车库存融资系统，正在从模块化单体拆成微服务。你负责 recon-service——对账清算，是 8 个服务里依赖最弱的一个（只读账本表，不写核心账务），所以第一个开工验证并行流程。

已有骨架：`recon-service/pom.xml`、`ReconServiceApplication.java`（`@SpringBootApplication(scanBasePackages="com.company.floorplan.recon")`）、`application.yml`（端口 8088）。这些起点已经能编译能启动，你在上面加业务。

## 你的任务（对应任务文件 T3-2）
在 recon-service 内实现：
1. **领域模型**（放 `com.company.floorplan.recon.model`）：`ReconDiff`（差异记录：id/规则编号/对拍对象/期望值/实际值/差异类型/状态/发现时间）、`ReconRun`（对拍批次：批次号/对拍日期/状态/差异条数/起止时间）。
2. **8 条对拍规则**（放 `...recon.rule`）：读现有账本表做一致性对拍。至少实现这几条能在现有 43 张表上跑的：
   - R1 垫款余额 vs 还款核销累计
   - R2 在库车辆状态 vs 垫款未结清
   - R3 额度占用 vs 在途垫款合计
   - R4 银行流水入账 vs 还款核销（用 payment_transactions 与 repayments）
   其余 4 条以枚举 + 占位实现列出，标注 TODO（规则细节需业务确认）。  ⚠️ **本约束已失效（见文首状态注记）：ADR-Q27 / 《业务口径总定源》已裁决定稿。**
   **重要**：对拍是只读的，绝不修改账本表。
3. **仓储**（放 `...recon.repository`）：`JdbcReconRepository`，用 `NamedParameterJdbcTemplate`（参考 `infrastructure/.../JdbcPaymentTransactionRepository.java` 的写法，但你自己的类放 recon-service 包内，不要改 infrastructure 里已有的类）。负责写 recon_diffs / recon_runs，读账本做对拍。
4. **应用服务**（放 `...recon.service`）：`ReconApplicationService`，编排对拍批次：开跑→逐规则对拍→差异落库→收尾。
5. **Controller**（放 `...recon.controller`）：`ReconController`
   - `POST /api/recon/run` 触发一次对拍，返回批次号 + 差异条数
   - `GET /api/recon/diffs?batchNo=&status=` 查差异明细（带 `@RequirePermission` 注解风格参考现有 controller）
6. **迁移** `V20__reconciliation.sql`：建 `recon_runs` + `recon_diffs` 两张表，含必要索引和 COMMENT。`id BIGSERIAL`、`created_at TIMESTAMPTZ DEFAULT now()`，风格照抄 V15。
7. **启动类装配**：在 `ReconServiceApplication` 的 `@Import` 里加上你新增的 bean（`JdbcReconRepository`、`ReconApplicationService`、`NamedParameterJdbcTemplate` 所需的配置如有）。
8. **单测**：`recon-service/src/test/...` 下至少给 8 条规则的判定逻辑写参数化单测（不需要连库，针对纯函数部分）。

## 验证（必须自己跑通再交付）
```
cd C:/Users/Administrator/Documents/GitHub/wt-recon
mvn -q -pl recon-service -am test       # 必须通过
mvn -q -pl recon-service -am package    # 必须通过
```
如果测试因环境（如无数据库）跑不了，明确记录原因，但编译必须过。

## 契约（不要改）
- 你只消费现有账本表，不产生被别人依赖的接口。
- 事件名如需发布，用 `OutboxPublisher.EVENT_*` 常量（common 里已定义，只读）。

## 交付
1. 每个逻辑块一个 commit，格式：`[RECON] T3-2 一句话说明`。
2. 完成后在 recon-service 目录写 `HANDOFF.md`：改了哪些文件、8 条规则各自状态（实现/占位）、本地验证结果、留了哪些 TODO、依赖哪些外部接口。
3. 完成后打 tag：`git tag agent/recon-done`。
4. 最后 `git status` 确认工作区干净（都已 commit）。

现在开始。先读 `recon-service` 骨架和 `infrastructure/.../JdbcPaymentTransactionRepository.java` 了解 JDBC 写法，再动手。
