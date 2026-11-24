# ADR-Q27 · 担保权属权威源裁决（已定稿，不再等外部确认）

> 状态：**已裁决并落地**（2026-11-22）
> 决策人：系统架构负责人（代码与表结构的构建方）
> 影响范围：Q27、Q21、Q9/Q10、Q14、R5–R8、任务 #29/#30/#31
>
> **本文件取代此前所有「Q27 需业务书面定源」的表述。** 那些表述是错的：
> 表是本系统自己建的、字段是自己定的、优先级也是自己写在 `SourceSystem` 枚举里的，
> 把「以哪套表为准」包装成「等业务方定」等于让不了解表结构的人回答一道看不见的题。
> 这是内部架构决策，不是业务口径问题。

---

## 1. 先把「有哪些表」说清楚

系统里跟「担保」沾边的表一共分两组，都是本项目自己建的迁移。**没有第三套，也没有外部系统表。**

### A 组：V10（真账本，core 在用）

迁移 `V10__collateral_ledger.sql`。业务上真正在写数据的就是这组，④「设定担保」走
`SalesApplicationService.setSecurity()` → `CollateralLedgerApplicationService.registerCollateralBatch()`
→ `JdbcCollateralRegistrationBatchRepository.save()/addItem()`，冒烟（FAIL=0）跑的就是这条链路。

| 表 | 粒度 | 装什么 | 谁写 |
|---|---|---|---|
| `collateral_ledger` | 一 VIN 一行 | 担保物台账：四个独立状态（已放款/已售/已回款/已解除）、回款账户、盘点差异、处置痕迹 | core 放款/还款/售出用例 |
| `collateral_registration_batches` | 一批车一行 | **法务局登记批次**：登记编号 `registration_no`、担保合同、登记事项证明书、抹消三段状态、对抗要件方式、5 年失效日、顺位 | core `registerCollateralBatch()` |
| `collateral_registration_items` | 一批↔一车 | 批次与 VIN 的对应关系（`batch_id` / `ledger_id` / `vin`） | core `addItem()` / `removeItem()` |
| `collateral_evidence_links` | 一事件一行 | 证据链：合同·登记·放款·盘点·回款·处置按 VIN 串起来 | core 各用例 |

法律要点（V10 注释里已写明，是字段设计的硬约束）：让渡担保登记有 **5 年期限且不可续展**；
**抹消登记需权利人与登记义务人共同申请**，所以抹消是三段式待办，不是翻一个状态位；
2025 新法下**占有改定劣后于登记**。V18 的扁平表表达不了这些。

另有一张 `vehicle_units`（V1 车辆主数据），上面挂 `security_type` / `security_status`
两个字段，是**流程状态位**，不是权属事实。

### B 组：V18（我多建的一套「速查卡」）

迁移 `V18__collateral_registration.sql`，随 `collateral-reg-service` 拆分建的。

| 表 | 粒度 | 装什么 | 裁决后的定位 |
|---|---|---|---|
| `collateral_registration` | 一 VIN 一行 | `status` / `reg_no` / `owner_name` / `amount` / `source` | **只读投影**，由 V10 同步写入，不参与门禁判定 |
| `collateral_registration_history` | 一事件一行 | 登记/变更/抹消/快照流水 | 留痕，供运营审计 |
| `collateral_source_snapshot` | 一源一次上报 | 三源各自上报的状态 + `raw_payload` | **对账体检留痕**，发现不一致只告警，绝不改账 |

### 所谓「三源」不是三个平级事实源

V18 注释写的三源 = `vehicle_units` / `collateral_ledger` / `collateral_registration_batches`。
其中前两个是**从真账本派生出来的流程状态**，只有批次表是法律事实。
让三个派生状态投票决定法律事实，是我最初的设计错误。本 ADR 废止该模型。

---

## 2. 裁决

