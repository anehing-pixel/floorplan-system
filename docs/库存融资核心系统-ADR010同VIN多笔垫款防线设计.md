# ADR-010：同一 VIN 多笔垫款的性质判定与重复放款防线

> **状态**：已决策（2026-10-26）
> **性质**：架构决策 + 缺陷修复
> **触发**：用户提问「同一 VIN 可能有多笔 advance? 这不就是同一辆车多次抵押了？」
> **关联**：ADR-006（垫款五段四岗）、ADR-007（上游直付）、ADR-008（逾期归属借据）

---

## 1. 结论先行

用户的直觉**是对的，而且比想象的更严重**。

但实证核查给出的结论与"一车多债"这个说法**不一样**：

| 项 | 结论 |
|---|---|
| 现象 | 库内确有 VIN 对应多笔 advance：`COLA017481`、`COLB017481` 各 2 笔 |
| **性质** | ❌ **不是**业务上的"一车多债"（分批放款／二次融资）<br>✅ **是**同一笔垫款申请被**重复提交了两次** |
| 后果 | 额度被**重复占用 4 份**（4 × 1,665,000 = 6,660,000），而实际只应占用 1 份 |
| 根因 | `stageAdvance` **没有任何"该 VIN 是否已有在途垫款"的校验**；`advances` 表对 VIN **无唯一约束** |
| 真实场景 | **业务上确实允许一车多债**（分批付尾款、车已结清后再入库），但那需要显式建模，不能靠"重复提交"实现 |

**一句话**：用户担心的"同一辆车多次抵押"不是幻象——**系统当前确实允许同一台车发起多笔有效垫款申请，且没有任何一道防线拦住它**。这不只是数据问题，是**资损级缺陷**。

---

## 2. 实证核查（可复现）

### 2.1 现象

```sql
SELECT vin, count(*) AS adv_cnt,
       string_agg(status || ':' || amount::text, ' | ' ORDER BY id) AS detail
FROM advances GROUP BY vin ORDER BY adv_cnt DESC, vin LIMIT 15;
```

结果（节选）：

```
    vin     | adv_cnt |                detail
------------+---------+---------------------------------------
 COLA017481 |       2 | PENDING:1665000 | PENDING:1665000
 COLB017481 |       2 | PENDING:1665000 | PENDING:1665000
```

两台车各 2 笔，**金额完全相同**、**状态全部 PENDING**。

### 2.2 这不是"一车多债"，是重复提交

关键证据（`audit_events`）：

```
 id  | actor     | actor_role         | action  | target_id  | after_snapshot  | created_at
-----+-----------+--------------------+---------+------------+-----------------+-------------------------------
1698 | operator  | BUSINESS_OPERATOR | ADVANCE | COLA017481 | reserved=1665000 | 12:24:42.443186
1699 | operator  | BUSINESS_OPERATOR | ADVANCE | COLB017481 | reserved=1665000 | 12:24:42.460300
1700 | operator  | BUSINESS_OPERATOR | ADVANCE | COLA017481 | reserved=1665000 | 12:24:42.476471
1701 | operator  | BUSINESS_OPERATOR | ADVANCE | COLB017481 | reserved=1665000 | 12:24:42.489395
```

**33 毫秒内，COLA → COLB → COLA → COLB 顺序提交 4 次**。如果是业务上的分批放款，
不可能在 33ms 内完成四次提交；如果是二次融资，两笔之间必然隔着"结清 + 重新入库"。

进一步佐证：

```sql
SELECT id, advance_no, vin, amount, status, purchase_order_id, idempotency_key
FROM advances WHERE vin IN ('COLA017481','COLB017481') ORDER BY vin, id;
```

```
 id  | advance_no  | vin         | amount  | status  | purchase_order_id | idempotency_key
-----+-------------+-------------+---------+---------+-------------------+---------------------------------------
 363 | ADV-F32CD84B | COLA017481 | 1665000 | PENDING |              NULL | 493fac57-71fe-4952-8347-c7e6b6f29774
 365 | ADV-769FD65E | COLA017481 | 1665000 | PENDING |              NULL | 470c35d5-3f78-4daf-a357-dac02858b3ba
 364 | ADV-35E83F18 | COLB017481 | 1665000 | PENDING |              NULL | eba9dce9-e385-43f5-b333-6a011804e717
 366 | ADV-84EFC6CA | COLB017481 | 1665000 | PENDING |              NULL | c25d8c8f-36eb-4f52-9fd7-06546e38fc01
```

