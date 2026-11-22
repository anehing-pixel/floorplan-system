# AGENT TASK · W1-CREDIT（额度真实回补 + 可用额度查询）

## 你的身份

你是 **W1-CREDIT** agent，worktree `C:/Users/Administrator/Documents/GitHub/wt-w1-credit`，分支 `agent/w1-credit`，
基线 `wave0b-baseline`。所有命令先 `cd` 到该 worktree。中文交流。

## 硬性边界（违反即合并失败）

1. **只写** `credit-service/**`（含其启动类）。
2. **绝不修改**：`common/`、`infrastructure/`、`application/`、`domain/`、`web/`、父 `pom.xml`、
   **任何迁移文件**（`credit_restores` / `credit_lines` 列已齐）、`smoke-test.py`、`PROGRESS.md`、其他服务目录。
3. 不改 `common` 里 Feign 契约与 DTO 字段（W0 冻结）。
4. 不执行 `git merge` / `rebase`；只 commit + 打 `agent/w1-credit-done` tag。

## 开工前

```bash
cd C:/Users/Administrator/Documents/GitHub/wt-w1-credit
git log -1 --format='%h %s'      # wave0b-baseline
cat docs/接口契约-事件与Feign.md   # PAYMENT_MATCHED 的 payload
```

## 背景

- 额度**真相源**是 credit-service（ADR 已定），但"回补"目前只在 bank 侧留了一条 `credit_restores` 记录：
  `status='PENDING'`、`available_after` 留空，注释明确写"真实写入由授信侧在自己的行锁事务内完成"。
  也就是说：**回款匹配成功 ≠ 额度回来**，经销商额度被永久占用。
- `credit_lines` 已有 `total_amount` / `used_amount` / `frozen_amount` 三个字段（V23 另有 `initiated_by`）。
- 幂等锚点在表上：`uk_credit_restore_match (match_id)`、`idx_cr_idem (idempotency_key)`。

## 任务

1. **可用额度查询（BANK 依赖，先做）**
   - `GET /api/credit-lines/{id}/available` → 返回整数日元：`total - used - frozen`（负数截 0）。
   - 字段与 W0 的 `CreditLineQueryClient` 对齐；查不到返回 404（BANK 侧会 fail-closed）。
   - 加 `@RequirePermission`（读权限），不要裸奔。
2. **消费 `PAYMENT_MATCHED` 真实回补**
   - 新增 `consumer/PaymentMatchedConsumer`（`name()="credit-service"`，订阅 `PAYMENT_MATCHED`）。
   - 只在 `settled=true` 或明确带 `restoreAmount` 时回补；**部分回款回补多少必须由事件携带，不许自己按比例猜**（ADR Q24：部分还款不回补额度）。
   - 事务内顺序：
     a) `SELECT ... FOR UPDATE` 锁 `credit_lines` 行（或 CAS `used_amount >= :amt`）；
     b) `used_amount -= amt`，写 `credit_restores(status='RESTORED', available_before/after, restored_at, operator='system')`；
     c) 写审计（额度回补，带 `paymentRef` + `traceId`）。
   - 幂等：`uk_credit_restore_match` 冲突 → 直接返回，不重复扣减。
   - 额度不足扣减（`used_amount < amt`）→ 抛业务异常，事件走重试→死信，**不要扣成负数**。
3. **单测**
   - 同一 `matchId` 消费两次只回补一次；
   - `used_amount` 不足时不产生负数、异常类型正确；
   - `available` 计算含 frozen。
4. **`scripts/smoke_w1_credit.py`**（用 W0 的 `agent_smoke_lib.py`）
   - psql 造一条 `credit_lines`（total/used/frozen 固定）+ 一条 `PAYMENT_MATCHED`（`restoreAmount` 固定）；
   - 3 秒内断言：`used_amount` 减少、`credit_restores.status='RESTORED'`、`available_after` 非空；
   - 再插同 `matchId` 事件 → `used_amount` 不再变化；
   - `GET /api/credit-lines/{id}/available` 返回预期值。

## 交付

- 每任务一 commit，前缀 `[W1-CREDIT]`；完成后 `git tag agent/w1-credit-done`。
- `credit-service/HANDOFF-W1.md`：回补口径（含部分回款为何不回补）、锁策略、TODO。
- 回复里贴：`mvn -pl credit-service -am test` 与 smoke 输出摘要。

## 验证

```bash
mvn -q -pl credit-service -am test
mvn -q -pl credit-service -am package
python scripts/smoke_w1_credit.py    # 需 web + credit 在跑
```

## 禁止

- 不要在 bank 侧"顺手"改额度（跨域写，拆库即失效；W1-BANK 也不会碰 `credit_lines`）。
- 不要放开 `floorplan.credit.strict-sod`（默认 false 是原型兼容，生产才置 true，属部署决策）。
- 不要改 `PaymentMatchApplicationService`（那是 W1-BANK 的写入集）。
