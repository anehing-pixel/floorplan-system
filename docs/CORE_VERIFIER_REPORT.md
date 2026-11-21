# CORE 独立验收报告（异构模型只读审计）

- 审计对象：`agent/core` 交付（`agent/core-done` = `8a6c363`），已合并进 master 的 `dba931d`
- 审计角色：CORE 独立验收 verifier（只读），除本报告外未修改任何文件
- 审计日期：2026-11-22
- 审计范围：以**设计性审查（B 层）**为主；流程性检查按 PM 要求不重复执行，仅复核结论
- **复核时点补充【实证】**：审计期间 PM 并行推进了主仓库，HEAD 已由 `dba931d` 前进到 `c9ed24d`（见 A-1 / A-4）。本报告的审计对象**仍是交付 tag `8a6c363` / 合并提交 `dba931d` 的代码内容**，并已用 `git show dba931d:<path>` 复核关键结论未被后续改动推翻（见 A-4 / B-9）。

## 证据口径声明（PM 对"禁止编造"极敏感，先说清楚）

本报告的结论严格分三类标注，不混用：

1. **【实证】**：我用只读命令（`git rev-parse` / `git rev-list` / `git diff --name-only` / `git show` / `git status` / Grep）或 Read 工具实际读到文件行号验证过的。
2. **【静态推演】**：基于已读到的代码路径做逻辑推演，**未启动数据库 / 未跑运行时复现 / 未构造并发压测**。凡属此类均显式标注，不写成既成事实。
3. **【PM 提供，未复跑】**：PM 已用命令实证过的流程性结论，我保持只读、不生成构建产物，**未独立重跑**，按原文引用并注明未复跑。

另：下列路径均为**主仓库根目录相对路径**。为节省篇幅，包目录做如下约定（`【实证】` 均已逐文件 Read 确认存在）：

- `application/…/Xxx.java` = `application/src/main/java/com/company/floorplan/application/service/Xxx.java`
- `domain/…/Xxx.java` = `domain/src/main/java/com/company/floorplan/domain/model/Xxx.java`；`domain/service/…` 在 `.../domain/service/`
- `domain/test/…/XxxTest.java` = `domain/src/test/java/com/company/floorplan/domain/...`
- `infrastructure/…/Vxx.sql` = `infrastructure/src/main/resources/db/migration/Vxx.sql`；`…/persistence/Xxx.java` 在 `.../infrastructure/persistence/`
- `web/…` = `web/src/main/java/com/company/floorplan/web/...`（`web/HANDOFF.md` 例外，为 `web/HANDOFF.md`）
- `common/…/CollateralClient.java` = `common/src/main/java/com/company/floorplan/common/contract/CollateralClient.java`
- `credit-service/…/CreditLineController.java` = `credit-service/src/main/java/com/company/floorplan/credit/controller/CreditLineController.java`

---

# A 流程复核

## A-1 tag / 基线 / 提交数 ——【实证】复核通过

我在主仓库执行了只读 git 命令，输出如下（逐字）：

- `git rev-parse agent/core-done` → `8a6c363b19986b529571586ec3b9817b511b640e`；`git rev-parse agent/core-done^{commit}` 同值 → **tag 为 lightweight，指向 `8a6c363`**，与 PM 结论一致。
- `git rev-parse e441da1` → `e441da1bb23695acb4c3c29a94eb6ffda0b5e5e8`，基线存在。
- `git rev-list --count e441da1..8a6c363` → **10**，与 PM「10 commits」一致（HANDOFF 列 9 个修复 commit + 1 个 HANDOFF commit = 10）。
- `git show -s --format='%H | %P | %s' dba931d` →
  `dba931d2bd9989a9fff2de73be54a6163c577629 | e976517bd176f6fac051a3381477ca503781e391 8a6c363b19986b529571586ec3b9817b511b640e | merge: 合并 agent/core（9 个断点修复，reasoning verifier 判定可合并）`
- `git merge-base --is-ancestor 8a6c363 HEAD` → 退出 0（`YES`），交付确为 HEAD 祖先。

**结论**：tag、基线、提交数、合并结构全部核实无误。**另确认 PM 所述的流程错误属实**：`dba931d` 的 commit message 确实含「reasoning verifier 判定可合并」，而我此前从未出具过验收结论（本次为首次）。

**复核时点更新【实证】**：报告写完前再次执行 `git log -1 --oneline` → `c9ed24d docs: 更正 verifier 流程瑕疵、如实记录 CORE 合并与 smoke 两次失败`，即 PM **已自行追加更正提交**（正是 A-1 原建议的动作）。该提交由 PM 操作、**非我执行**；我不代为修改 git 历史、不 amend、不重写 commit message。C 节流程建议第 1 条据此更新为「已完成」。

## A-2 交付写入集零越界 ——【实证】复核通过，但表述需一处微调

`git diff --name-only e441da1 8a6c363` 输出 33 个文件，全部为：

- `application/…/` 下 8 个 java（`AdvanceApplicationService`、`BatchApplicationService`、`CreditApplicationService`、`CurtailmentApplicationService`、`DisposalApplicationService`、`InventoryAuditApplicationService`、`RepaymentApplicationService`、`SalesApplicationService`）
- `domain/…/` 下 main java 8 个 + `domain/test/` 下 9 个测试类
- `web/` 下：`web/HANDOFF.md`、`web/pom.xml`、5 个 java（`FloorplanApplication`、`GuaranteeFeignConfig`、`VehicleController`、`ApiRequests`、`FeignGuaranteeLookup`）
- 根目录 `smoke-test.py`

**未出现**：父 `pom.xml`、任何 `application.yml`、`common/`、`gateway/`、`*-service/`、任何迁移文件（V1–V21）、`PROGRESS.md`。与 PM「零越界」结论一致。