- 四笔 `idempotency_key` **各不相同**（UUID）→ 幂等键每次都换，**幂等机制被绕开**
- 四笔 `purchase_order_id` **全为 NULL** → 未走采购订单通道，是直接建账路径
- `vehicle_units` 侧：`COLA017481` / `COLB017481` 的 `advance_id` 为 **NULL**（该字段从未写入）
- 车辆状态：`FINANCED`（申请阶段不流转台账，符合 `stageAdvance` 的设计）

### 2.3 额度被重复占用

```sql
SELECT id, dealer_id, approved_limit, used_amount FROM credit_lines WHERE id = 239;
```

```
 id  | dealer_id | approved_limit | used_amount
-----+-----------+----------------+-------------
 239 |       288 |      200000000 |      6660000
```

`6,660,000 = 4 × 1,665,000`。两台车实际只需占用 `3,330,000`，**多占 3,330,000**。
若这 4 笔全部走到放款成功，**实际出账 666 万日元而真实债务只有 333 万**——直接资损。

---

## 3. 根因分析：为什么四道防线全部失守

### 3.1 防线一：应用层「同 VIN 已有垫款」校验 —— **不存在**

`AdvanceApplicationService.stageAdvance`（`:395`）全文：

```java
public Advance stageAdvance(Long creditLineId, String vin, String idempotencyKey, String actor) {
    CreditLine line = creditLineRepository.findByIdForUpdate(creditLineId)...;
    VehicleUnit unit = vehicleUnitRepository.findByVinForUpdate(vin)...;
    if (unit.getStatus() != VehicleStatus.FINANCED) {          // ← 只查车辆状态
        throw new BusinessException(ErrorCode.VEHICLE_002, ...);
    }
    long amount = unit.getAdvanceAmount();
    line.occupy(amount);                                        // ← 每次都占额度
    creditLineRepository.save(line);
    Advance advance = new Advance();
    advance.setVin(vin);
    ...
    Advance saved = advanceRepository.save(advance);            // ← 无任何去重
    ...
}
```

**只校验车辆状态是 `FINANCED`，从不校验"该 VIN 是否已有在途垫款"。**
车辆状态在申请阶段**不流转**（仍是 `FINANCED`），所以第二次提交时状态校验依然通过。

`advanceRepository` 也**没有** `findByVinAndStatusIn` / `countActiveByVin` 之类的方法
（`JdbcAdvanceRepository` 只有 `findByVin` 取 `id DESC LIMIT 1` 的单笔查询，见 `:65`）。

### 3.2 防线二：`idempotency_key` 幂等 —— **被绕开**

`applyAdvance`（`:225`）的幂等完全依赖 `idempotency_key`：

```java
var existing = advanceRepository.findByIdempotencyKey(idempotencyKey);
if (existing.isPresent()) { ... return buildAdvanceResponse(a, ..., true); }
```

幂等键由**调用方**生成。本例中四次调用各自生成了新 UUID → 幂等键**只在"同一次重试"内有效**，
对"业务上重复提交同一台车"完全无效。

> **这是设计层缺陷，不是调用方用错**。幂等键防的是**网络重试**，不是**业务重复**。
> 业务重复必须有独立的业务唯一性约束来防。

### 3.3 防线三：数据库唯一约束 —— **不存在**

```sql
SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
WHERE conrelid = 'advances'::regclass AND contype IN ('u','p');
```

```
 advances_pkey            | PRIMARY KEY (id)
 advances_advance_no_key  | UNIQUE (advance_no)
 advances_idempotency_key_key | UNIQUE (idempotency_key)
```

**`advances` 表对 `vin` 没有任何唯一性约束**，只有一个普通索引 `idx_advances_vin`。

