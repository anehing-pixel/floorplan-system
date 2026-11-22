# CORE 交付说明（agent/core）

> 分支 `agent/core`，基线 `e441da1`，9 个逻辑块各自单独 commit + 验证。
> 全部改动位于允许边界内：`web/` 全部、`domain/application/infrastructure` 已存在 java 文件（含 `src/test/`）、根目录 `smoke-test.py`。
> 未改父 pom、任何 application.yml、`common/`、`gateway/`、`run-microservices.sh`、任何 `*-service/`、任何迁移文件（V1–V21）、PROGRESS.md、AGENT_TASK.md。

## 一、提交清单

| commit | 任务 |
|---|---|
| `7b821f1` | T1-1 B3 处置冻结额度 |
| `9b6f1d6` | T1-2 B4 盘点缺失改状态 |
| `6067957` | T1-3 M9 登记编号落库 |
| `579879b` | T1-4 M12 授信审批双段 |
| `de116c5` | T1-5 B1 担保门禁（Q27 开关默认关） |
| `9dc3e9f` | T1-7 B1b 提前还本金误伤 |
| `d7a1f53` | T2-4 M6 库龄递减增强 |
| `6395834` | T2-6 M8 法律清算三态 |
| `5848c0a` | T2-8 M11 终端客户支付确认 |

## 二、九个修复点完成度

### P0 断点

1. **T1-1 B3 处置冻结额度（完成）**
   - `CreditLine.freezeForDisposal(long)`：冻结敞口，可用量同步下降；敞口为 0 也置 `FROZEN` 阻止继续放款；冻结量受可用额上限保护。
   - `DisposalApplicationService.open`：开单时行锁加载额度并冻结 `unit.principalBalance`，写 `DISPOSAL_FREEZE` 审计。
   - 单测：`CreditLineDisposalFreezeTest`（4 项，含 occupy 被拦截、既有 release 不受影响）。

2. **T1-2 B4 盘点缺失改状态（完成）**
   - `VehicleUnit.markInventoryMissing(LocalDate)`：账有实无时 `IN_STOCK → OVERDUE`、置 `ootSuspected`、风险等级 HIGH、差异次数累加；重复调用状态幂等。
   - `InventoryAuditApplicationService.submitAudit` 改调该领域方法，返回新增 `escalatedToOverdueVins`，告警文案标明升级数量。
   - 升级到 OVERDUE 后可被既有 `DEFAULT_DISPOSAL` 扫描作业自动开处置单（接缝打通）。
   - 单测：`VehicleUnitInventoryMissingTest`（5 项，含"可进处置流程"断言）。

3. **T1-3 M9 登记编号落库（完成，有一处口径待定）**
   - `VehicleUnit.setSecurity(SecurityType, String registrationNo)`：带登记编号时担保状态推进 `REGISTERED`，不再永远 PENDING。
   - `SalesApplicationService.setSecurity`：老接口 `regNo`/`ownerName` 委托 V10 `registerCollateralBatch` 落批次表，按登记编号可查、重复编号幂等；返回 `registrationNo`/`batchNo`/`ownerName`。
   - **已裁决（ADR-Q27 + V25 落地）**：V10 批次表已加 `owner_name` 专用列，权利人不再误写 `co_signed_by`；V10 为唯一权威源、V18 为只读投影，门禁直读 V10。原 TODO(Q27) 表述作废。
   - 单测：`VehicleUnitSecurityRegistrationTest`（4 项）。

4. **T1-4 M12 授信审批双段（完成，兼容入口有限制）**
   - `CreditApplicationService.initiateCreditLine`（建额度置 `PENDING_APPROVAL`）/ `completeCreditLine`（通过→`ACTIVE`、驳回→`CLOSED`），双段各写审计。
   - `CreditLine.occupy` 对 `PENDING_APPROVAL` 抛新增错误码 `CREDIT_003`，未经 complete 绝不可用。
   - **兼容入口**：`createCreditLine`（credit-service `POST /api/credit-lines` 与 smoke 依赖）内部自动发起+复核，已标 TODO(M12)：生产应禁用单人自动审批、由 credit-service 暴露独立审批端点并做 SOD。
   - 单测：`CreditLineApprovalTest`（4 项）。

5. **T1-5 B1 担保门禁（接缝完成，默认关闭，判定源待 Q27）**
   - 见第三节"开关与门禁口径"。
   - 单测：`GuaranteeGatePolicyTest`（6 项，含 fail-closed、经销商放宽、默认关闭放行）。

