# W-U4 实现说明（凭证质押、WMS 与放车放证闭环）

> 分支 `used/wu4`，基线 `ea21473`（tag `base-used-dev-20261122`）。
> 本轮为**骨架层**：按用户指令「开发吧，并行开发，都不需要测试，等我确认再开始测试」，
> **未编译、未冒烟、未连库**。凡拿不准的一律写进本文§5「待确认」，不用猜测填空。

---

## 1. 本轮实际落盘

| 提交 | 内容 | 文件 |
|---|---|---|
| `b7c8fbf` | V65–V69 五个迁移 | `infrastructure/src/main/resources/db/migration/V65__custodian_wms.sql`、`V66__wms_snapshot_reconcile.sql`、`V67__release_order_used_extension.sql`、`V68__release_item_receiver.sql`、`V69__registration_application.sql` |
| `f203f55` | USED 权限点 ＋ 错误码基座 | `domain/.../model/Permission.java`、`domain/.../model/UserRole.java`、`domain/.../exception/ErrorCode.java` |
| `48dfdfd` | USED 取值枚举 ＋ 10 态状态机 | `domain/.../model/` 下 13 枚新枚举、`domain/.../service/UsedReleaseStateMachine.java` |
| `0fee693` | 门禁与策略层 | `domain/.../service/` 下 `ReleaseFundsGate`、`ReleaseStatusAggregator`、`ReceiverResolutionPolicy`、`ReleaseChannelSelector`、`ReleaseEvidenceChainAssessor`、`RepaymentAllocationStrategy`、`CertificateStateMachine` |
| `f0a864b` | 本实现说明 | `docs/AGENT_TASK-W-U4-实现说明.md` |
| `dc3ea52` | 编译抓到的真实缺陷修复 | `domain/.../service/ReleaseFundsGate.java`（`resolveCancel` 漏 `PARTIAL`，见 §6.1） |
| 验证轮（本节所在提交） | 冒烟脚本 ＋ 本说明改验证口径 | `scripts/smoke_used_wu4.py`、本文件 §6 |

**未落盘**（详见 §3）：domain 实体与仓储端口、infrastructure 仓储、application 用例服务、
D07 §7 的 21 个端点、事件接线、`ReleaseOrchestrator:109` 的真实回补修复。

---

## 2. 规格 ↔ 实现 对照

### 2.1 迁移（恰好 5 个，名字逐字）

| 号段 | 对象 | 关键约束 | 规格源 |
|---|---|---|---|
| V65 | `custodians` ＋4 列、`wms_inbound_confirms` | `uk_wms_inbound`＝幂等键 `INB:{wh}:{vin}:{no}`；`chk_custodian_channel_capability` 正则 | D04 §3.15/§3.16、D07 §7.5 |
| V66 | `wms_inventory_snapshots`/`_items`/`wms_reconcile_diffs` | 同仓同 `snapshot_date` 只一份生效（部分唯一索引）；`MISSING_IN_WMS` 恒 HIGH；IGNORED 必须理由＋二人且不同人 | D04 §3.17/§3.18、D03:309、A8.1/A8.3 |
| V67 | `release_orders` ＋12 列、`vehicle_units.settle_seq`、十态 CHECK、回执权威收紧四步 | `settle_seq`＝`{order_no}:{release_receipts.id}` ＋ 部分唯一索引；`chk_receipt_partner_needs_inbox`；`ux_receipt_inbox` | D04 §5.2/§6、D09 §5.4/§7、D13 G7 |
| V68 | `release_order_items` ＋5 列、`release_receipts` ＋8 列 | `item_status` 六值并集；`item_type` 六值；`exception_type` 九值＋NULL 回填 NONE | D03:321、D09:232、D00 §7:405/408 |
| V69 | `registration_applications` | `chk_reg_completed_needs_inbox`＝「内部 confirm 不得伪造 COMPLETED」的 DB 镜像；`chk_reg_two_person` | D04 §3.11、D07 §7.3、D13 G15 |