### 3.4 防线四：`uk_po_vin_active` —— **管的是另一件事**

该索引（V7 迁移）位于 **`vehicle_units` 表**，非 `advances`：

```sql
"uk_po_vin_active" UNIQUE, btree (vin)
  WHERE purchase_order_id IS NOT NULL
    AND status IN ('FINANCED','IN_STOCK','SOLD_PENDING')
```

作用是"**一个 VIN 不能同时挂在两个活跃采购订单下**"（防同一台车被两个渠道重复采购），
**与"同一台车能否有多笔垫款"无关**。且它是**部分索引**——`purchase_order_id IS NULL` 的行不受约束，
本例四笔 advance 的 `purchase_order_id` 恰好全为 NULL。

> ⚠️ **记忆纠错**：MEMORY.md 记「VIN 幂等（条件唯一索引 `uk_po_vin_active`）防两单报同一台车」——
> 这句**本身没错**（它确实防两单报同一台车），但**极易被误读为"VIN 全局唯一"**。
> 该索引**不覆盖 `advances` 表**，也**不覆盖 `purchase_order_id IS NULL` 的行**。

### 3.5 已有的「重复融资拦截」拦的是另一件事

`ingestVehicles`（`:150`）的 P0-③ 重复融资拦截：

```java
var lien = financingLienCheckRepository.findLatestByVin(draft.getVin());
if (lien.isPresent() && lien.get().isEncumbered()) {
    throw new BusinessException(ErrorCode.VEHICLE_005, ...);   // 该车在他处存在抵押/查封
}
```

它拦的是「**该车在他处**（外部机构）已有抵押/查封」——**外部权属负担**，来自人工/厂家门户核对。
而本例是「**本系统内**同一台车被重复发起垫款」——**系统内重复融资**。

**两者是完全不同的风险，代码注释里的"在他处"三个字恰恰划清了边界，但边界之外一片空白。**

---

## 4. 业务上到底允不允许一车多债？

必须区分两种截然不同的情况：

### 4.1 情形 A：**同一笔采购的分批放款**（业务允许）

日本新车渠道常见：上游要求先付定金、到货付尾款；或一笔采购分多次结算。
此时**同一台车对应多笔 advance 是正常的**。

但要注意：**分批放款 ≠ 多笔债**。这是**一笔债**（一个 loan）分几次支付，
所以多笔 advance 应挂在**同一个 loan** 下，而不是各自独立。

> 这正是 ADR-008 已确立的原则：`loan.advance_id → advances.id`（advance 是资金来源证明）。
> 在 ADR-008 的目标模型里，**分批放款 = 一个 loan 下挂多条 advance 明细**。

### 4.2 情形 B：**车已结清后重新入库再融资**（业务允许）

车卖出去 → 结清 → 解押 → 又从别的渠道买回来（拍卖/二手）。
**同一 VIN 在生命周期内会有两段独立的债务**，前一段已 `SETTLED`。

### 4.3 情形 C：**同一台车在未结清时被重复放款**（业务禁止，且当前系统允许）

车还在库里、债没结清，又放一笔款 → **同一台车被重复抵押**。
这正是用户担心的场景，**在库存融资业务中等于让与担保的重复登记，会导致优先权瑕疵**。

### 4.4 判定结论

| | 是否允许 | 正确实现方式 | 当前系统 |
|---|---|---|---|
| A 分批放款 | ✅ 允许 | 一个 loan 下多条 advance 明细 | ❌ 未建模，且**实现成了重复提交** |
| B 结清后再融资 | ✅ 允许 | 两条独立 loan，各自状态机 | ❌ 未建模 |
| C 未结清时重复放款 | ❌ **禁止** | **必须拦截** | ❌ **无任何拦截，且已在库内发生** |

**当前系统把 A 和 C 用同一种方式实现了——"同一个 VIN 再插一条 advance"，
结果既没有 A 的正确语义，又放行了 C 的资损。**

---

## 5. 决策

### 5.1 D1：确立「同一 VIN 同时至多一笔在途垫款」为硬不变量

