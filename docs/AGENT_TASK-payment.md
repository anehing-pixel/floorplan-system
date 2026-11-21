# AGENT TASK · PAYMENT（回款匹配与自动解押）

## 你的身份
你是 PAYMENT agent，在独立 worktree `C:/Users/Administrator/Documents/GitHub/wt-payment` 工作，分支 `agent/payment`。所有命令先 `cd` 到该 worktree。中文交流。

## 协作协议（重要，先读）
你在一个团队里运行，有项目经理（PM，即 team-lead）在监管。遇到以下情况，主动用 SendMessage 给 `team-lead` 发消息，不要闷头卡住：
1. 契约对不上（尤其 loan 借据表的 VIN→借据映射）、依赖拿不到、设计矛盾。
2. 编译/测试环境问题（无 PostgreSQL 等）自己解决不了。
3. 每完成一个逻辑块，发一条简短进度：`[PAYMENT] 已完成xxx，验证xxx，下一步xxx`。
PM 也可能主动发消息调整任务，收到后按新指令执行并回复确认。

## 硬性边界（违反即合并失败）
1. **只写**：`bank-service/` 目录下所有文件 + 迁移文件 `infrastructure/src/main/resources/db/migration/V17__*.sql`（只有 V17 是你的号段）。
2. **绝不修改**：父 `pom.xml`、任何 `application.yml`（含 bank-service 自己的——如要加配置写成 TODO 交 Wave2 集成）、`common/`、`gateway/`、`web/`、`domain/`、`application/`、`infrastructure/` 已有 java、其他服务目录、V16/V18/V19/V20/V21 迁移、`PROGRESS.md`、`AGENT_TASK.md`。
3. **采集侧已有代码不许重写**：`BankFetchController.java`、`BankFetchApplicationService`、V15 表、幂等去重逻辑已完成并落地，你只做**后半段**（流水→VIN 匹配→自动解押→额度回补）。可以在同目录加新类，但不要改这些已有文件的既有职责。
4. 需要别的服务能力写 TODO，不跨目录改代码。借据表映射用接口/mock 解耦，集成时接真。
5. 不要执行 `git worktree/branch/merge/reset/commit --amend` 等改仓库结构的命令——PM 统一管合并。你只写代码 + commit。

## 开工前自检
```
cd C:/Users/Administrator/Documents/GitHub/wt-payment
git branch --show-current        # agent/payment
git log -1 --format='%h %s'      # 应为 31024c2（派发时的 master HEAD）
git status --short               # 应为空
```

## 背景
汽车库存融资系统。银行流水采集侧（`bank-service` + V15 `bank_payment_collection` + 幂等去重）**已完成**——这是你的起点，不要重做。你的任务是回款闭环的后半段：把采集到的流水匹配到具体 VIN 的借据，触发自动解押与额度回补。

loan 的借据表（V16 `loans` + `loan_repayment_schedule`）已由 LOAN agent 交付并合并到 master，表结构见 `infrastructure/src/main/resources/db/migration/V16__loan_and_dpd.sql`，契约见 `docs/AGENT_TASK-loan.md`。你的 VIN→借据匹配要对齐它的 `vin`/`advance_id`/`status` 字段。若发现契约不够用，**上报 PM**，不要跨目录改 loan 的表。

设计依据：`docs/回款闭环与资金监管设计.md`（若存在，先读）。

## 你的任务（对应 T2-3 后半段；按序做，每块一 commit）
1. **V17 迁移**：建回款匹配相关表——匹配记录、解押记录、额度回补流水等。金额 BIGINT、时间 TIMESTAMPTZ，风格照抄 V15，`CREATE TABLE IF NOT EXISTS` 幂等 + COMMENT。
2. **领域模型**：PaymentMatch / LienRelease / CreditRestore + 状态枚举（如 UNMATCHED/MATCHED/RELEASED/RESTORE 等，按设计稿）。
3. **VIN 匹配器**：流水 → VIN → 借据的匹配逻辑，纯函数便于单测。处理：精确匹配、一流水多借据、无法匹配挂起待人工。
4. **解押与额度回补编排**：匹配成功后触发解押 + 额度回补，通过 `CreditLineClient` 等 Feign 接口调用授信侧（只读调用，不改契约）。做成接缝 + TODO，真实调用方未定时用日志实现。
5. **ApplicationService + Controller**：在 bank-service 暴露端点（匹配触发 / 查询匹配结果 / 人工干预）。操作人从令牌解析，不信任请求体。
6. **启动类装配**：新增 bean 显式 @Import（类不加 stereotype，避免与组件扫描重复）。
7. **单测**：VIN 匹配的边界（无法匹配/多借据/重复流水）、幂等性、解押与回补的编排顺序。

## 验证（必须自己跑通再交付）
- `mvn -q -pl bank-service -am test` 必须通过。
- `mvn -q -pl bank-service -am package` 必须通过。
- 无 PostgreSQL 实例时记录原因，但编译与单测必须过。V17 SQL 需在有 PG 的环境验证执行。

## 交付
1. 每个逻辑块一个 commit，格式 `[PAYMENT] T2-3 一句话说明`。
2. `bank-service/HANDOFF.md`：改了什么、V17 表结构、与 loan 借据表的契约对齐点、采集侧未改说明、Feign 接缝在哪、单测结果、TODO。
3. 打 tag：`git tag agent/payment-done`。
4. `git status` 确认干净，发消息给 team-lead 报完成。

## 抗中断纪律
模型可能随时切换/会话可能暂停。每完成一个逻辑块立刻 commit，不要攒着。完成后一段话总结：改了什么、验证结果、tag、工作区是否干净。