**需微调的表述**：PM 写「只改 `web/**` + `domain/**` + `application/**` 的 java + `smoke-test.py`」，实际还包含 `web/pom.xml`（**web 模块 pom，不是父 pom**）与 `web/HANDOFF.md`（文档）。二者仍落在 `web/**` 边界内，`web/pom.xml` 的改动与 `web/HANDOFF.md:89` 自述「只在 `web/pom.xml` 加 common 依赖」吻合，故**「未碰父 pom」成立、零越界成立**，只是"java"一词不涵盖这两个文件。这不是缺陷，仅为措辞精确化。

## A-3 构建 / 单测 / Flyway ——【PM 提供，未复跑】

- 合并后主仓库 `mvn -q -B clean package` EXIT 0、全仓 163 单测全绿（domain 46 项为 CORE 新增）：**PM 实证，我未独立重跑**（保持只读，不生成 `target/` 构建产物）。
- Flyway V1–V21 在临时空库逐文件单事务 21/21 跑通：**PM 实证，我未独立复跑**。
- `wt-core` 工作区干净：**PM 实证，我未独立复跑**（我仅操作主仓库）。

我独立做的旁证【实证】：`domain/src/test` 下确有且仅有 9 个 CORE 新增测试类（`AgingPlanTierTest`、`CreditLineApprovalTest`、`CreditLineDisposalFreezeTest`、`CreditReleasePolicyTest`、`DisposalStateMachineTest`、`VehicleUnitCustomerPaymentTest`、`VehicleUnitInventoryMissingTest`、`VehicleUnitSecurityRegistrationTest`、`GuaranteeGatePolicyTest`），与 `web/HANDOFF.md:93` 自述「9 个测试类共 46 项」一致；**46 这个数字我未逐项计数，标注「未验证」**。

## A-4 主仓库工作区状态 ——【实证，含两次快照差异】

我在**审计开始时**与**报告写完前**各取了一次 `git status --short`，两次结果不同，如实记录（避免被误认为是我改了代码）：

- **快照 1（审计开始时）**：`M PROGRESS.md`、`?? .workbuddy/memory/2026-11-22.md`、`?? ai-service/`
- **快照 2（报告写完前）**：`D .microservice-pids/{auth,bank,credit,gateway,risk,web}-service.pid`（6 个删除）、`M web/pom.xml`、`M web/src/main/java/com/company/floorplan/web/integration/FeignGuaranteeLookup.java`、`?? .workbuddy/memory/2026-11-22.md`、`?? docs/CORE_VERIFIER_REPORT.md`（本报告）

**核实结论【实证】**：差异来自 PM 在我审计期间的并行操作，与我无关 —— `git log -1 --oneline` 显示 HEAD 已前进到 `c9ed24d`；`git diff` 显示两处改动是 PM 的**启动期集成修复**：`web/pom.xml` 新增 `spring-cloud-starter-loadbalancer`（注释写明「2026-11-22 PM 集成修复」，解决 `@EnableFeignClients` 无 LoadBalancerClient 导致启动失败），`FeignGuaranteeLookup.java` 去掉匹配不上 bean 的 `@Qualifier("collateral-reg-service")` 改为按类型注入。6 个 `.pid` 删除应为服务停止产物（`未验证` 其来源）。

**对审计结论的影响（重要）**：

1. 我全程**只读 + 仅 Write 本报告**，未执行 `commit` / `merge` / `checkout` / `add` / `stash` / `reset`，未改任何 java / pom / 配置。上述改动与 HEAD 前进均非我操作。
2. 我的结论以**交付版本**为准，已用 `git show dba931d:web/src/main/java/com/company/floorplan/web/integration/FeignGuaranteeLookup.java | grep -n "Interceptor\|Authorization\|RequestContext"` 复核：输出为空（无匹配），即**交付版确实缺 JWT RequestInterceptor**，B-9 结论成立、未被后续改动推翻。
3. PM 的这两处修复解决的是「Feign 启动期 bean 注入 / loadbalancer 缺失」，**与 B-9 指出的「缺 JWT 透传 → 门禁开启后下游 401 → fail-closed 全量拒付」是两件不同的事**：当前工作区版 `FeignGuaranteeLookup.java` 的 diff 只改了构造器注入，仍未引入 `RequestInterceptor`；`GuaranteeFeignConfig.java` 未出现在改动列表中，仍只注册 fallback bean。**B-9 结论保持不变**，请勿把"修了 @Qualifier"误当作"补了 JWT 门禁"。
4. `PROGRESS.md` 的 `M` 与 `?? ai-service/` 在快照 2 中消失，推测随 `c9ed24d` 一并处理（`未验证` 具体去向），不影响本报告。

---

# B 设计性发现

逐条给出「文件:行号 / 是否属实 / 阻塞或非阻塞 / 理由」。PM 抽查的 3 条列 B-1～B-3，我独立检查的项列 B-4～B-9，额外独立发现列 B-10。

## B-1 ownerName 写进 co_signed_by（PM ①）——【实证】属实；**主体非阻塞，幂等覆盖分支为必改**

**位置**
- `application/…/SalesApplicationService.java:165-170`：`// TODO(Q27): ownerName（担保权人）在 V10 批次表无专用列，暂记 co_signed_by`，第 168 行 `b.setCoSignedBy(ownerName);`
- `infrastructure/…/V10__collateral_ledger.sql:156`：`co_signed_by VARCHAR(128), -- 共同申请对方（登记义务人＝经销商）`；上下文 `:144-153` 为抹消登记「双方共同申请」字段组
- `common/…/CollateralClient.java:25`：`private String ownerName; // 权利人`；`domain/service/RoleSeparationPolicy.java:196` 的 `GuaranteeView` 同字段语义

**是否属实：属实。** 三点均经文件核对：