号段纪律核对：未占 V40–V64/V70+，未改 V1–V26，无空迁移，未建 `certificate_registrations`，
未建权属多源投影表（`authoritative_source_type` 属 V54/W-U1）。

### 2.2 权限点（19 个，按 D07 §7 端点表逐行反推）

`CERT_READ CERTIFICATE_RECORD CERTIFICATE_PLEDGE CERTIFICATE_RELEASE CERTIFICATE_LOST_DECLARE
CERTIFICATE_REISSUE_APPROVE REGISTRATION_APPLY REGISTRATION_CONFIRM CUSTODIAN_MAINTAIN
WMS_SNAPSHOT_IMPORT RECON_READ RECON_ACK RECON_IGNORE RELEASE_ORDER_ISSUE RELEASE_ORDER_DISPATCH
RELEASE_ORDER_CANCEL RELEASE_RECEIPT_MANUAL RELEASE_READ RELEASE_SETTLE`

授予矩阵（过渡态，理由与代价写在 `UserRole.java` 的类头与各角色行内）：

| 角色 | 新增点 |
|---|---|
| SYSTEM_ADMIN | CUSTODIAN_MAINTAIN |
| PURCHASE_OPERATOR | WMS_SNAPSHOT_IMPORT |
| PURCHASE_REVIEWER | RELEASE_RECEIPT_MANUAL |
| COLLATERAL_OFFICER | 証書/登记组 8 点（含 CERT_READ） |
| POST_LENDING_MANAGER | RECON_READ、RECON_IGNORE、RELEASE_ORDER_CANCEL |
| SETTLEMENT_OFFICER | RELEASE_ORDER_ISSUE、RELEASE_ORDER_DISPATCH、RELEASE_READ、RECON_ACK |
| （无人持有） | RELEASE_SETTLE —— 「内部不开放」的代码表达 |

未被授予的 1 点即 `RELEASE_SETTLE`；18 点覆盖 D07 §7.3/§7.5/§7.6 全部 21 行的权限列。

### 2.3 状态机

* `UsedReleaseStatus`（10 值，与 V67 的 `chk_release_order_status` 一一对应）
  ＋ `UsedReleaseStateMachine`（穷举 `Map.ofEntries`，十键齐全）。
* `CertificateStatus`（6 值，D00 §7:394）＋ 迁移判定内建在枚举（与 `VehicleStatus` 同形）
  ＋ `CertificateStateMachine`（质押生效／放证／挂失三组前置）。
* 门禁表达力自检（本轮只到 domain，端点未接，但条件已经可被断言）：
  A1.1/A1.2/A1.4→`ReleaseFundsGate`，A1.3→`ConfirmationAuthority.derive`，A1.5→`requiresSystemOrigin`，
  A2.1/A2.4→`mayRestoreCredit` ＋ 权威判定，A3.1/A3.2→`ReleaseStatusAggregator`，
  A8.2/A8.3→闸门 G3 ＋ V66 的 IGNORE 约束，A9.x→待 application 层落 `@Transactional` 边界，
  RL-1a…RL-4/RL-6/RL-7→`ReleaseFundsGate`/`ReleaseChannelSelector`/`ReleaseEvidenceChainAssessor`。

---

## 3. 未实现项及原因

