# PAYMENT Agent 交接 · 回款匹配与自动解押（T2-3 后半段）

> **2026-11-24 状态更新（以 `PROGRESS.md` 为准）**：本文 §5/§7 的三条主接缝已落地——
> `LoanLookupClient` 换成真实 Feign（`FeignLoanLookupClient` → `LoanQueryClient`）、
> `InMemoryPaymentStore` 换成 `JdbcPaymentStore`（V17 三表）、`PAYMENT_MATCHED` 已在匹配事务内发布。
> 额度回补改为 credit-service 消费事件后行锁真实写入（V80 幂等表）；bank 侧 `ReleaseOrchestrator`
> 只登记 `PENDING` 请求，不再写 `availableAfter = before`。**车辆/担保台账的跨域解押仍未接**，
> release-service 消费属 USED R7。`CreditLineClient`（BigDecimal 死契约）已删除。

> 分支 `agent/payment`，worktree `wt-payment`，起点 e441da1。
> 交付边界：`bank-service/**` + `V17__payment_match_and_release.sql`，其他目录一律未改。

## 1. 改了什么

按逻辑块分 7 次提交（每块一 commit）：

| commit | 内容 |
|---|---|
| `9f9e820` | V17 迁移：匹配记录 / 解押记录 / 额度回补流水三表 |
| `0241d98` | 领域模型：`PaymentMatch` / `LienRelease` / `CreditRestore` + `MatchStatus` / `ReleaseStatus` / `RestoreStatus` |
| `46e0414` | `VinMatcher` 纯函数匹配器 + `LoanLookupClient` 借据查询端口与存根 |
| `9aea666` | `ReleaseOrchestrator` 解押与回补编排 + `PaymentStore` 幂等存储 |
| `d7a1d05` | `PaymentMatchApplicationService` + `PaymentMatchController`（7 个端点） |
| `fb77c1a` | 启动类 `@Import` 装配新增 bean；`bank-service/pom.xml` 加 junit-jupiter |
| `484d71e` | 三套单测（18 项） |