1. **语义相反**：`co_signed_by` 注释明确是「登记义务人＝经销商」，`ownerName` 注释明确是「权利人」（担保权人/金融公司），二者是法务省登记中法律主体相对的两方，不是同一列。
2. **该列全仓唯一写入点就是这里**：Grep `setCoSignedBy|getCoSignedBy` 全仓结果 —— 写入仅 `SalesApplicationService.java:168`；`application/…/CollateralLedgerApplicationService.java:322-334` 的 `registerCollateralBatch` 签名 13 个参数中**无 co_signed_by**，`:351-371` 建批次时也**未调用** `setCoSignedBy`；读写持久化在 `infrastructure/…/persistence/JdbcCollateralRegistrationBatchRepository.java:134,144,154,163`；领域对象 getter/setter 在 `domain/…/CollateralRegistrationBatch.java:372-378`。即：这一列**从来不会被填入正确的经销商名，只会被填入担保权人名**，`SalesApplicationService:168` 是唯一数据来源。
3. **幂等分支确实覆盖他单批次**：`SalesApplicationService.java:153-170`，`batchRepository.findByRegistrationNo(regNo)` 命中已存在批次时（`:154-157`），仍无条件执行 `findById(batchId).ifPresent(b -> { b.setCoSignedBy(ownerName); batchRepository.save(b); })`（`:167-170`）。而 `CollateralLedgerApplicationService.java:344-347` 对重复 `registrationNo` 是抛 `CONFLICT_001` 的，说明该幂等分支正是「复用他单已存在批次」的路径 —— 复用时改写其 `co_signed_by` 属实。

**判定与理由**：**主体（写错列）非阻塞，可记为技术债（Q27）；「幂等分支无条件覆盖」属必改（建议随本批一起修）。**

- 不资损：不参与放款金额计算，B1 门禁默认关闭（见 B-9），该列仅用于抹消登记留痕。
- 不不可逆：属落库数据语义污染，可通过数据订正回退。
- 不是核心流程不可用：`setSecurity` 主流程（车辆担保状态推进 REGISTERED）不依赖该列。
- 不是测试失效：无对应单测（9 个新增测试类均无覆盖），不构成"假绿灯"。
- 已知且有意：`web/HANDOFF.md:39,112,109` 与 TODO(Q27) 明示该接缝；V10 迁移文件禁改（HANDOFF:105），无法新增专用列是**有意的约束**而非疏忽 —— 因此我不把"写错列"升级为阻塞。
- 但**幂等覆盖是纯 bug**：不同 VIN / 不同 ownerName 复用同一 regNo 时会反复改写已有批次，且把本应为空的「共同申请对方」列污染。改动面极小（判空 / 仅新建分支写 / 改为单列留痕），应随本批修掉。

## B-2 处置撤销 / 结案未配对解冻额度（PM ②）——【实证】属实；**阻塞，必须现在补修**

**位置**
- `application/…/DisposalApplicationService.java:88-100`：`open()` 中 `line.freezeForDisposal(exposure)`（`:95`）+ `save`（`:96`）
- 同文件 `:161-173`（`cancel()`）、`:183-218`（`close()`）
- `domain/…/CreditLine.java:163-171`（`freezeForDisposal`）、`:179-185`（`unfreeze`）、`:75-91`（`occupy`）
- `infrastructure/…/persistence/JdbcCreditLineRepository.java:52`：`SELECT * FROM credit_lines WHERE id = ? FOR UPDATE`

**是否属实：属实。** Grep `unfreeze(` 全仓代码调用点仅 4 处：`application/…/AnnualReviewApplicationService.java:89`、`application/…/CreditApplicationService.java:118`（service 方法定义）与 `:121`（内部调用）、`domain/…/CreditLine.java:179`（定义），以及 `credit-service/…/CreditLineController.java:71`（REST 端点）。`DisposalApplicationService` 中：`cancel()` 只做 `c.cancel()` + 车辆 `cancelDisposal()` + 审计；`close()` 只做 `c.close()` + `unit.close()` + `markDisposed/markReleased` + 审计 —— **两方法内既无 `unfreeze` 调用，也完全没有用到注入的 `creditLineRepository`**（`close()` 全文未出现该字段）。与 PM 结论一致。

**后果【静态推演，代码路径已实证】**：`open():95` 调 `freezeForDisposal(exposure)`，该方法（`CreditLine.java:163-171`）对 `exposure>0` 走 `freeze(exposure)`（`:120-124` 末尾 `this.status = "FROZEN"`），对 `exposure==0` 也直接置 FROZEN（`:168`）。冻结的是**整条 creditLine 的状态**，而 `occupy():80-82` 对非 ACTIVE 一律抛 `CREDIT_002` —— 因此该额度线下**其他正常在库车辆的放款一并被阻断**，且处置撤销（客户已补救还清/和解）后不恢复、结案后也不恢复。

**非绝对不可逆，但补修条件不具备**：
- 存在人工端点 `credit-service/…/CreditLineController.java:65-73` 的 `POST /api/credit-lines/{id}/unfreeze`（`@RequirePermission(ANNUAL_REVIEW)`）→ 所以是"可人工干预"，不是"永久锁死"；
- 但处置闭环**自身无自动配对**，且 `domain/…/DisposalCase.java:25-62` **没有持久化"本次冻结增量"字段**；`open():97-99` 审计写的是 `frozenAmount=frozenTotal`，其中 `frozenTotal` 是 `freezeForDisposal` 返回的**累计冻结额**（`CreditLine.java:170` `return this.frozenAmount`），不是本次增量 → 即便后续想补解冻，也没有精确回退依据；
- `freezeForDisposal(0)` 只置 FROZEN 不加 `frozenAmount`（`:166-169`），"冻结状态"与"冻结金额"脱钩，进一步使按金额解冻不可靠。