| # | 项 | 原因 |
|---|---|---|
| 1 | domain 实体 ＋ 仓储端口 | 本轮预算内未完成。已把全部判定（闸门/聚合/解析/证据链）做完，剩余是"数据载体"，与 §5 禁区无冲突，可继续。 |
| 2 | infrastructure 仓储（含 collateral-reg 只读投影） | 同上。**注意**：`inspection_certificates`/`certificate_custody_events` 由 **V52/W-U1** 建，本分支库内不存在（实测迁移目录只有 V1–V26 ＋ V65–V69），因此凭证侧仓储只能按 D04 §3.9/§3.10 的列契约写，**分支内无法单独跑通凭证链路**，合并后必须在主干 schema 跑一次全量。 |
| 3 | application 六个用例服务 | 同上；`@Transactional` 只在 application 层的纪律已在服务未落地前无从体现。 |
| 4 | D07 §7 的 21 个端点 | 依赖 1–3。**且被两个开工门禁卡住，需裁决（见 §4.1/§4.2），不是单纯没写完。** |
| 5 | 事件发布/消费接线 | 依赖 3（发布调用只能写在我的业务调用方内）。冻结名与 † 未定义名的登记见 §5.4。 |
| 6 | `ReleaseOrchestrator.java:109` 真实回补 | 该文件在 **bank-service**（工单卡写成 web/core，实测纠正）。`availableAfter = before` 的语义修复必须与 §3.4 的回补通道一起定：现在既没有额度**写**契约（`CreditLineClient` 是只读），也不允许直接写 credit 的表，故延后到 §5.3 那条裁决落地。 |
| 7 | `ReleaseStateMachineUsedTest` 等测试 | 本轮禁止测试；D03 要求的那条「旧六态迁移集完全一致」断言目前**无法成立**，见 §4.3。 |

---

## 4. 自审发现的规格冲突（每条带出处）

### 4.1 网关前缀 ↔ 属主服务（开工门禁，必须先裁）
D07 §7.0 实测：`/api/release-orders/**`、`/api/certificates/**`、`/api/registration-applications/**`
都不命中任何具名谓词，落到 `gateway/.../application.yml:95-98` 的 `Path=/api/**` 兜底 = **core**；
而 release-service 的谓词是 `/api/releases/**`（`:83-86`，实测 `ReleaseController.java:39` 同）。
但 D00:135 ＋ D12:57 ＋ D13 §3 G11 又明写放车端点归 `release-service`、凭证/登记写端点归
`collateral-reg-service`。§5 禁止并行包改网关 yml，§3.2 禁止自造路径 ⇒ **三者不可同时满足**。
本轮的处置：不改路径、不改 yml、不落 Controller，把这一格留给你裁决（两条出路：
① 加一条 `Path=/api/release-orders/**,/api/certificates/**,/api/registration-applications/**` 路由；
② 端点实现落 core，两服务只保留各自前缀下的只读投影）。

### 4.2 新角色需要第 6 个迁移
D10 §3 的 12 个角色名在本仓不存在，且 `user_roles.role_code` 被 V8 的 `ck_user_role_code`
（`V8__user_roles.sql:25-30`）白名单钉死。不加迁移就只能把 USED 点加到既有角色上，
代价是 D-6（质押↔放证）与 D-2（委托↔结果确认）两对**同岗持有**，岗位级分离失效。已就地标注「收口必删」。

### 4.3 LEGACY 兼容断言与 RL-4 互斥
D03 §3.3 要求「旧六态之间的合法迁移集在 USED 枚举下保持完全一致」，但同节 mermaid 删掉了
`SENT→CANCELLED`、`CONFIRMED→CANCELLED`，而 D09 §12 RL-4 明确要求 `CONFIRMED` 后 `/cancel`
必须被拒（`RELS_001`）。本轮按 RL-4 ＋ mermaid 实现，把兼容口径改成
「USED 只允许删边、不允许加边」（`UsedReleaseStateMachine.legacyStateEdges()` 供断言）。
另外 D09 §5.4 的「SENT 尽力撤 → 成功」又把 `SENT→CANCELLED` 需要回来：USED 表里 SENT 无 CANCELLED 出边，
尽力撤的实现只能走"cancel 出站任务 ＋ 对方确认后由 ACKNOWLEDGED 侧处理"，这条要请 D09 owner 确认哪种解释为准。

### 4.4 RELS_* 的四种互相打架的用法
D07 §8（码表）定义 001–004；D07 §7.6 重校验表把 003 用作"过期"、004 用作"项不可释放"；
D03 §3.2 把 001/002 用在**車検証**迁移上；D09 §4 引入 006。
本轮以 **D07 §8 ＋ D09** 为准落 `ErrorCode`（RELS_005 无文档语义 ⇒ 不建），
其余引用点在代码注释里显式标出冲突。