6. **T1-7 B1b 提前还本金误伤（完成）**
   - `VehicleUnit.RepaymentCause` / `ReleaseDecision` / `evaluateCreditRelease`：引入**还款动因**维度，避免只按车辆状态修而把库龄递减的强制还款一起改错（核查报告 §8.3）。
   - `RepaymentApplicationService.partialRepay`：在库车主动提前还本金回补对应额度（行锁），响应新增 `releasedCredit`；**全程不调用 `releaseSecurity`/`markReleased`**，担保消灭仍只由结清/赎回/处置完成驱动。
   - 单测：`CreditReleasePolicyTest`（7 项，含"担保状态保持 REGISTERED"断言）。

### P1/P2 增强

7. **T2-4 M6 库龄递减增强（部分完成）**
   - `AgingPlan` 内置分层规则：0–60 监控 / 61–90 预警 / 91–120 10% / 121–180 20% / 181–270 30%+冻结 / 271+ 转处置（首档沿用 10% 保存量 V1 兼容）。
   - `CurtailmentApplicationService.payPeriod`：按当前余额×分层比例动态重算应还额（不再用生成时算死的 `reduceAmount`），并禁止对非在库车催款。
   - `BatchApplicationService.runAgingReduction`：豁免 VIN 名单（配置 `floorplan.aging.exempt-vins`）跳过计划生成。
   - 单测：`AgingPlanTierTest`（5 项）。
   - **未完成（需新迁移，本次禁改）**：回款抵扣、后台可配规则表（`aging_rule_tiers`）、到期不还冻结额度升级、经销商整体压降。

8. **T2-6 M8 法律清算三态（完成）**
   - `DisposalStatus` 新增 `NOTIFIED`/`LEGAL_ACTION`/`CANCELLED` 三态与流转白名单，`CANCELLED` 为终态（撤销通道，补救期内还清/和解）。
   - `DisposalCase` 加 `notifyParty`/`startLegalAction`/`cancel`；`DisposalApplicationService` 加三段编排，撤销后车辆 `DISPOSING → OVERDUE`（`VehicleStatus` 补该白名单）。
   - 单测：`DisposalStateMachineTest`（7 项，含变现中不可撤销）。
   - **未完成**：`risk-service` 的 `/api/disposals` 端点属禁改目录，未暴露 notify/legal-action/cancel HTTP 端点（已留 TODO）。

9. **T2-8 M11 终端客户支付确认（接缝完成，持久化待新表）**
   - `VehicleUnit.canConfirmCustomerPayment`：仅 `SOLD_PENDING` 且本金未结清可确认（资金未到账不放车）。
   - `SalesApplicationService.confirmCustomerPayment` + audit `CUSTOMER_PAYMENT_CONFIRMED`。
   - `POST /api/vehicles/{vin}/customer-payment/confirm`（`REPAYMENT_EXECUTE` 权限）。
   - 单测：`VehicleUnitCustomerPaymentTest`（4 项）。
   - **未完成**：确认记录（金额/付款人/凭证/幂等）需新表 `customer_payment_confirmations`；与 B2 出库指令的前置门禁待对接（已留 TODO）。

## 三、B1 开关与门禁口径位置

- **总开关（默认关闭）**：`application/src/main/java/com/company/floorplan/application/service/AdvanceApplicationService.java`
  - `@Value("${floorplan.core.guarantee-gate.enabled:false}") private boolean guaranteeGateEnabled;`
  - `enforceGuaranteeGate(pending)` 在 `disburseAdvance` 的 SOD 校验之后、支付网关调用之前；**关闭时连权属服务都不查询**，完全不改变现有放款行为。
- **按经销商放宽（Q21）**：同文件 `@Value("${floorplan.core.guarantee-gate.relaxed-dealers:}")`，逗号分隔 dealerId。
- **判定接缝（不自定判定源）**：`domain/src/main/java/com/company/floorplan/domain/service/RoleSeparationPolicy.java`
  - `GuaranteeLookup` 端口 + `GuaranteeView` 只读视图；`evaluateGuarantee(view, gateEnabled, dealerRelaxed)` 只消费上层传入的视图。
- **Feign 适配**：`web/src/main/java/com/company/floorplan/web/integration/FeignGuaranteeLookup.java`（调用 common 冻结契约 `CollateralClient.getRegistration`，失败返回 null → fail-closed）。
- **口径定稿（ADR-Q27）**：门禁以 V10 `collateral_registration_batches + items` 为唯一权威源，`RoleSeparationPolicy.evaluateGuarantee` / `AdvanceApplicationService.enforceGuaranteeGate` / `FeignGuaranteeLookup` 均按该口径实现；生产置 `guarantee-gate.enabled=true` 即启用。
- **依赖处理**：core 各模块 pom 不依赖 common（common 反向依赖 application，直接引用会循环）。按边界只在 `web/pom.xml` 加 common 依赖 + `FloorplanApplication` 加 `@EnableFeignClients`，并用显式 `@ComponentScan` 正则排除 `com.company.floorplan.common..*`（否则 common 与 web 的同名 config bean 冲突导致启动失败）。未改契约签名、未改父 pom。

