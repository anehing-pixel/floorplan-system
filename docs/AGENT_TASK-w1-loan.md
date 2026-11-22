# AGENT TASK · W1-LOAN（借据生成消费端）

## 你的身份

你是 **W1-LOAN** agent，worktree `C:/Users/Administrator/Documents/GitHub/wt-w1-loan`，分支 `agent/w1-loan`，
基线 `wave0b-baseline`。所有命令先 `cd` 到该 worktree。中文交流。

## 硬性边界（违反即合并失败）

1. **只写** `loan-service/**`（含 W0 留下的 `consumer/LoanCreatedConsumer.java` 骨架）。
2. **绝不修改**：`common/`、`infrastructure/`、`application/`、`domain/`、`web/`、父 `pom.xml`、
   任何迁移文件、`smoke-test.py`、`PROGRESS.md`、其他服务目录。
3. 不改 `@FeignClient` 契约与 DTO 字段（W0 已冻结，改了会把 BANK 打挂）。
4. 不执行 `git merge` / `rebase`；只 commit + 打 `agent/w1-loan-done` tag。

## 开工前

```bash
cd C:/Users/Administrator/Documents/GitHub/wt-w1-loan
git log -1 --format='%h %s'     # 必须是 wave0b-baseline
cat docs/接口契约-事件与Feign.md  # LOAN_CREATED 的 payload 字段以这份为准
```

## 背景（少走弯路）

- `LoanApplicationService.createFromLoanCreated(LoanCreatedEvent)` **已经写好**：`loan_no = "LN-" + advanceId`，
  ON CONFLICT 幂等、按 VIN 复用已有借据、`advanceId` 为空直接拒绝（钱没到债不成立）。
- 缺的只有两件：**谁来调用它**（消费者）、**事件从哪来**（core 放款时发布，属 W2-CORE）。
- 所以你的核心交付是把 W0 的骨架消费者补成真消费者，并用测试证明幂等成立。

## 任务

1. **补完 `LoanCreatedConsumer`**
   - `name()` = `"loan-service"`，`eventTypes()` = `Set.of("LOAN_CREATED")`。
   - 解析 payload JSON → `LoanApplicationService.LoanCreatedEvent(vin, advanceId, dealerId, creditLineId, principal, dueDate, gracePeriodDays, traceId)`。
   - `dueDate` 用 `LocalDate.parse`；缺 `vin` / `advanceId` → 抛 `IllegalArgumentException`（交给框架重试/死信，不要吞）。
   - 日志必须带 `eventId` + `traceId`，便于跨服务排查。
2. **核对查询契约**：`GET /api/loans/by-vin/{vin}` 的响应字段必须与 `common/.../contract/LoanSnapshotDto` 完全一致
   （`id/vin/advanceId/dealerId/status/principal/dueDate`）。不一致就改 loan 侧，**不改 DTO**。
3. **重复投递验证**：同一 `advanceId` 的 `LOAN_CREATED` 投两次，只允许一笔借据、一条还款计划。
4. **（可选，S）消费 `PAYMENT_MATCHED`**：按 `vin` 重算余额与 DPD、落 `overdue_records`。
   做不完就在 HANDOFF 里写清 TODO，不要留半截代码。
5. **单测**（不依赖 DB）：payload 解析、缺字段抛错、重复事件走"复用已有借据"分支（fake repo）。
6. **`scripts/smoke_w1_loan.py`**（在 worktree 内，用 W0 的 `agent_smoke_lib.py`）：
   - 插一条合法 `LOAN_CREATED` → 3 秒内 `loans` 出现 `LN-<advanceId>` 且 `status='DRAFT'`；
   - 再插同 `advanceId` 的事件 → 断言 `loans` 仍只有一行、`event_consumptions` 只有一行；
   - 断言 `GET /api/loans/by-vin/{vin}` 返回的 JSON 字段与契约逐字段对齐。

## 交付

- 每任务一 commit，前缀 `[W1-LOAN]`；完成后 `git tag agent/w1-loan-done`。
- `loan-service/HANDOFF-W1.md`：改了哪些文件、留给 W2/W3 的 TODO、本地怎么验证。
- 回复里贴：`mvn -pl loan-service -am test` 与 `smoke_w1_loan.py` 的输出摘要。

## 验证

```bash
mvn -q -pl loan-service -am test
mvn -q -pl loan-service -am package
python scripts/smoke_w1_loan.py    # 需 web + loan-service 在跑
```

## 禁止

- 不要为了让测试过而在生产代码里伪造借据/事件。
- 不要改 `loans` 表结构（V16 已定；需要新列走 W3 统一排号）。
- 不要顺手去修 DPD 分档、画像、预警（不属于你）。