### 4.5 其他（已登记，代码内均带出处注释）
* D07:635 写 `vehicle_ledger.settle_seq`，库里是 `vehicle_units`（V1）。
* D00 §8.3 说权威 CHECK/回填在 "V65 段"，D04 §6/§2 说 V67 ⇒ 取 V67。
* D04 §6 步骤1 要 `public.enum_confirmation_authority` 字典表，与 §2.1 的 5 表预算冲突 ⇒ 未建，用 CHECK＋枚举。
* D04 §5.2 把 `release_receipts.inbound_message_id` 记在 V68，§6（V67）先需要它 ⇒ 列建在 V67，V68 保留幂等 no-op。
* D03:321（5 值）与 D09:232（`ACKNOWLEDGED`）⇒ `item_status` 取六值并集。
* D04 §5.2 的 V67 行漏 `receipt_degraded`/`dispatch_seq`/`product_line` ⇒ 已补（源 D09:258、D07 §7.6、D03:470）。
* D07 §7.6 清单第 3 行的 `CREDIT_003` 与现役码义不符（冻结是 `CREDIT_002`）⇒ 按码义取 002。
* `audit_events`（V1:146-157）没有 `details`/`biz_key`/`reason` 列，D10 §6.4 假设有 ⇒ 回填写进 `after_snapshot`。
* D09 §5.5 要求改 `ManualReleaseChannel.dispatch()`，§5 禁区要求它行为不变 ⇒ 本轮不改旧类，USED 走新接缝。
* D11 A2.1 断言「无回执直接调**结清端点**」，而 D07 §7.6 把 `/settle` 标为内部不开放 ⇒ 该断言要改成"不可达即通过"。

---

## 5. 待你/收口方点头的事项

1. **§4.1 的网关裁决**（阻塞 21 个端点落地）。
2. **§4.2 的角色矩阵**：加迁移建 12 角色，还是接受过渡矩阵并把 D-6/D-2 交给自然人校验。
3. **额度回补通道**：Feign 写契约（新契约属收口方）、`CREDIT_LINE_RESTORED` 事件，还是 core 直写——决定 §3.4 修复的写法。
4. **† 事件登记**（本轮未接线，先登记）：发布 `TOPUP_REQUESTED`†、`EXTENSION_REQUESTED`†；
   消费 `LOAN_REPAID`†、`EXTENSION_APPROVED`†。可接线的一批（`CERTIFICATE_PLEDGED`、
   `WMS_INBOUND_CONFIRMED`、`REGISTRATION_DISCHARGED`、`RELEASE_ORDER_DISPATCHED`、
   `RELEASE_ORDER_RECEIPTED`、`VEHICLE_SETTLED`、`LTV_BREACH_DETECTED`、消费 `PAYMENT_MATCHED`、
   `CREDIT_LINE_RESTORED`）等 application 层落地时一并接。
   `aggregate_key` 一律不用 `vin`：`settle_seq`、`{order_no}:{dispatch_seq}`、`snapshot_key`。
5. **X-1 冲抵顺序**：接口已给（`RepaymentAllocationStrategy`），无任何默认实现；
   配置开关 `repayment-alloc-fee-then-interest-then-principal` 本轮**没有落**，等定源后带 `[待定源 X-1]` 一起落。
6. **R9/R10**：`inventory_releases`、`repayment_allocations` 未建 ⇒ 证据链第 ⑥ 条恒"无法取证"，
   `evidence-chain` 视图会把未闭合显示出来（不是 bug，是 R9 未签字的直接后果）。