新增端点（`bank-service`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/payments/match` | 批量触发 PAYMENT_MATCH |
| POST | `/api/payments/transactions/{transactionId}/match` | 单笔触发 |
| GET | `/api/payments/matches?status=` | 查询匹配结论 |
| GET | `/api/payments/unmatched` | 挂起待人工清单 |
| POST | `/api/payments/transactions/{transactionId}/manual-match` | 人工绑定 VIN |
| POST | `/api/payments/transactions/{transactionId}/ignore` | 判定非本业务 |
| GET | `/api/payments/matches/{matchId}/release` | 查解押与回补凭证 |

写端点标 `@RequirePermission(Permission.REPAYMENT_EXECUTE)`；读端点遵循全项目口径，对所有已登录用户开放。

## 2. V17 表结构

文件：`infrastructure/src/main/resources/db/migration/V17__payment_match_and_release.sql`（唯一号段，未碰 V15/V16/V18+）。

| 表 | 用途 | 幂等锚点 |
|---|---|---|
| `payment_matches` | 流水 → VIN → 借据的匹配结论 | `uk_payment_match_txn (account_ref, transaction_id)` |
| `lien_releases` | 解押动作留痕（含抹消待办状态） | `uk_lien_release_match (match_id)` |
| `credit_restores` | 额度回补流水（含回补前后快照） | `uk_credit_restore_match (match_id)` |

口径：金额一律 `BIGINT`（整数日元），业务时间一律 `TIMESTAMPTZ`，全部 `CREATE TABLE IF NOT EXISTS` + `CREATE INDEX IF NOT EXISTS` + `COMMENT`，风格照抄 V15。

- `payment_matches.match_status` 取值与 V15 `payment_transactions.match_status` 一致：`UNMATCHED / AUTO_MATCHED / MANUAL_MATCHED / IGNORED / SPLIT`。`UNMATCHED` 是"挂起待人工"，不是失败。
- `lien_releases.status`：`PENDING / RELEASED / FAILED`；`cancellation_status`：`NOT_REQUIRED / REQUESTED / DONE`。**RELEASED 只表示担保实体权利消灭**，抹消登记需双方共同申请，系统只能置 `REQUESTED`。
- `credit_restores.status`：`PENDING / RESTORED / FAILED`；`available_before` 是 `CreditLineClient` 只读查询快照，`idempotency_key` 通常为 `transaction_id`。

## 3. 与 loan 借据表契约的对齐点

依据 `docs/接口契约-V16借据.md`（冻结）与 `V16__loan_and_dpd.sql`：

1. `LoanSnapshot` 是自有的 V16 投影 DTO，**字段名与冻结契约 §2.3 完全一致**（`advanceId` / `dealerId` / `principal` / `dueDate` / `status` / `principalOutstanding` 等），不引用 loan-service 的类，loan 内部重构不穿透。
2. VIN → 借据映射：查询限定 `status ∈ {DRAFT, ACTIVE, OVERDUE, SETTLING}`（与 `idx_loans_vin_active` 一致）；同 VIN 多笔未结清（脏数据）按 `id DESC` 取最新一笔并留痕告警，不抛错。
3. 契约 404 明确当"该 VIN 无在贷债务"处理，走挂起/忽略分支，**不当系统错误重试**。
4. 容忍 `advanceId` / `dueDate` / `currentStageCode` 为 null（契约声明 `non_null` 序列化，JSON 键可能缺失）。
5. 匹配结论里的 `loanId`=`loans.id`、`advanceId`=`loans.advance_id`、`dealerId`=`loans.dealer_id` 直接落 V17，供后续核销与回补归属使用。
6. 未改 V16 任何字段、未跨目录改 loan 代码；契约若不够用已在本文件 TODO 中列出，未私自扩展。

## 4. 采集侧未改说明

- `BankFetchController.java`、`BankFetchApplicationService.java`、V15 `payment_transactions` / `bank_fetch_batches`、幂等去重逻辑（`uk_payment_txn` + `ON CONFLICT DO NOTHING`）**一行未动**。
- 新增代码只通过 `PaymentTransactionRepository.findUnmatched()` **只读**取流水；匹配结论写 V17 三表，**不提供流水表 update 通道**，以维持 V15 "流水是事实、不可事后修改"的纪律。
- 因此采集侧 `match_status` 仍停留在 `UNMATCHED`；V17 是匹配结果的权威读源。流水状态回写列为 TODO（需跨 agent 协调，见 §7）。

## 5. Feign 接缝在哪

| 接缝 | 当前实现 | 真实接线 TODO |
|---|---|---|
| `LoanLookupClient`（bank-service 自有端口） | `LoggingLoanLookupClient`：只 warn + 返回 `Optional.empty()`（宁可挂起，绝不凭空造债务） | 换 `@FeignClient(name="loan-service")` 实现 `GET /api/loans/by-vin/{vin}`；404 → empty，其余异常照抛 |
| `CreditLineClient`（common 冻结契约，只读 `getAvailable`） | `LoggingCreditLineClient`：warn + 降级返回 0 | Wave2 启用 `@EnableFeignClients` 后换 Feign bean；降级语义保持 fail-closed |
| 解押写入 | `ReleaseOrchestrator` 内日志 + `LienRelease` 留痕 | `common.CollateralClient` 只有只读登记查询、**无解押写入契约**，不能越过冻结契约自造；需 loan/collateral agent 提供写入端点后再接 |
| 额度写入 | 同上，只留 `CreditRestore` 凭证 | 契约 Javadoc 明确额度写入在授信侧行锁事务内，本服务只做只读快照，不跨服务写额度 |

`BankServiceApplication` 通过 `@Import` 精确装配 5 个新 bean；新类均**不加 stereotype**，避免被本包组件扫描重复注册（Controller 按框架要求保留 `@RestController`，由扫描发现）。

## 6. 单测结果

| 命令 | 结果 |
|---|---|
| `mvn -q -pl bank-service -am test` | 通过，18 项测试，0 failures / 0 errors |
| `mvn -q -pl bank-service -am package` | 通过（含 repackage） |

测试分布：

- `VinMatcherTest` 10 项：VIN 精确命中 95 自动绑定、一笔流水两个 VIN 歧义挂起、同 VIN 多笔取 id 最大、无候选挂起、金额单独 60 分不自动绑定、冲正/已结清过滤、纯函数幂等。
- `InMemoryPaymentStoreTest` 4 项：三类唯一键的插入或忽略、不同账户同流水号不误合并。
- `ReleaseOrchestratorTest` 4 项：解押先于授信查询先于回补的 steps 顺序、重复触发跳过且不再查授信、未匹配无副作用、授信失败留 `FAILED` 凭证。

**PostgreSQL**：本 worktree 无可用 PG 实例，`V17` SQL 未在真实库执行验证；编译与单测均已通过。需在有 PG 的集成环境跑 Flyway 迁移确认 DDL（尤其 `IF NOT EXISTS` + `COMMENT` 语法与唯一约束名）。

## 7. TODO（按优先级）

1. ~~Wave2 接真实 Feign：`LoanLookupClient` → loan-service~~ ✅ 2026-11-24（`FeignLoanLookupClient`）；
   `CreditLineClient` 已随 Q-EVT2 删除（回补不再从 bank 查额度）；解押写入端点仍缺（collateral/loan 契约未提供）。
2. ~~`InMemoryPaymentStore` → `JdbcPaymentStore`~~ ✅ 2026-11-24（V17 三表，`ON CONFLICT DO NOTHING`）。
3. 流水 `match_status` 回写：需与采集侧 agent 协调增加受控 update（或改由 outbox 消费端写），当前 V17 是权威读源。
4. 权限点：设计稿建议新增 `PAYMENT_MATCH_EXECUTE`，因不能改 `domain.Permission`，当前沿用 `REPAYMENT_EXECUTE`；请 PM 决定是否升权限矩阵。
5. ~~`OutboxPublisher.EVENT_PAYMENT_MATCHED` 无实现 bean~~ ✅ 2026-11-24（`PaymentMatchedPublisher`；
   与匹配结论同本地事务，聚合键 `paymentRef`）。
6. 解押与回补的跨服务一致性：2026-11-24 起回补由 credit-service 消费事件后行锁完成（V80 幂等），
   bank 侧只登记请求。**解押仍只到 V17 留痕**；车辆/担保台账释放与「直接还款 vs 银行匹配」的
   双重释放防线（共用 `settle_seq`）仍未定源。
7. 设计稿五级匹配规则当前只实现 VIN(95) + 金额(60/45)；订单号(90)、时间窗(10)、付款人(20) 信号待补。
8. 一流水多借据的人工拆分（`payment_allocations`，设计稿 §3.5）与 8 类预警/工单联动未实现。
9. `common/config/application.yml` 相关配置（Feign 超时、outbox 投递频率）未改，按边界交 Wave2 集成。

## 8. 自检结论

- 只写 `bank-service/**` 与 V17 迁移；父 pom、任何 application.yml、common/gateway/web/domain/application/infrastructure 已有 java、其他迁移、`PROGRESS.md`、`AGENT_TASK.md` 均未改。
- 采集侧既有文件零改动；借据映射通过接口/mock 解耦。
- 每逻辑块一 commit，工作区干净，tag `agent/payment-done`。