**判定与理由**：**阻塞，必须现在补修。** 按 PM 给的四维判据：资损=否（这是**过度冻结**，保守方向，不会多放钱）；不可逆=部分（有人工端点，但无自动配对、无精确冻结量记录）；**核心流程不可用=是**（该额度线其他正常车放款被阻断且不恢复）；测试失效=否。**命中「核心流程不可用」**，且与 B-3 耦合后形成资损通道（见 B-3），故必须与 B-3 同批修复。

## B-3 DISPOSING 被当成"已处置 / 担保消灭"全额回补额度（PM ③）——【实证】属实，链路完整可达；**阻塞，必须现在补修**

**位置**
- `domain/…/VehicleUnit.java:585-588`：`if (unit.getStatus() == VehicleStatus.DISPOSING || unit.getStatus() == VehicleStatus.CLOSED) return ReleaseDecision.full(principalPaid, "车辆已处置，担保消灭，本金敞口清零");`
- `application/…/RepaymentApplicationService.java:329-340`（`partialRepay` 前置校验）、`:345-346`（`resolveRepaymentCause` + `evaluateCreditRelease`）、`:355-360`（`line.release(released)`）、`:386-391`（`resolveRepaymentCause`）
- `domain/…/VehicleStatus.java:79-80`：`DISPOSING` 注释「处置中：车辆回收、残值处置、损失核算流程**进行中**」；`:141` 白名单 `DISPOSING → CLOSED || OVERDUE`

**是否属实：属实，且从 HTTP 用例到额度写入的链路完整可达**（逐段实证）：

1. `partialRepay():331-333` **只拦截 `SETTLED` / `CLOSED`**，未拦 `DISPOSING`；`:334-340` 仅校验金额 → DISPOSING 车可进入。
2. `resolveRepaymentCause():386-391` 只对 `OVERDUE` 返回 `OVERDUE_MAKEUP`，**其余一律返回 `VOLUNTARY_PREPAY`** → DISPOSING 得到"自愿提前还本金"动因。
3. `evaluateCreditRelease():575-584` 的 C1（`principalPaid>0`）与动因分支（AGING_REDUCTION / OVERDUE_MAKEUP）均不拦截；随即命中 `:586` → `ReleaseDecision.full(principalPaid, ...)`。
4. `partialRepay():355-360` `decision.release()` 为真 → `findByIdForUpdate` 行锁下 `line.release(released)`（`:359`），**额度被实际回补**。

