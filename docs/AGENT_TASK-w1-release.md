# AGENT TASK · W1-RELEASE（出库指令自动触发 + 撤销 + 巡检）

## 你的身份

你是 **W1-RELEASE** agent，worktree `C:/Users/Administrator/Documents/GitHub/wt-w1-release`，分支 `agent/w1-release`，
基线 `wave0b-baseline`。所有命令先 `cd` 到该 worktree。中文交流。

## 硬性边界（违反即合并失败）

1. **只写** `release-service/**` + 你自己的迁移号段 `infrastructure/src/main/resources/db/migration/V27__release_cancel.sql`（**按需**，列够用就不建文件）。
2. **绝不修改**：`common/`、`infrastructure/` 其他 Java 文件、`application/`、`domain/`、`web/`、父 `pom.xml`、
   V1–V26 迁移、`smoke-test.py`、`PROGRESS.md`、其他服务目录。
3. 不改状态机白名单以外的流转语义（红线不能松）。
4. 不执行 `git merge` / `rebase`；只 commit + 打 `agent/w1-release-done` tag。

## 开工前

```bash
cd C:/Users/Administrator/Documents/GitHub/wt-w1-release
git log -1 --format='%h %s'      # wave0b-baseline
cat docs/接口契约-事件与Feign.md   # PAYMENT_MATCHED 的 payload
```

## 背景

- 出库指令现在**只能人工 `POST /api/releases` 建单**：结清/回款成功后没有任何触发方，
  所以"结清即出库"这条环节实际上是空转的。
- 状态机是六态：`PENDING_RELEASE → SENT → CONFIRMED → RELEASED → LIEN_DISCHARGED`，另有 `CANCELLED`（终态）。
  两条资损红线：**未 `CONFIRMED` 不放车**、**未 `RELEASED` 不解押**。这两条一个字都不许松。
- 表已齐：`release_orders` / `release_order_items` / `release_state_history` / `release_receipts` / `redemptions`。
- 并发安全已用 CAS（`updateStatus` 带 `expectedFrom`），沿用，不要改回无条件 UPDATE。

## 任务

1. **消费 `PAYMENT_MATCHED` 建草稿指令**
   - 新增 `consumer/PaymentMatchedConsumer`（`name()="release-service"`，`eventTypes()=Set.of("PAYMENT_MATCHED")`）。
   - 仅当 `settled=true`（整笔结清）才建单；部分回款不建。
   - 建单落 `PENDING_RELEASE` 草稿，**不自动 `confirm`、不自动 `release`**——放车仍必须人工确认。
   - 幂等：同一 `vin`（或 `paymentRef`）已有未 `CANCELLED` 的指令则跳过；用唯一约束或先查后插 + CAS 双保险。
   - 建单同时写 `release_state_history`，操作人记 `system(PAYMENT_MATCHED)`，事件 `traceId` 落到备注。
2. **撤销端点**
   - `POST /api/releases/{id}/cancel`，入参 `reason`（必填，≤200 字），权限沿用本服务的 `@RequirePermission`。
   - 仅允许 `PENDING_RELEASE` / `SENT` 撤销；`CONFIRMED` 及之后拒绝（已在接收方手上，需走线下冲正）。
   - 走 CAS `WHERE id=? AND status=?`，失败抛 `CONFLICT_001`；写 `release_state_history` + 审计。
   - V27 只在确实缺列（撤销原因/撤销时间/撤销人）时创建，**不要为了建而建**。
3. **巡检作业**
   - `POST /api/releases/patrol`（手动触发，暂不接调度）：
     - `SENT` 超过 N 天未回执（N 可配，默认 2）→ 返回清单 + 写审计；
     - `RELEASED` 但 `vehicle_units.security_status` 未解押 → 返回"账实缺口"清单。
   - 只读诊断 + 告警，**不要自动改状态**（自动化改账属另一个决策）。
4. **单测**
   - 消费幂等：同 `paymentRef` 投两次只建一单；
   - 部分回款不建单；
   - `cancel` 在 `CONFIRMED` 必须拒绝；
   - 巡检能识别两类异常（用 fake repo）。
5. **`scripts/smoke_w1_release.py`**（用 W0 的 `agent_smoke_lib.py`）
   - psql 插一条 `PAYMENT_MATCHED`（`settled=true`）→ 3 秒内出现 `PENDING_RELEASE` 指令；
   - 重复插同 `paymentRef` → 指令数不变；
   - `cancel` 成功 → 状态 `CANCELLED` + 历史表多一行；
   - 断言 `confirm → release → discharge-lien` 三条合法边仍可走通（红线不回归）。

## 交付

- 每任务一 commit，前缀 `[W1-RELEASE]`；完成后 `git tag agent/w1-release-done`。
- `release-service/HANDOFF-W1.md`：事件消费口径、撤销边界、巡检输出格式、TODO。
- 回复里贴：`mvn -pl release-service -am test` 与 smoke 输出摘要。

## 验证

```bash
mvn -q -pl release-service -am test
mvn -q -pl release-service -am package
python scripts/smoke_w1_release.py    # 需 web + release 在跑
```

## 禁止

- 不要把"结清"直接做成"自动放车"；草稿单必须停在 `PENDING_RELEASE`。
- 不要在 release 服务里直接 UPDATE `vehicle_units` 或 `collateral_ledger`（跨域写）。
- 不要改 `ReleaseStateMachine` 的白名单来"让测试过"。