> 在**借据（loan）**层面，同一 VIN 在**任一时刻**至多存在一笔**未结清**的债务。
> 一笔未结清债务**可以**有多个 advance 明细（分批放款），但它们**共享同一额度占用与同一笔本金**。

这条不变量把 A 和 C 彻底分开：
- A（分批放款）：一笔 loan、多个 advance、**额度只占一次**
- C（重复放款）：两笔 loan、**被不变量拒绝**

### 5.2 D2：业务唯一性必须落在数据库，不依赖应用层自觉

新增 **V12 迁移**：

```sql
-- ① advance 侧：同一 VIN 至多一笔"在途"垫款（PENDING/PROCESSING）
--    部分唯一索引：只约束在途，终态（PAID/FAILED/REVERSED）不受限，
--    以支持"结清后重新入库再融资"（情形 B）
CREATE UNIQUE INDEX uk_advance_vin_inflight
    ON advances (vin)
    WHERE status IN ('PENDING', 'PROCESSING');

-- ② vehicle_units 侧：同一 VIN 至多一条"未结清"债务
--    补上 uk_po_vin_active 的空白区（无采购订单的直连建账路径）
CREATE UNIQUE INDEX uk_vehicle_vin_open_debt
    ON vehicle_units (vin)
    WHERE status NOT IN ('SETTLED', 'CLOSED');
```

- ①用**部分唯一索引**而非全表唯一：终态行可重复，天然支持情形 B。
- ②补齐 `uk_po_vin_active` 的漏洞（`purchase_order_id IS NULL` 的行不受约束）。
- **数据库约束是最后一道防线**：应用层可能漏，数据库不会。

### 5.3 D3：应用层补业务唯一性校验（给出友好错误，而非 DB 报错）

`stageAdvance` 在 `findByVinForUpdate` 拿到行锁后、占额度**之前**插入：

```java
// 业务唯一性：同一 VIN 至多一笔在途垫款（D1）
// 行锁已持有（findByVinForUpdate），并发提交会被串行化，此处检查可靠
if (advanceRepository.existsInflightByVin(vin)) {
    throw new BusinessException(ErrorCode.ADVANCE_004,
        Map.of("vin", vin, "reason", "该车已有在途垫款，不能重复融资"));
}
```

> 为什么放在 `findByVinForUpdate` 之后？行锁保证同一 VIN 的并发申请**串行执行**，
> 第二个请求进来时能看到第一个已提交的 PENDING 行 → 检查可靠。
> 若放在加锁之前，并发下两个请求可能同时通过检查（TOCTOU）。

**新增错误码** `ADVANCE_004`：「该车已有在途垫款，不能重复融资」。

### 5.4 D4：额度占用与释放必须与「借据」对齐，而非与「advance」对齐

这是本次缺陷最深的教训。

**现状**：额度在 `stageAdvance` 按 advance 粒度占用（每笔 advance 占一次），
在 `markFailed`（`:488`）按 advance 粒度释放（`line.release(a.getAmount())`）。

**问题**：分批放款（情形 A）下，一笔 loan 的 N 笔 advance 会**占用 N 倍额度**；
而 `CreditLine.release`（`:96`）用的是 `Math.min(amount, usedAmount)` 截断，
**放款成功后的重复释放会被静默吞掉**——账实从此不一致。

**决策**：额度占用/释放的锚点改为**借据（loan）**：

```
一笔 loan 存续期间，额度占用 = 该 loan 的放款总额（所有 advance 之和）
advance 是支付指令（ADR-006），不是额度账单位
```

在 ADR-008 的 `loans` 表落地前，**过渡方案**：
`stageAdvance` 占用额度时改为**占用该 VIN 在途 advance 的累计剩余**而非本笔全额——
即第二笔分批放款只占"本笔金额 - 已占金额"，使同一笔债的总占用恒等于债额。

> ⚠️ 这是过渡期的权宜之计。**根本解是 ADR-008 的 loans 表**——
> 只有引入借据，额度账本才有唯一锚点。ADR-008 的紧迫性因此上升。

### 5.5 D5：复核环节必须能阻断，且结论必须落库

**现状缺陷**（`reviewAdvance`，`:260`）：