**语义自相矛盾【实证】**：同一方法 Javadoc `VehicleUnit.java:562-565` 明写「**不误触发担保消灭**：本方法只返回是否回补额度，绝不改变 securityStatus；担保的解除仍只由结清/赎回/**处置完成**用例驱动」，但 `:587` 却以「担保消灭」为由对**处置进行中**全额回补；而担保真正解除（`releaseSecurity()`，`:625-630`）与车辆 `close()`（`:477-482`）在 DISPOSING 阶段都未发生，`DisposalApplicationService.close():207-208` 才调 `markReleased`。即"处置进行中"被当成"担保已消灭"。

**资损评估 —— 如实不夸大**：回补走 `CreditLine.release():100-106`，只减 `usedAmount`、**不检查 status**，故 `available()` 上升；但 `open()` 已把 status 置 `FROZEN`，`occupy():80-82` 会抛 `CREDIT_002`。因此**当前默认路径下资金并未被实际多放出去，属"潜在资损通道"，不是已发生的资损**。但三条实证事实使其不能放任：

- `domain/test/…/CreditLineDisposalFreezeTest.java:66-76` 明确把「FROZEN 下 `release()` 仍减 usedAmount、冻结不破坏既有回补路径」钉死为期望行为 → 回补在冻结态确实生效，唯一的挡板只有 status；
- 一旦按 B-2 建议补上解冻，或运维经 `CreditLineController.java:65-73` 人工解冻使 `frozenAmount==0` → `CreditLine.java:182-184` 把 status 复位 `ACTIVE`，**虚增的 `available()` 立即变成可放款额度，闭环成实际资损**；
- 因此 **B-2 与 B-3 必须同批修**：单独修 B-2（加解冻）会把 B-3 从"潜在"直接变成"实际"。这是本次审计最重要的交叉结论。

**测试失效【实证】**：`domain/test/…/CreditReleasePolicyTest.java:78-86` 的 `disposed_fullRelease` 用 `unit(VehicleStatus.DISPOSING, SecurityStatus.REGISTERED)` 断言 `decision.release() == true` 且 `amount == PAID`，**把 B-3 的错误行为固化成绿灯期望**。修复 B-3 时该用例会变红；换言之，当前「163 单测全绿」**不能作为该判定正确的验收证据**。`web/HANDOFF.md:96` 声称「针对性单测均遵循修复前会失败原则」，就本例而言该原则把错误行为也钉成了"修复前失败→现在通过"的绿灯。

**判定与理由**：**阻塞，必须现在补修。** 四维判据：**资损=是（潜在，依赖解冻；方向为"多放钱"）**、不可逆=否、**核心流程不可用=否**、**测试失效=是**（假绿灯）。命中两条，且与 B-2 强耦合，故列为首要阻塞项。

## B-4 `freezeForDisposal` / `open` 并发安全性 ——【实证 + 静态推演】非阻塞，记技术债

**实证部分**
- 行锁确实存在：`JdbcCreditLineRepository.java:52` = `SELECT * FROM credit_lines WHERE id = ? FOR UPDATE`；`infrastructure/…/persistence/JdbcVehicleUnitRepository.java:161` = `SELECT * FROM vehicle_units WHERE vin = ? FOR UPDATE`。
- `open():62` 先 `findByVinForUpdate`，`:92` 再 `findByIdForUpdate`；与 `AdvanceApplicationService.stageAdvance():457-460`、`RepaymentApplicationService.partialRepay():329,356`、`settle():94,125`、`AnnualReviewApplicationService:46,85`、`CreditApplicationService.freeze/completeCreditLine/unfreeze` 一致 —— **额度的读-改-写全部在行锁下，未见无锁裸奔**；锁顺序为 vehicle→creditLine 或仅 creditLine，未发现成环死锁（其余只锁 creditLine 的用例不反向锁 vehicle）。
- 敞口 0 边界：`CreditLine.freezeForDisposal(0)`（`:163-171`）只置 FROZEN、不加 `frozenAmount`，`:157-158` 注释说明了理由；`domain/test/…/CreditLineDisposalFreezeTest.java:42-53` 有覆盖。设计合理，但使"按金额解冻"不可靠（见 B-2）。`open():97-99` 审计把累计值写成 `frozenAmount=`，语义易误导。

**形式上的 check-then-act【实证】**：`open():64-68` 用 `disposalCaseRepository.findLatestByVin(vin)` 判断"是否已有未结案工单"，其实现 `infrastructure/…/persistence/JdbcDisposalCaseRepository.java:75-78` 为 `SELECT * FROM disposal_cases WHERE vin = ? ORDER BY opened_at DESC LIMIT 1`，**无 `FOR UPDATE`**；DB 侧 `infrastructure/…/V2__business_gap_features.sql:39-55` 仅 `case_no VARCHAR(32) UNIQUE` + `idx_disposal_vin` / `idx_disposal_status` 普通索引，**没有「同一 VIN 只能有一笔未结案工单」的唯一约束**；`save()`（`:54-66`）靠 `ON CONFLICT (case_no)` 幂等，而 case_no 每次新生成 UUID，不会冲突。

**静态推演（未运行时复现）**：同一 VIN 的两个并发 `open()` 都会先争第 62 行的 vehicle 行锁而被串行化，在 PostgreSQL 默认 READ COMMITTED 下后到事务的 `findLatestByVin` 能看到先提交工单，从而抛 `DISPOSAL_001` —— 即**同 VIN 重复开单实际上被车辆行锁间接挡住了**。但该保护是隐式不变量（依赖"所有开单入口都先锁 vehicle 行"与隔离级别），且 `findLatestByVin` 只取 `opened_at` 最新一条，若存在多条历史则只检最新。不同 VIN 共享同一 creditLine 的并发由额度行锁串行化，`frozenAmount` 累加属设计使然。

**判定**：**非阻塞**。建议记为技术债：补「同 VIN 未结案」部分唯一索引，或对工单查询加行锁，把隐式保护显式化。**并发场景未做运行时复现，标注「未验证」。**

## B-5 `createCreditLine` 兼容入口绕过 SOD ——【实证】属实；非阻塞，列为上线前必改技术债

**位置**：`application/…/CreditApplicationService.java:98-102`（`createCreditLine` 内部 `initiateCreditLine(...)` + `completeCreditLine(initiated.getId(), true, actor)`，**同一个 `actor`**）；`:48-67` / `:74-89`（双段本身）；`domain/…/CreditLine.java:75-79`（`PENDING_APPROVAL` → `CREDIT_003`）；`credit-service/…/CreditLineController.java:41-52`（`POST /api/credit-lines` 第 49 行直接调 `createCreditLine`）；`web/src/main/resources/static/js/views.js:781`（前端入口）。

**是否属实：属实。** `initiate` 置 `PENDING_APPROVAL`、`complete` 立刻用**同一 actor** approve → 领域层"未经 complete 不可放款"的不变量虽成立，但兼容入口让单人一次 HTTP 调用即可激活可放款额度。对照 `AdvanceApplicationService.java:285,353-354` 对垫款复核/支付调用了 `RoleSeparationPolicy.assertNoSelfApproval`（`domain/service/RoleSeparationPolicy.java:167-176`），**授信链路上未见该校验**。且这不是死代码：`CreditLineController.java:49` 与 `views.js:781` 证明它是生产可达的 `POST /api/credit-lines`。

**判定**：**非阻塞，但列为「上线前必改」技术债**。四维判据：资损=间接（单人可自核自激活额度）、不可逆=否、核心流程不可用=否、测试失效=否。修复需 credit-service 暴露独立 initiate/complete 端点并做发起/复核不同人（跨模块，**超出 CORE 写入边界**），且 `web/HANDOFF.md:45,111` 与 `smoke-test.py` 明确依赖该兼容入口 —— 不是单改 core 能闭环，故不判本次合并阻塞，但**不得带入生产**。

## B-6 AgingPlan 库龄分层边界 ——【实证】未发现问题

`domain/…/AgingPlan.java:157-174`：`TIERS = (0,60) (61,90) (91,120) (121,180) (181,270) (271,-1)`；`tierFor` 判定 `agingDays >= t.fromDays() && (t.toDays() == -1 || agingDays <= t.toDays())`，未命中回落 `TIERS.get(0)`。

逐档验算【实证】：60→0-60、61→61-90、90→61-90、91→91-120、120→91-120、121→121-180、180→121-180、181→181-270、270→181-270、271→271+。**连续、无空洞、无重叠**；`toDays=-1` 表示无上限，与 `:145` 注释一致；负数/异常 agingDays 回落 MONITOR 档，不抛异常。`domain/test/…/AgingPlanTierTest.java:24-36` 覆盖全部边界且期望值为硬编码枚举。

**判定**：符合预期，**非阻塞、无改进项**。

## B-7 DisposalStatus 三态非法跳转 / 终态不可变 ——【实证】代码正确，测试覆盖偏弱；非阻塞

`domain/…/DisposalStatus.java:50-69`（`canTransitTo`）、`:39-42`（`isTerminal`）、`domain/…/DisposalCase.java:67-77`（`transit`）、`:112-133`（`cancel`/`close`）：

- `switch` 的 `default: return false`（`:66-67`）覆盖 `CLOSED_RECOVERED` / `CLOSED_LOSS` / `CANCELLED` → **终态不可再流转** ✓
- `LIQUIDATING` 仅允许 `CLOSED_*`（`:64-65`）→ **变现中不可撤销** ✓
- `OPEN` 不允许直接到 `LEGAL_ACTION` / `LIQUIDATING`、`NOTIFIED` 不允许直接到 `LIQUIDATING`、`REPOSSESSED` 不允许回 `NOTIFIED` ✓
- `transit()` 非法流转抛 `DISPOSAL_002`（`DisposalCase.java:72-75`）✓；`isTerminal()` 被 `DisposalApplicationService.open():65` 用于防重复开单 ✓
- 一处边界（**仅记录，不作为缺陷**）：`DisposalCase.transit():69-71` 在 `status == null` 时自动置 OPEN 再判断，即新建但未 `open()` 的 case 直接 `close()` 会被当 OPEN 允许结案；但应用层 `requireCase():220-223` 从 DB 读取，status 非 null，**实际路径不受影响**。

**测试覆盖**：`domain/test/…/DisposalStateMachineTest.java:66-84` 覆盖终态不可变（`isTerminal()` / `canTransitTo`）与 LIQUIDATING 不可撤销；但**未断言 `transit()` 抛 `DISPOSAL_002`**，也未覆盖 OPEN→LEGAL_ACTION、NOTIFIED→LIQUIDATING、REPOSSESSED→NOTIFIED 等非法方向。

**判定**：代码正确，**非阻塞**；建议补强非法跳转的异常断言（技术债，优先级低）。

## B-8 单测是否循环论证 ——【实证，逐个点名】**未发现严格循环论证**；点名 3 处相关问题

我逐个 Read 了 `domain/src/test` 下全部 9 个 CORE 新增测试类（见 A-3 列名）。**结论：没有发现"用被测方法自己生成期望值再断言自己"的循环论证**（Wave1 同类问题）。9 个类的期望值均为硬编码字面量 / 枚举常量 / 布尔常量；`DisposalStateMachineTest` 与 `VehicleUnitInventoryMissingTest` 中直接断言 `canTransitTo()` / `isTerminal()` 返回值，属于对白名单方法的正常单测，不是自指。

但如实点名 3 处**相关但性质不同**的问题（不把它们包装成"循环论证"以硬凑发现数）：

1. `domain/test/…/CreditReleasePolicyTest.java:78-86` `disposed_fullRelease`：把 `DISPOSING → release=true` 固化为绿灯期望 → 与 B-3 直接冲突，**修复 B-3 必须同步改此用例**。性质是「**测试为错误行为背书 / 测试失效**」，不是循环论证。
2. `domain/test/…/CreditLineDisposalFreezeTest.java:66-76` `frozenLine_stillAllowsExistingRepaymentRelease`：把「FROZEN 下 `release()` 不检查状态」钉死为期望，是 B-3 资损链路的必要环节。同上性质。
3. `domain/test/…/CreditLineDisposalFreezeTest.java:30,36`：用同对象 `available()` 的结果 `before` 做**相对断言**（`assertEquals(before - 200_000L, line.available())`），属弱断言；但同用例 `:34-37` 有 `200_000L` / `"FROZEN"` 等绝对断言兜底，**不构成自证**。

其余 6 个类（`AgingPlanTierTest`、`CreditLineApprovalTest`、`DisposalStateMachineTest`、`VehicleUnitCustomerPaymentTest`、`VehicleUnitInventoryMissingTest`、`VehicleUnitSecurityRegistrationTest`、`GuaranteeGatePolicyTest`）期望值均为独立硬编码，未发现异常。`GuaranteeGatePolicyTest.java:44-54` 的 fail-closed 断言、`:56-59` 的放宽断言均为独立常量，合格。

## B-9 B1 担保门禁：默认关闭 / fail-closed / JWT RequestInterceptor ——【实证】前两项正确；**对 PM「静默降级」措辞做一处修正**

- **默认关闭【实证】**：`application/…/AdvanceApplicationService.java:89` `@Value("${floorplan.core.guarantee-gate.enabled:false}")`；`:388-391` `enforceGuaranteeGate` 在 `!guaranteeGateEnabled` 时**直接 return，连权属服务都不查询**；调用点 `:356` 位于 `disburseAdvance` 的 SOD 校验（`:353-354`）之后、`paymentGateway.pay()`（`:363`）之前。✓
- **异常 / 未知源 fail-closed 拒付【实证】**：`domain/service/RoleSeparationPolicy.java:224-241` —— `view == null` → `block`（`:233-234`）、status 非 `REGISTERED`（含未知值 / NONE / PENDING）→ `block`（`:239-240`）；`web/…/integration/FeignGuaranteeLookup.java:31-40` 对 `vo == null` 与 `catch (RuntimeException)` 均返回 `null`；`common/…/CollateralClient.java:39-45` `Fallback.getRegistration` 返回 `status="NONE"`；`AdvanceApplicationService.java:399-403` blocked → 创建 HIGH 告警 + 抛 `GUARANTEE_001`。**异常与未知源确实一律拒付** ✓，`GuaranteeGatePolicyTest.java:44-54` 有覆盖。
- **缺 JWT RequestInterceptor【实证】**：`web/…/config/GuaranteeFeignConfig.java:15-21` 整个类只注册了 `collateralClientFallback()` 一个 `@Bean`，**没有任何 `RequestInterceptor` / `RequestContextHolder` / `Authorization` 透传**。PM 疑点属实。

**需修正 PM 的措辞（重要，避免误判为资损）**：缺 interceptor 导致的是「门禁开启后 Feign 请求不带 JWT → 下游若校验 JWT 返回 401 → `FeignGuaranteeLookup` catch → `null` → `evaluateGuarantee` block → **拒付**」，或「→ fallback → `NONE` → block → **拒付**」。两条路径都是 **fail-closed 拒付，不是"静默降级放行"**，不会造成资损；实际后果是「门禁一旦开启，所有放款都会被拒」的**可用性问题**。且默认关闭时该链路完全不执行。

**另一处非资损细节【实证】**：`RoleSeparationPolicy.java:230-232` 中 `dealerRelaxed == true` 的分支**先于 `view == null` 判定直接 allow**，即服务不可达时白名单经销商仍放行（Q21 有意的放宽设计，`GuaranteeGatePolicyTest.java:56-59` 有覆盖，`RelaxedDealers` 默认空串见 `AdvanceApplicationService.java:92`）。属"可配置 bypass"，开启门禁前应确认白名单为空。

**判定**：**默认关闭与 fail-closed 均正确，非阻塞**。缺 JWT interceptor 记为「开启门禁前必须补」的技术债（否则门禁开启即全量拒付）；不判资损、不判阻塞。

**时点澄清（见 A-4）**：PM 在审计期间对 `FeignGuaranteeLookup.java` 的 `@Qualifier` 与 `web/pom.xml` 的 loadbalancer 做了启动期修复，但 `git show dba931d:...` 与当前工作区 diff 均显示**未引入 `RequestInterceptor`**，`GuaranteeFeignConfig.java` 也未改动。该修复解决的是启动失败，不改变本条结论。

## B-10 其他独立发现

### (a) 冻结来源未分账，年审解冻可能连带解掉处置冻结 ——【静态推演，未运行时复现】资损方向，非阻塞但需登记

`domain/…/CreditLine.java:44` 的 `frozenAmount` 是**单一标量**，被三处共用且无来源标记：年审冻结（`application/…/AnnualReviewApplicationService.java:48-49`）、手动 `freeze`（`CreditApplicationService.java:105-115`）、处置冻结（`DisposalApplicationService.java:95`）。

代码路径【实证】：`AnnualReviewApplicationService.initiate():48` 取 `freezeAmount = line.getUsedAmount()`，`:49` 调 `line.freeze(freezeAmount)`，`:58` `review.setFrozenAmount(freezeAmount)` —— 记录的是**请求量**；而 `CreditLine.freeze():120-122` 实际冻结量是 `min(amount, free)`（`free = available()`），**可能被 cap**；`complete():89` 用该请求量调 `line.unfreeze(review.getFrozenAmount())`；`CreditLine.unfreeze():180-184` 按 `min(amount, frozenAmount)` 扣减，且 `frozenAmount == 0 && !CLOSED` 就把 status 复位 `ACTIVE`。

推演：当 `available() < usedAmount`（额度接近用满）或多次冻结叠加时，一次年审解冻的请求量 ≥ 当前累计 `frozenAmount`，会把**不属于年审的冻结（含处置冻结）一并清零**，并使 status 误回 `ACTIVE` → 违约经销商恢复放款能力。此耦合是 CORE 新增 `freezeForDisposal` 后才出现的（`git diff --name-only` 证明 `AnnualReviewApplicationService.java` **未**在交付写入集内，属既有代码）。

**判定**：**非阻塞、记为资损方向技术债**。理由：属静态代码推演、**未做运行时复现**，触发依赖特定金额条件；且修复需引入冻结分账模型，改动面大。建议登记并补针对性用例。此项**不作为本次阻塞依据**。

### (b) 已排除的疑点（说明未顺着疑点硬挑错）

`application/…/CollateralLedgerApplicationService.java:368` 写 `batch.setCancellationStatus(CancellationStatus.NOT_DUE.name())`，而 `V10__collateral_ledger.sql:161-162` 的 `chk_cancel_status` 白名单不含 `NOT_DUE` —— 初看会是 INSERT 必失败的 CHECK 冲突。经核 `infrastructure/…/V11__postlending_monitoring.sql:21-25` 已 `DROP CONSTRAINT` 并重建为含 `NOT_DUE` 的 6 值白名单，`:30-36` 还做了数据订正。**该疑点不成立，不计入发现。**

### (c) 边界观察（仅记录代码事实，未验证可达性，不计入结论）

`DisposalApplicationService.open():80-86` 仅在 vehicle 状态为 `OVERDUE` / `IN_STOCK` / `SOLD_PENDING` 时调 `unit.dispose()`；若状态为其他值（如 `SETTLED` / `CLOSED` / `CREATED`），不流转车辆状态，但 `:88-100` **仍无条件执行额度冻结**；且 `open()` 只校验「工单是否未终态」（`:64-68`），未校验车辆是否终态。**是否可达未验证，标注「未验证」**，不作为阻塞依据。

---

# C 结论

## **有阻塞问题，需补修后再合并**

代码虽已合入 master（`dba931d`），但本次独立验收**不能判定该交付验收通过**。阻塞依据是 B-3（**测试失效 + 潜在资损**）与 B-2（**核心流程不可用**），且二者强耦合：单独补 B-2 的解冻会把 B-3 从"潜在资损"变成"实际资损"，**必须同批修复**。当前「163 单测全绿」不能作为质量证据，因为 `CreditReleasePolicyTest.disposed_fullRelease` 把错误行为钉成了绿灯（见 B-8 第 1 条）。

### 必须现在补修（阻塞项）

| # | 项 | 位置 | 最小修复方向 |
|---|---|---|---|
| 1 | B-3 DISPOSING 被当担保消灭 | `domain/…/VehicleUnit.java:586`；`application/…/RepaymentApplicationService.java:331-333,345-346,355-360,386-391` | 把 `DISPOSING` 从 `full(...)` 条件中移除（仅 `CLOSED` 或 `securityStatus==RELEASED` 才 full）；或在 `partialRepay` 显式拒绝 DISPOSING 车辆；**同步修正 `CreditReleasePolicyTest.java:78-86` 的期望** |
| 2 | B-2 撤销/结案未配对解冻 | `application/…/DisposalApplicationService.java:161-173,183-218`；`domain/…/DisposalCase.java:25-62` | `cancel()` / `close()` 增加配对解冻；在 `DisposalCase` 持久化**本次冻结增量**（当前 `open():99` 审计是累计值），明确"撤销/结案后额度状态"口径 |

> 修复后需重新跑针对性用例并复核 `CreditLineDisposalFreezeTest.frozenLine_stillAllowsExistingRepaymentRelease`（`domain/test/…/CreditLineDisposalFreezeTest.java:66-76`）的语义是否仍成立。

### 可记为技术债（后续修，但须登记跟踪；其中 2 项有明确上线前门禁）

| 项 | 位置 | 何时必须修 | 判据 |
|---|---|---|---|
| B-1 幂等分支无条件覆盖已有批次 `co_signed_by` | `application/…/SalesApplicationService.java:167-170` | **建议随本批一起修**（改动极小、持续污染数据） | 非资损 / 可数据订正 / 不影响放款 / 无测试失效 |
| B-1 ownerName 写错列（Q27） | `SalesApplicationService.java:165-166`；`V10__collateral_ledger.sql:156` | Q27 定源后归一（HANDOFF 已标 TODO） | 同上，且 V10 迁移禁改属有意接缝 |
| B-5 `createCreditLine` 单人自动审批绕过 SOD | `CreditApplicationService.java:98-102`；`CreditLineController.java:49` | **上线前必改** | 间接资损通道；修复需跨 credit-service，超出 CORE 边界 |
| B-9 `GuaranteeFeignConfig` 缺 JWT RequestInterceptor | `web/…/config/GuaranteeFeignConfig.java:15-21` | **开启 B1 门禁前必补** | 后果是全量拒付（可用性），非资损；默认关闭时无影响 |
| B-4 同 VIN 未结案工单无唯一约束 / `findLatestByVin` 无锁 | `V2__business_gap_features.sql:39-55`；`JdbcDisposalCaseRepository.java:75-78` | 后续 | 当前被 vehicle 行锁间接串行化；并发未运行时复现（未验证） |
| B-7 状态机非法跳转异常断言缺失 | `domain/test/…/DisposalStateMachineTest.java` | 后续（低优先级） | 代码正确，仅测试覆盖偏弱 |
| B-10(a) `frozenAmount` 冻结来源未分账 | `CreditLine.java:44,179-185`；`AnnualReviewApplicationService.java:48-49,58,89` | 后续（资损方向，建议补用例） | 静态推演未复现，触发依赖金额条件 |
| B-10(c) `open()` 对非预期车辆状态仍冻结额度 | `DisposalApplicationService.java:80-100` | 待验证可达性 | **可达性未验证**，不作结论 |

### 判断依据（按 PM 要求的四维，逐项对齐）

| 发现 | 资损 | 不可逆 | 核心流程不可用 | 测试失效 | 判定 |
|---|---|---|---|---|---|
| B-1 ownerName 写错列 | 否 | 否（可订正数据） | 否 | 否 | 非阻塞 |
| **B-2 未配对解冻** | 否（过度冻结，保守方向） | 部分（有人工端点，无自动配对 / 无精确冻结量） | **是** | 否 | **阻塞** |
| **B-3 DISPOSING 全额回补** | **潜在（当前 FROZEN 拦住 `occupy`，未闭环；配合解冻即成实际）** | 否 | 否 | **是**（`disposed_fullRelease` 假绿灯） | **阻塞** |
| B-4 并发 check-then-act | 否 | 否 | 否 | 否 | 非阻塞 |
| B-5 SOD 兼容入口 | 间接 | 否 | 否 | 否 | 非阻塞（上线前必改） |
| B-9 缺 JWT interceptor | 否（fail-closed 拒付） | 否 | 开启门禁后全量拒付 | 否 | 非阻塞 |
| B-10(a) 冻结未分账 | 潜在（静态推演） | 否 | 否 | 否 | 非阻塞 |

### 附：流程建议（不代为执行）

1. 修正 `dba931d` commit message 中「reasoning verifier 判定可合并」的不实表述 —— **已由 PM 通过 `c9ed24d` 追加更正提交完成**（A-1【实证】），无需再操作；我不修改 git 历史。
2. 补修直接在主仓库 master（或新分支）进行；补修后重新验收，重点复核 B-2 / B-3 及配套单测。
3. 本报告是 verifier 首次出具的验收结论；在此之前**不存在**任何"verifier 判定可合并"的结论。

---

## 审计行为声明

- 本次仅创建本报告 `docs/CORE_VERIFIER_REPORT.md`；**未修改任何代码 / 配置 / 迁移 / 其它文档**。
- **未执行** `git commit` / `merge` / `checkout` / `add` / `stash` / `reset`，未运行任何会写入 `target/` 的构建命令。
- 主仓库工作区在审计期间被 PM 并行改动（两次 `git status` 快照差异见 A-4），HEAD 亦由 `dba931d` 前进到 `c9ed24d`；**这些改动与本次审计无关，非我操作**。我未触碰任何既有文件，`docs/CORE_VERIFIER_REPORT.md` 是我创建的唯一文件。
- 所有 `【实证】` 结论均来自实际执行的只读命令或实际读取的文件行号；`【静态推演】` 与 `【PM 提供，未复跑】` 已分别标注；未验证项明确写「未验证」。无编造的命令输出，无推测性断言。