7. **工单**：`MANUAL` 通道要"生成工单"，但按 D06 §15 表尾降级没有工单表 ⇒ 本轮只落决策枚举，落单动作待收口。
8. `floorplan.core.guarantee-gate.enabled` 保持 `false`；Q21 无白名单/灰度旁路；
   `collateral-reg-service` 保持只读投影（其"转正"依赖 §4.1 裁决后才好定形态）。

---

## 6. 验证记录（2026-11-22 第二轮：用户解除了"本轮不测试"的冻结）

> 本节替换上一版的"未编译未冒烟"声明。**§1–§5 里凡写着"未经编译器校验"的句子，
> 现在只对 §3 那些还没实现的东西成立**，已落盘部分按下述口径核过。

### 6.1 `mvn -B -ntp clean package`（全反应堆）

16/16 模块 **SUCCESS**，测试 **189 项、0 失败**（domain 49 / bank 18 / loan 23 /
collateral-reg 10 / release 55 / recon 34）。与本轮有关的两个结论：

- `ReleaseStateMachineTest` 46 项全绿 ⇒ §5 禁区"旧 6×6 白名单不许动"守住了；
- `bank-service` 的 `ReleaseOrchestratorTest` 4 项全绿 ⇒ 我没碰 `:109` 那处占位回补，
  现役行为不变（该文件实测在 **bank-service**，不是工单卡写的 web/core）。

第一轮编译**抓到一处真实缺陷**：`ReleaseFundsGate.resolveCancel` 的 switch 覆盖了
9 个值而枚举有 10 个——漏的正是 `PARTIAL`。已按状态机反推归入"不可撤"（RELS_001）
并单独提交 `dc3ea52`。这件事本身就是"没编译的骨架不能当交付"的实证：
纯读代码时那段白名单我来回看过四遍，没看出少一个键。

### 6.2 环境隔离（与并行会话互不干扰）

| 项 | 值 |
|---|---|
| schema | `used_wu4`（`agent_env.sh init` 建，Flyway 只由 web 写入） |
| 端口偏移 | **+800**：web 8880 / auth 8881 / release 8887 / 网关 18880（700 段归 W-U1，未使用） |
| 连接串 | `currentSchema=used_wu4,public`（第二段 public 不能省，pgcrypto 在那） |
| `agent_env.sh check` | 表数=**72/72** 通过（默认基准 67 时按脚本自己的提示判"主干涨了"） |
| `public` 是否被污染 | **否**，冒烟第 0 节实测：`public` 里本包五张表 **0** 张、`public.flyway_schema_history` 里 V65–V69 **0** 笔 |

进程与清理：四个进程按端口 `taskkill` 清理，`netstat` 复确认 88xx/18880 无监听；
夹具在 `used_wu4` 内十张表全部归零（脚本首尾各清一次，可重复跑）。

### 6.3 Flyway 实况

`Successfully applied 31 migrations ... now at version v69`——V1–V26 加本包 V65–V69，
**五笔逐字名全部 success=true**，无失败语句。两条 WARN（原文）：

```
DB: column "exception_type" of relation "release_receipts" already exists, skipping (SQL State: 42701)
DB: column "inbound_message_id" of relation "release_receipts" already exists, skipping (SQL State: 42701)
```

定性：**空转，不是形状错位**。`exception_type` 由 V19 建、`inbound_message_id` 由 V67:130
建（V67 注释 :129 写明"D04 §5.2 把该列归属写在 V68 行里，故两处都幂等重挂"）。
库里实际 `VARCHAR(24)` / `BIGINT` 与我 V68 写的意图逐字一致，且后续的
`SET DEFAULT 'NONE'` 与 `VALIDATE CONSTRAINT` 都执行到了（冒烟已断言）。

### 6.4 `scripts/smoke_used_wu4.py`：96 条断言、0 失败、EXIT=0

跑法（三个环境变量缺一个结论就无效，脚本第 0 节会自己终止）：

```bash
export FLOORPLAN_DB_SCHEMA=used_wu4 SMOKE_BASE=http://127.0.0.1:18880 \
       SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5434/floorplan?currentSchema=used_wu4,public'
PYTHONIOENCODING=utf-8 python scripts/smoke_used_wu4.py
```