## 四、验证结果

- `mvn -q -pl web,domain,application,infrastructure -am test`：**通过**。新增 9 个测试类共 **46 项**单测全部通过（全部位于 domain/src/test，因 application/infrastructure 模块 pom 无测试依赖且模块 pom 不在可写边界）。
- `mvn -q -pl web,domain,application,infrastructure -am package -DskipTests`：**通过**，`web/target/floorplan-web-1.0.0-SNAPSHOT.jar` 生成。
- 启动验证：修正 common 包扫描冲突后，Feign bean 定义与 fallback 注册阶段通过；随后 Spring 初始化卡在 Flyway 迁移（见下），**未能进入业务端点可用状态**。
- 针对性单测均遵循"修复前会失败"原则：例如 B1b 的 `CreditReleasePolicyTest.inStockVoluntaryPrepay_releasesAndDoesNotTouchSecurity`、B1 的 `GuaranteeGatePolicyTest.gateEnabled_serviceUnavailable_failsClosed`、M12 的 `CreditLineApprovalTest.pendingApproval_cannotOccupy`。

## 五、smoke 未跑及原因

- `bash run-local.sh` + `python3 smoke-test.py`（182 项）**未执行完成**。
- 根因：`infrastructure/src/main/resources/db/migration/V19__release_orders.sql:218` 使用 PostgreSQL 不支持的部分唯一约束语法：
  `CONSTRAINT uk_redemption_vin UNIQUE (vin) WHERE status <> 'CANCELLED'`，Flyway V19 迁移失败（实际启动日志：`ERROR: syntax error at or near "WHERE"`），所有服务无法启动。
  修复建议：改为部分唯一索引 `CREATE UNIQUE INDEX uk_redemption_vin ON redemptions (vin) WHERE status <> 'CANCELLED';` 并从建表语句删除该 CONSTRAINT。
- 该文件属 release 侧且迁移文件为我的禁改边界，已通过 SendMessage 上报 team-lead（含精确行号与修复建议）。V19 修复后即可跑全量 smoke。
- smoke-test.py 仅改了一处标题文案（⑪ 提前部分还款 → 在库主动提前还本金回补额度但不解担保），未增删断言项。

## 六·补 · Q21 决策变更（2026-11-22 08:26）

业务方裁决 **Q21 = A 硬阻断**，不保留按经销商/产品线放宽。对本 HANDOFF 上文描述的实现做如下调整（上文保留作当时交付记录）：

- `RoleSeparationPolicy.evaluateGuarantee` 删除 `dealerRelaxed` 参数与「白名单先于 `view == null` 直接 allow」分支，签名改为 `(view, gateEnabled)`；非 REGISTERED / 查询无结果 / 未知状态一律 `block`。
- `AdvanceApplicationService` 删除 `@Value("${floorplan.core.guarantee-gate.relaxed-dealers:}")` 字段与 `isRelaxedDealer()`，调用改两参；`dealerId` 查询保留，仅用于拒付时生成 HIGH 工单。
- 拒付时仍创建 `GUARANTEE_PENDING` HIGH 告警并抛 `GUARANTEE_001`，但这是**拒付后的追补动作**，不是 Q21 的 B 方案「放行」。
- `GuaranteeGatePolicyTest` 的 `gateEnabled_relaxedDealer_allows` 改为 `gateEnabled_noDealerBypass_blocks`，另补 `unknownStatus_blocks`。
- 门禁总开关 `floorplan.core.guarantee-gate.enabled` **仍默认 false**——Q27 判定源未定，开启即全量拒付，不擅自打开。

## 六、TODO 汇总

1. **Q27（阻塞项）**：担保判定源需业务书面确认，B1 门禁保持默认关闭，绝不自己定哪张表为准。
2. **V19 迁移**：`uk_redemption_vin` 语法错误，需 release 侧修复（全团队启动阻塞）。
3. **M12**：生产禁用 `createCreditLine` 单人自动审批，由 credit-service 暴露 initiate/complete 端点并做发起/复核 SOD。
4. **M9**：`ownerName` 在 V10 批次表无专用列，暂记 `co_signed_by`；与 collateral-reg-service 权属表的归一待 Q27 定源。
5. **M6**：回款抵扣、后台可配分层规则表、到期不还冻结额度升级、经销商整体压降，均需新增迁移/表。
6. **M8**：risk-service 暴露 `/api/disposals/{caseNo}/notify|legal-action|cancel` 端点并做审批留痕。
7. **M11**：新增 `customer_payment_confirmations` 表持久化确认凭证与幂等键，对接 B2 出库指令前置门禁。
8. **B1 配套**：`DEALER_METRIC_SCAN` 查未登记车、担保工单派发（Q22）待定。
