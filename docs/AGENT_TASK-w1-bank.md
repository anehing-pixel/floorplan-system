# AGENT TASK · W1-BANK（回款匹配真接线 + 发事件）

## 你的身份

你是 **W1-BANK** agent，worktree `C:/Users/Administrator/Documents/GitHub/wt-w1-bank`，分支 `agent/w1-bank`，
基线 `wave0b-baseline`。所有命令先 `cd` 到该 worktree。中文交流。

## 硬性边界（违反即合并失败）

1. **只写** `bank-service/**`（含其启动类 `BankServiceApplication.java`）。
2. **绝不修改**：`common/`、`infrastructure/`、`application/`、`domain/`、`web/`、父 `pom.xml`、
   **任何迁移文件**（`payment_matches` 在 V17 已建好，不需要新表）、`smoke-test.py`、`PROGRESS.md`、其他服务目录。
3. 不改 `common` 里 Feign 契约与 DTO 的字段（W0 冻结）。
4. 不执行 `git merge` / `rebase`；只 commit + 打 `agent/w1-bank-done` tag。

## 开工前

```bash
cd C:/Users/Administrator/Documents/GitHub/wt-w1-bank
git log -1 --format='%h %s'      # wave0b-baseline
cat docs/接口契约-事件与Feign.md   # PAYMENT_MATCHED 的 payload 以这份为准
```

## 背景

bank-service 的匹配逻辑（规则打分、次高分差距、人工绑定）**已经写好且单测齐全**，
现在跑不通是因为三个存根把数据源掐断了：

| 类 | 现状 | 后果 |
|---|---|---|
| `client/LoggingLoanLookupClient` | 恒返回 `Optional.empty()` | 永远没有借据 → 自动匹配 0 命中 |
| `client/LoggingCreditLineClient` | 恒返回 0 | 解押/回补时可用额度算不出来 |
| `store/InMemoryPaymentStore` | 进程内 Map | 重启丢匹配结果，跨服务不可见 |

你的任务是把这三个换成真实现，并在匹配成功时发事件。**匹配规则本身不要动。**

## 任务

1. **借据查询换真 Feign**
   - 新增 `client/FeignLoanLookupClient implements LoanLookupClient`，内部注入 W0 的 `common.contract.LoanQueryClient`。
   - `GET /api/loans/by-vin/{vin}` 返回 404 → 映射 `Optional.empty()`；其他异常照抛（不要吞成 empty，否则会把故障伪装成"无借据"）。
   - `BankServiceApplication` 里把装配从 `LoggingLoanLookupClient` 换成新实现（日志存根保留，单测可用）。
2. **额度查询换真 Feign**
   - 新增 `client/FeignCreditLineClient` 实现 bank 内部 `CreditLineClient`，内部调 `common.contract.CreditLineQueryClient`。
   - 查不到额度 → 保持 fail-closed（返回 0），**不要放行**。
3. **匹配结果落库**
   - 新增 `store/JdbcPaymentStore implements PaymentStore`，写 `payment_matches`（V17 已有 `uk_payment_match_txn (account_ref, transaction_id)`）。
   - `saveMatchIfAbsent` 用 `INSERT ... ON CONFLICT (account_ref, transaction_id) DO NOTHING` + 回查实现，
     语义与 `InMemoryPaymentStore` 完全一致（幂等，不覆盖已有结论）。
   - `findMatch` / `findUnmatched` 用 JDBC 实现；`InMemoryPaymentStore` 保留（单测用）。
4. **匹配成功发 `PAYMENT_MATCHED`**
   - 在 `PaymentMatchApplicationService` 里，**`AUTO_MATCHED` 落库与 outbox 事件必须在同一事务**（`@Transactional` 加在该方法上）。
   - payload 按契约：`vin / loanId / advanceId / dealerId / paymentRef(transaction_id) / amount / settled / matchedAt / traceId`。
   - `settled` 判定：流水金额 ≥ 借据应收（先按 `principal` 比，利息未接入就写注释说明），**不要猜**。
   - 未匹配 / 候选待确认 / 人工绑定前的挂起，**一律不发事件**。
5. **单测**（不依赖 DB）
   - Feign 404 → `Optional.empty()`；Feign 500 → 抛异常。
   - 匹配成功发一条事件、未匹配发 0 条（用 fake `OutboxPublisher` 计数）。
   - `saveMatchIfAbsent` 重复调用只有一条记录（fake 或集成测试二选一）。
6. **`scripts/smoke_w1_bank.py`**（用 W0 的 `agent_smoke_lib.py`）
   - psql 造：一条 `loans`（`status='ACTIVE'`、`vin` 固定、`principal` 固定）+ 一条 `payment_transactions`（摘要含该 VIN、金额相等）。
   - 调 `POST /api/payments/match`（走网关或直连 8082）→ 断言 `payment_matches.match_status='AUTO_MATCHED'`、`outbox_events` 多出一条 `PAYMENT_MATCHED`。
   - 再调一次 → 断言不产生第二条事件（幂等）。

## 交付

- 每任务一 commit，前缀 `[W1-BANK]`；完成后 `git tag agent/w1-bank-done`。
- `bank-service/HANDOFF-W1.md`：三个存根的替换情况、`settled` 判定口径、留给 W3 的 TODO。
- 回复里贴：`mvn -pl bank-service -am test` 与 `smoke_w1_bank.py` 输出摘要。

## 验证

```bash
mvn -q -pl bank-service -am test
mvn -q -pl bank-service -am package
python scripts/smoke_w1_bank.py      # 需 web + bank + loan + credit 在跑
```

## 禁止

- 不要改 `VinMatcher` 的打分与阈值（规则已定源，改动会打穿已通过的用例）。
- 不要在 bank 里直接写 `loans` / `credit_lines` 表（跨域写，拆库即失效）。
- 不要改 `ReleaseOrchestrator` 的解押/回补语义（那是 W1-RELEASE / W1-CREDIT 的地盘），
  你只负责把 `PAYMENT_MATCHED` 发出去。