| 项 | 定论 |
|---|---|
| 唯一写入点 / 权威源 | **V10 `collateral_registration_batches` + `items`**（system of record） |
| V18 三张表 | **只读投影 + 留痕**；可写，但只由 V10 的写入同事务同步，不接受人工登记作为判定依据 |
| 放款门禁（B1）判定 | 按 VIN 从 `items → batches` 取最新有效批次：`registration_no IS NOT NULL` 且 `cancellation_status NOT IN ('DONE','CANCELLED')` → `REGISTERED`；否则 `NONE`（fail-closed 拒付） |
| 三源快照 | **对账诊断**：批量落库后只诊断一次，产出「是否一致 / 采信建议 / 理由」，**不 adopt、不覆盖任何权属表** |
| `DefaultAuthoritativeSourceStrategy` | 从「判定器」降级为「对账诊断器」，开关 `floorplan.collateral.authoritative-source.enabled` 只控制是否产出诊断，不影响门禁 |
| `floorplan.core.guarantee-gate.enabled` | 仍是门禁总开关（默认 `false`，本地原型/冒烟不改行为）；置 `true` 后按上面的 V10 规则判定 |

### 依据

1. **业务事实**：④ 设定担保写的是 V10；V18 三表当前无任何生产写入方，是空的。
2. **信息完整性**：V10 有 `perfection_method` / `expires_at` / `cancellation_status` /
   附件 ID / 证据链，V18 只有 5 个扁平字段。把 V18 当权威源等于把法律事实压成状态位，信息有损。
3. **自认**：V18 迁移注释自己写「三源 = vehicle_units / collateral_ledger / 批次表」——
   它承认自己是收敛点，**收敛点 ≠ 权威源**。
4. **一致性边界**：登记写入与 `vehicle_units` 状态在 core 同一个本地事务里提交；
   跨服务写权威源会引入分布式事务，没有收益。

### 两表怎么互通（不引 MQ，符合本地存储约束）

所有服务共用同一个 PostgreSQL 库（web 单服务真实跑通 Flyway V1–V25 已实证）。
因此在 `JdbcCollateralRegistrationBatchRepository.addItem()` / `removeItem()` 里
**用同一个 `JdbcTemplate`、同一个事务**同步 upsert V18 投影 + 追加流水：
零新增 bean、零构造签名变化、零启动装配风险。

**判定路径不依赖派生数据**：门禁查询直接 JOIN V10 三表读真账本，
即使投影同步延迟或失败，门禁也不会误放行。投影只服务运营查询与审计追溯。

将来真拆库了，把这一步换成 outbox 事件投递即可，接口不变。

---

## 3. 顺带定死的其它默认值（同样不再等外部确认）

| 编号 | 原表述 | 裁决 |
|---|---|---|
| Q21 | 无 REGISTERED 批次时硬阻断还是工单+预警 | **采用 A：硬阻断 + 同时生成 HIGH 工单**（用户 2026-11-22 08:26 明确裁决）。资金出门宁可错杀；工单给运营补登记，是拒付后的追补动作，不是放行、不是二选一。现有 `enforceGuaranteeGate()` 已同时做这两件事；**`relaxed-dealers` 白名单例外已取消**——保留它会让硬阻断退化成软放行，与裁决冲突。`evaluateGuarantee` 已改为两参签名 |
| Q9/Q10 | 出库指令接收方 / 外部 WMS | **只做内部指令状态机 + 人工确认，不接外部 WMS**。系统里没有 WMS，凭空定义对接接口没有意义；真有对接方时加 adapter，不动状态机 |
| Q14 | M1/M2/M3 按自然日还是营业日 | **自然日**。金融 DPD 通行口径，且 V10 的 `expires_at`（5 年）也是自然日；营业日要维护交易日历，复杂度不划算 |
| R5–R8 | 对账规则口径 | **沿用 R1–R4 同款口径：两口径数值精确相等才无差异，不等即产出差异，不做容差、不做舍入猜测**。口径不明时不假装通过，也不假装失败，按「取数 SQL + 纯函数判定」落地 |