```java
boolean pass = true;
if (order == null) {
    checks.add("未关联采购订单：收款方无法确认，需补单后复核");
    pass = false;          // ← 只改返回值，不抛异常、不改状态、不落库
}
...
advanceRepository.saveReview(pending.getId(), actor, LocalDateTime.now());  // 只存 reviewer + reviewed_at
data.put("reviewResult", pass ? "PASS" : "REJECTED");
```

- `advances` 表**没有复核结论列**（只有 `reviewer` / `reviewed_at`）→ `REJECTED` 结论**不落库**。
- `pass=false` **不阻断**：`disburseAdvance`（`:326`）**不检查复核结论**，
  只检查状态是 `PENDING` → **复核未通过的垫款照样能放款**。

这意味着 ADR-006 的"五段四岗"里，**复核岗实际上是虚设的**——复核只留痕、不把关。

**决策**：
1. `advances` 增列 `review_result VARCHAR(16)`（`PASS`/`REJECTED`）与 `review_note TEXT`，
   复核结论**必须落库**。
2. `reviewAdvance` 在 `pass=false` 时**同步翻状态**为 `REJECTED`（新增终态），并**释放已占额度**。
3. `disburseAdvance` 增加前置校验：`review_result != 'PASS'` → 拒绝放款（`ADVANCE_005`）。

### 5.6 D6：重复提交必须由「业务唯一键」拦截，而非「幂等键」

| | 防的是 | 谁生成 | 语义 |
|---|---|---|---|
| `idempotency_key` | 网络重试 / 消息重投 | 调用方 | 同一次请求重发 |
| **业务唯一键** | 业务上重复操作 | **系统规则** | 同一台车只能一笔在途垫款 |

**决策**：新增 `advance_biz_key` 概念（业务唯一键），由系统按
`vin + 状态域` 派生，数据库唯一索引兜底（§5.2①）。调用方**无权**绕过。

### 5.7 D7：PENDING 垫款必须有时效，否则额度永久占用

**现状**：`advances` 无创建后超时作废机制。PENDING 的垫款占着额度不放，
若业务人员申请后不推进，**额度被永久锁死**。

**决策**：新增定时作业 `advancePendingExpiryJob`：
- PENDING 超过 N 小时（可配置，建议 72h）未复核 → 置 `EXPIRED`，**释放额度**，写审计。
- 需新增 `EXPIRED` 终态（或复用 `FAILED` 但独立原因码，**推荐独立状态**便于区分）。

> 现状无任何 `@Scheduled` 任务（全库零命中），本作业是**第一个**定时任务，
> 需同步引入调度框架（Spring `@Scheduled` 已足够，不必上 Quartz）。

---

## 6. 影响面与实施顺序

### 6.1 存量数据清理（**必须先做，否则加索引会失败**）

当前 4 笔重复的 PENDING advance 仍在占用额度。加 `uk_advance_vin_inflight` 前必须先处理：

```sql
-- 保留最早一笔，其余标记为重复提交并释放多占的额度
-- 人工确认后执行，不可盲目 DELETE（审计链要求）
```

### 6.2 实施顺序（按依赖）

| 阶段 | 内容 | 依赖 |
|---|---|---|
| **P0** | 存量清理（释放多占 3,330,000）+ 应用层 D3 校验 | 无 |
| **P0** | D5 复核阻断（结论落库 + 放款前校验） | V12 加列 |
| **P1** | V12 迁移：两个部分唯一索引 | 存量清理完成 |
| **P1** | D7 超时作废作业 + 调度框架 | 无 |
| **P2** | D4 额度锚点迁移到 loan（真正解决） | ADR-008 loans 表落地 |

> **P0 先于 P1**：先加应用层校验能立刻止血（返回友好错误），
> 数据库约束是第二道防线。**存量清理必须最先做**。

### 6.3 与 ADR-008 的关系

ADR-008 论证"一车多债"时引用的实证（`COLA017481` 有 2 笔 advance）**必须更正**：
那**不是**业务上的一车多债，而是**重复提交的脏数据**。

但 ADR-008 的**结论不受影响**——因为：
- 情形 A（分批放款）与情形 B（结清后再融资）**在业务上确实允许一车多债**，
  借据模型依然是正解；
