# AGENT TASK · LOAN（借据 / DPD / 经销商画像服务）

## 你的身份
你是 loan agent，在独立 worktree `C:/Users/Administrator/Documents/GitHub/wt-loan` 工作，分支 `agent/loan`。
所有命令先 `cd` 到该 worktree。中文交流。

## 硬性边界（违反即合并失败）
1. **只写**：`loan-service/` 目录下所有文件 + 迁移文件 `infrastructure/src/main/resources/db/migration/V16__*.sql`（只有 V16 是你的号段）+ 一份契约文档 `docs/接口契约-V16借据.md`（这份是要给 payment agent 看的，例外允许写）。
2. **绝不修改**：父 `pom.xml`、任何 `application.yml`、`common/`、`run-microservices.sh`、其他三个服务目录、`web/`、`domain/`、`application/`、`infrastructure/` 的已有 java 文件、V17/V18/V19/V20/V21 迁移。
3. 需要别的服务能力时写 TODO，不跨目录改代码。
4. 不要执行 `git worktree/branch/merge` 等改仓库结构的命令——主 agent 统一合并。你只写代码 + commit。

## 开工前自检
```
cd C:/Users/Administrator/Documents/GitHub/wt-loan
git branch --show-current        # agent/loan
git log -1 --format='%h %s'      # wave0 那条 aab5295
```

## 背景
汽车库存融资系统拆微服务。你负责 loan-service——这是"算风险"的一层（借据、DPD、经销商贷后画像），区别于 risk-service 的"发现风险"（预警/工单）。

关键区分（面试加分点）：`risk_grade`（授信评级，在 credit）和 `risk_tier`（贷后画像，在你这里）是两套并存指标，不要混。

已有骨架：`loan-service/pom.xml`、`LoanServiceApplication.java`（端口 8085，`@Import` 已含验签 bean）、`application.yml`。已能编译启动。

设计依据（精确路径，就在你的 worktree 的 docs/ 下）：
- `docs/贷后风险分层-借据事件与经销商画像设计.md`
- `docs/库存融资核心系统-ADR008逾期状态归属借据设计.md`
动手前先读这两份，按里面定的五表结构和 DPD 规则实现，不要自己另起炉灶。

## 你的任务（对应 T2-2 + T3-1 + M3/M4）
1. **五张表**（迁移 `V16__loan_and_dpd.sql`）：按 ADR-008 设计稿建 loans / loan_status_history / repayment_plans / overdue_records / dealer_risk_profile。
   - `loans` 必须含这些字段（payment agent 的 VIN 匹配要用，是跨 agent 契约）：`id`、`vin`、`advance_id`、`dealer_id`、`status`、`principal`、`due_date`、`created_at`。
   - 金额一律 BIGINT（整数日元），风格照抄 V15。
2. **领域模型**（`...loan.model`）：Loan / RepaymentPlan / OverdueRecord / DealerRiskProfile + 状态枚举。
3. **DPD 计算**（`...loan.service.DpdCalculator`）：按设计稿的 DPD 分档规则算逾期天数与风险分层，做成纯函数便于单测。
4. **仓储**（`...loan.repository.JdbcLoanRepository`）：用 NamedParameterJdbcTemplate，写法参考 `infrastructure/.../JdbcPaymentTransactionRepository.java`，但类放你自己的包内。
5. **应用服务**（`...loan.service.LoanApplicationService`）：借据生成（放款后由 outbox 事件 `LOAN_CREATED` 触发）、还款计划生成、DPD 批量刷新、画像更新。
6. **Controller**（`...loan.controller.LoanController`）：
   - `GET /api/loans/{id}` 借据详情
   - `GET /api/loans/by-vin/{vin}` 按 VIN 查借据（payment agent 会调这个）
   - `GET /api/loans/{id}/dpd` 查 DPD
   - `GET /api/dealer-profile/risk/{dealerId}` 经销商贷后画像
7. **启动类装配**：在 `LoanServiceApplication` 的 `@Import` 加你的新 bean。
8. **单测**：DpdCalculator 的分档边界必须写参数化单测（0天/1-29/30-59/60-90/90+ 各档边界）。

## 契约冻结（写进 docs/接口契约-V16借据.md，给 payment agent 用）
文档必须明确写出：
- `loans` 表完整 DDL（字段名+类型），尤其是 vin / advance_id / dealer_id / status / due_date
- `GET /api/loans/by-vin/{vin}` 的响应 JSON 结构（字段名）
- VIN→借据的映射规则（一笔垫款对应一个借据，vin 唯一指向未结清借据）
payment agent 会照这份文档编码，所以字段一旦写定不要改。

## 验证（必须自己跑通）
```
cd C:/Users/Administrator/Documents/GitHub/wt-loan
mvn -q -pl loan-service -am test       # 必须通过
mvn -q -pl loan-service -am package    # 必须通过
```
无数据库时明确记录，但编译必须过。

## 交付
1. 每个逻辑块一个 commit：`[LOAN] T2-2 一句话说明`。
2. loan-service 目录写 `HANDOFF.md`：改了哪些文件、五表状态、DPD 规则实现情况、单测结果、TODO、依赖外部接口。
3. 契约文档 `docs/接口契约-V16借据.md` 写完即 commit（payment agent 等着用）。
4. 打 tag：`git tag agent/loan-done`。
5. `git status` 确认干净。

现在开始。先读 `docs/贷后风险分层-借据事件与经销商画像设计.md` 和 `docs/库存融资核心系统-ADR008逾期状态归属借据设计.md`，再动手。