覆盖面（判据一律是"插一条、回库里数行数"，不认 HTTP 码）：
十态 CHECK 正反（10 个合法值全可写 ＋ 第 11 值被拒）、LEGACY 六态是十态子集、
五张新表与 30 个新列到位、部分唯一索引的**两个半边**都验（拒绝重复 ＋
`SUPERSEDED`/`settle_seq IS NULL` 时谓词放行——后者最容易被写错成恒拒）、
WMS 六类差异与四级严重、`MISSING_IN_WMS` 恒 HIGH、IGNORED 需理由且双签不同人、
回执三值权威与"代人回执必须有入站引用"、`ux_receipt_inbox` 一条入站消息只一份回执、
登记委托的二人分离与"COMPLETED 必须有入站引用"（含 §3.2 那条"内部 confirm
不得伪造 COMPLETED"的库层兜底半边）、`apply_no` 唯一、入库确认三键幂等。

第 12 节经网关跑 LEGACY 真实链路：`collateral`（担保管理岗）建单 → confirm → release
→ discharge-lien，库里状态逐段核对，四列时间戳写满、状态历史 5 条、代录回执权威非空；
反向半边：**管理员同一路径 403 / AUTH_002**（证明我改过 `UserRole` 之后
"管理员不碰业务单据"这条 SOD 仍然成立）；且 LEGACY 单子的新列取默认
`product_line=NULL / receipt_degraded=FALSE / dispatch_seq=1`（没被我的 ALTER 污染）。

### 6.5 验证带出的新发现（接在 §4 冲突清单后面编号）

- **4.12 仓级差异没有合法的 diff_type**。D04:450 的六值全是 VIN 级语义，
  D04:455 只规定一个唯一键 `(biz_date, wh, vin, diff_type)`；我为补
  "PostgreSQL 里 NULL 不参与 UNIQUE 去重"另建了 `uk_wms_diff_daily_wh`（`WHERE vin IS NULL`），
  但没有任何一个 diff_type 能合法地配上 `vin IS NULL` ⇒ **这条索引的分支目前无生产者**。
  冒烟只能借 `BIN_MISMATCH` 把谓词本身验掉（脚本里注明这不是语义结论）。
  要么补一个仓级 diff_type（改 D04 §3.18），要么删掉这条索引并规定"差异必须落 VIN 级"。
  本轮没有自造第七个值。
- **4.13 五条 CHECK 停在 `convalidated=false`**：`chk_release_order_status`、
  `chk_custodian_channel_capability`、`chk_custodian_reconcile_cycle`、
  `chk_receipt_item_type`、`chk_receipt_result`。前三条是 V65:81／V67:13 写明
  "留给主干回归补 VALIDATE"的**有意**选择；后两条是新增列、存量行全 NULL，
  NOT VALID 在这里没换来任何东西，只会让主干"全库约束已验证"的审计永远看到红。
  本轮**不修改已跑过的迁移**（改了与本包 `used_wu4` 的校验和打架），仅登记待收口处理。
- **4.14 `agent_env.sh:144` 的表数阶梯对不上单包分支**：注释给的档位是
  73/84/89/94/96，而**只含本包的分支实测 72**（67＋5）。阶梯是按"合并累加"写的，
  并行期每个 agent 照它读数会以为自己的 schema 少建了表、进而 `drop` 重迁。
  建议改成"基准 = 67 ＋ 本分支新建表数"或按包列增量。（我没改这个脚本：
  它不在本轮可改范围，且默认值 67 是主干判据。）