- 本 ADR 反而**强化**了 ADR-008 的必要性：
  **没有借据，就没有额度占用的唯一锚点（D4），就没有一车多债的正确表达。**

> ⚠️ ADR-008 §1.4 的实证描述需按 §7 勘误。

---

## 7. 对既有文档的勘误

### 7.1 ADR-008 §1.4 实证更正

原文：

> 实证：库内 `COLA017481` 有 2 笔 advance。业务上这意味着：
> 一台车被**两次融资**（先融资 A 买入 → 售出 → 再融资 B 买入），
> 或**部分融资**（同一批采购多笔放款）。

**更正**：该实证**不能作为"一车多债"的业务证据**。经 2026-10-26 深度核查，
这 2 笔 advance 是**同一笔垫款申请被重复提交两次**造成的脏数据
（33ms 内提交、金额相同、`idempotency_key` 各异、`purchase_order_id` 全空），
详见 ADR-010。

**但 ADR-008 的结论不变**——"一车多债"在业务上确实允许（分批放款 / 结清后再融资），
只是**不能用这个实证来证明**，需要另行举业务例证。

### 7.2 《十一环节闭环核查报告》Q16 结论

原文（`:662`）：

> **Q16**：同一台车二次融资，业务上是否允许？库内已有 2 组实证，但可能是测试数据。
> 若业务上不允许，ADR-008 的一车多债场景就不存在，设计可简化。

**结论更新**：确实是测试数据。业务上**允许**一车多债（分批放款 / 结清后再融资），
**但当前系统用"重复提交"错误地实现了它，并放行了未结清重复放款的资损**。

### 7.3 MEMORY.md 记法的歧义提示

MEMORY.md 中「VIN 幂等（条件唯一索引 `uk_po_vin_active`）防两单报同一台车」
易被误读为 VIN 全局唯一。实际约束范围：

- 约束表：`vehicle_units`（**不是 `advances`**）
- 约束列：`vin`
- 生效条件：`purchase_order_id IS NOT NULL` **且** `status IN ('FINANCED','IN_STOCK','SOLD_PENDING')`
- **不覆盖**：`purchase_order_id IS NULL` 的行（直连建账路径）、**不覆盖** `advances` 表

---

## 8. 与用户提问的对应关系

| 用户的问题 | 回答 |
|---|---|
| 同一 VIN 可能有多笔 advance？ | **是的，系统当前允许，且已发生**（实证：4 笔） |
| 这不就是同一辆车多次抵押了？ | **判断正确**。业务上「未结清时重复放款」就是重复抵押，被禁止；「分批放款」和「结清后再融资」允许，但不是同一回事 |
| 那现在库里这 4 笔怎么解释？ | **不是业务上的多次抵押，是重复提交**——33ms 内 4 次、金额相同、幂等键各异。问题性质从"设计歧义"降级为"**实现缺陷（资损级）**" |
| 需要改吗？ | 需要。P0 立即止血（应用层校验 + 存量清理），P1 加数据库约束；根本解是 ADR-008 的借据表 |

---

## 9. 待确认事项

| # | 事项 | 影响 | 建议 |
|---|---|---|---|
| Q1 | 同一 VIN 允许几笔**在途** advance？ | 决定 `uk_advance_vin_inflight` 的索引条件 | 建议 **1 笔**（分批放款靠 loan 明细承载，不靠多笔在途） |
| Q2 | PENDING 超时作废阈值 | D7 作业参数 | 建议 **72 小时**，可配置 |
| Q3 | 业务上是否存在真实的分批放款（情形 A）？ | 决定 D4 过渡方案是否够用 | 需业务确认；若存在且频繁，应**优先落地 ADR-008 loans 表** |
| Q4 | 复核 `REJECTED` 是否需要独立终态？ | D5 状态机 | 建议新增 `REJECTED`（区别于 `FAILED`＝支付失败） |
| Q5 | 存量 4 笔重复 advance 如何处置？ | 清理脚本 | 保留最早 1 笔，其余标记并释放额度，**不物理删除**（审计链） |