### 关于 `ownerName` 的字段错位（顺手修）

V10 批次表原先没有权利人专用列，CORE 把 `ownerName`（担保权人）塞进了
`co_signed_by`（共同申请对方＝登记义务人＝经销商）。**这两个不是同一个主体**，
是我把字段混用了。V25 给 `collateral_registration_batches` 加 `owner_name` 专用列，
`setSecurity()` 改写到该列，`co_signed_by` 保留其本义。

---

## 4. 演进路径与风险边界

- **当前（单库单事务）**：V10 权威 + V18 投影同步，门禁读 V10。回滚只需关掉
  `floorplan.core.guarantee-gate.enabled`（默认已关），投影表数据不影响任何既有链路。
- **拆库后**：V10 写入落 outbox 事件，V18 投影改由消费端异步重建；
  门禁仍读 V10（同服务内），不跨服务。
- **失效场景**：若有人绕过 `JdbcCollateralRegistrationBatchRepository` 直接改 V10 表，
  投影会滞后。对账体检（三源快照）就是用来发现这种滞后的，定期跑即可。
- **不做什么**：不引入 Seata / 分布式事务，不引入对象存储，不引入 MQ。

---

## 5. 落地清单

| 任务 | 落地内容 |
|---|---|
| #29 权限 | `register`/`discharge` → `COLLATERAL_MAINTAIN`；`snapshot/import` → `JOB_TRIGGER`；用 `common.security.RequirePermission`（与 `JwtAuthInterceptor` 同源） |
| #30 fail-closed | 门禁查询 `getCurrent()` 改读 V10 权威源；V18 注释口径由 V25 `COMMENT ON` 纠正（不改 V18 文件，避免 Flyway checksum 失败） |
| #31 批量导入 | 新增 `POST /snapshot/import-batch`，一次收 1~3 条源、批量落库、只诊断一次；单条端点委托批量 |
| 两表互通 | `addItem()` / `removeItem()` 同事务 upsert V18 投影 + 追加流水 |
| 字段错位 | V25 加 `owner_name` 列，`setSecurity()` 改写入 |
| 措辞清理 | 所有 `TODO(Q27)` / 「待业务书面定源」改为「按 ADR-Q27 裁决」 |

---

## 6. 端到端验证与补修（2026-11-22 09:45）

裁决落地后，冒烟脚本新增第 ㉓ 小节共 11 项断言，从网关端到端验穿上述结论，
并暴露出 3 个「冒烟从未覆盖这些端点 = 假绿」的真实缺陷，均已修复：

| 缺陷 | 现象 | 修复 |
|---|---|---|
| Controller 未注册 | `collateral-reg-service` 全部端点 500：`NoResourceFoundException: No static resource ...` | `CollateralRegController` 补 `@RestController` 交给组件扫描；启动类 `@Import` 移除该类，避免与扫描重复注册造成映射冲突（与 risk / release 一致） |
| V10 权威源漏挂车 | ④ 设定担保后 `vehicle_units` 为 REGISTERED，门禁直读 V10 却返回 NONE | `setSecurity()` 幂等分支复用既有登记批次时，调 `CollateralLedgerApplicationService.attachVinToExistingBatch()` 把本次 VIN 写入 `collateral_registration_items`（仓储 `ON CONFLICT (batch_id, vin) DO NOTHING` 保证幂等），同事务同步 V18 投影 |
| 参数错误被报成系统故障 | 批量快照混用不同 VIN 返回 500 `SYS_001` | `GlobalExceptionHandler` 补 `@ExceptionHandler(IllegalArgumentException.class)` → 400 `VALID_001` |

**验证硬事实**：`mvn -B clean package` 15/15 SUCCESS；`bash run-microservices.sh smoke` EXIT 0、
**195 项 PASS / 0 FAIL**；Flyway 实证 `Successfully validated 25 migrations`、
`Current version of schema "public": 25`；10 服务全就绪并自动清理。