- **4.15 主干 `public` 已被别的会话落了一笔未合并的 V27，且它动的是同一张表**。
  实测证据（我只读，未参与）：`public.flyway_schema_history` 第 27 行
  `v27 | release payment ref | success=true | 2026-11-22 22:50:01`，
  而 master 工作树里**没有 V27 文件**——文件在 `wt-w1-bank/…/V27__release_payment_ref.sql`。
  对本包的影响：
  1. 列名不冲突（它加 `payment_ref`，我的 V67 加 11 列，无交集），合并不会互相覆盖；
  2. 但它执行了 `ALTER TABLE release_orders ALTER COLUMN redemption_id DROP NOT NULL`，
     并规定"回款匹配自动草稿 `redemption_id` 为 NULL、人工建单必填"；
  3. 于是我的 `ReleaseFundsGate`（G1 敞口按赎车/借据推导）与
     `ReceiverResolutionPolicy` **将来必须处理 `redemption_id IS NULL` 的行**，
     不能再假设每张指令都有赎车申请凭证；
  4. 且它给 `release_orders` 引入了一个**非本包的写入方**（bank-service 消费
     `PAYMENT_MATCHED` 自动建草稿）。我的十态白名单从 `PENDING_RELEASE` 起步，
     形状兼容，但"谁有权建单"这条 D07 端点门禁将来要覆盖两条写入路径。
  合并前请收口方确认 V27 与 V65–V69 的先后（Flyway 按版本号顺序，27 在前 ⇒
  我落 V67 时 `redemption_id` 已可空，幂等写法不受影响；反过来也成立，两处都不改名）。
  **本轮结束后复查仍成立**（只读实测）：`public` 最高成功版本 = 27、
  V65–V69 在 `public` 里 0 笔、`public` 表数 67、master 工作树依旧没有 V27 文件。
- **4.16 V69 的跨包外键守卫只走过"跳过"那一支**。V69:119-127 用
  `IF to_regclass('inspection_certificates') IS NOT NULL` 包住 `fk_reg_certificate`
  （因为 D04 §2 标了 V69 依赖 V52 = W-U1 号段），这条写法让本包**在无 V52 的分支上也能迁通**
  ——实测 `used_wu4` 里 `registration_applications` 只有 6 条 CHECK ＋ pkey ＋ `uk_registration_apply_no`，
  **没有** `fk_reg_certificate`，即当时走的是"跳过"分支。
  反面是：**"建 FK"那一支至今没有任何一个 schema 执行过**（`used_wu1` 有 V52 但没有我的 V69，
  我去不了它的 schema，也不该去——那是往别人的库里落未合并迁移）。
  合并后主干跑全量时这条 FK 是**第一次真正执行**，请把它列为观察点；
  且它是 `NOT VALID`，存量脏 `certificate_id` 不会当场报错，
  将来补 `VALIDATE CONSTRAINT` 时才会红。
- **过程诚实**：这份冒烟第一轮跑出 5 条红，其中 **3 条是我探针写错**
  （唯一索引类探针把合法行也数进去了，`landed=False` 已证明约束本身是好的）、
  **1 条是我夹具自造了 `COUNT_MISMATCH`**、**1 条是我漏传 `SMOKE_BASE`**。
  **没有一条是迁移缺陷**。记在这里是给后面的人提个醒：这五红看起来全都像
  "迁移有问题"，逐条查到证据之前不要动手改迁移。

### 6.6 仍未验证（不要拿 §6.4 的绿当这些项过了）

R5 凭证链（`inspection_certificates`/`certificate_custody_events` 属 V52/W-U1，
本分支无表；本轮只读实查确认它们**已存在，但只在 `used_wu1` schema 里**（该库当前在 v54），
`public` 里没有 ⇒ 这是纯粹的合并次序依赖，不是我漏建，也不是 W-U1 没做）、
本包 21 个端点（Controller 未实现）、七类事件接线、
`ReleaseOrchestrator:109` 的真实额度回补、X-1 冲抵顺序（**只留接口，无实现，
未落任何默认值**）、`UsedReleaseStateMachine` 与六个门禁类的单元测试
（本轮只验了"在库里成立"，domain 层的行为断言还欠着）。
合并后必须在主干 `public` 跑一次全量——分支绿不算验收。
