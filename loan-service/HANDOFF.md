# HANDOFF · LOAN agent（借据 / DPD / 经销商画像）

> 分支 `agent/loan` · 起始 HEAD `0911b24`（派发任务卡，即 aab5295 的后续）· 未 reset、未执行 branch/worktree/merge。
> 契约文档：`docs/接口契约-V16借据.md`（已冻结，payment agent 照此编码）。

## 1. 实际改了什么

| 范围 | 文件 | 说明 |
|---|---|---|
| 迁移（唯一号段） | `infrastructure/src/main/resources/db/migration/V16__loan_and_dpd.sql` | 五表 + 索引 + COMMENT，金额全 BIGINT |
| 跨 agent 契约 | `docs/接口契约-V16借据.md` | loans DDL、by-vin 响应 JSON、VIN→借据映射规则 |
| loan-service | `pom.xml` | 补 `spring-boot-starter-test`（原骨架缺，单测无法编译） |
| loan-service | `LoanServiceApplication.java` | `@Import` 增加 JdbcLoanRepository、LoanApplicationService |
| loan-service | `model/*.java`（8 个） | Loan / RepaymentPlan / OverdueRecord / DealerRiskProfile + LoanStatus / DpdStage / RepaymentPlanStatus / OverdueRecordStatus |
| loan-service | `service/DpdCalculator.java` | DPD 与 M 系列分层纯函数 |
| loan-service | `service/LoanApplicationService.java` | 建据、还款计划、DPD 批量刷新、画像全量重算 |
| loan-service | `repository/JdbcLoanRepository.java` | NamedParameterJdbcTemplate 真实 JDBC 仓储 |
| loan-service | `controller/LoanController.java` | 4 个查询端点 + 2 个 `JOB_TRIGGER` 批处理端点 |
| loan-service | `test/.../DpdCalculatorTest.java` | 参数化边界单测（22 例） |
| loan-service | `HANDOFF.md` | 本文件 |

未改父 pom、任何 application.yml、common/、run-microservices.sh、其他服务、web/domain/application 既有 java、V17–V21 迁移。

## 2. 五张表状态（全部建好，含索引与 COMMENT）

| 表名 | 设计稿原名 | 内容 | 关键约束 / 索引 |
|---|---|---|---|
| `loans` | loans | 借据当前状态载体（流程唯一读依赖） | `loan_no` UNIQUE；`idx_loans_vin_active`（未结清 VIN）、`idx_loans_advance`、`idx_loans_dealer`、`idx_loans_stage` |
| `loan_status_history` | loan_status_history | 状态跃迁留痕（append-only） | `idx_lsh_loan(loan_id, occurred_at DESC)` |
| `repayment_plans` | repayment_schedules | 还款计划（到期的唯一来源） | `uk_rp_loan_period(loan_id, period_no)`；`idx_rp_due` |
| `overdue_records` | overdue_records | 逾期记录（一笔借据仅一条，跨档升档） | `uk_overdue_loan(loan_id)`；`idx_or_dealer`、`idx_or_stage`；三列规则快照 |
| `dealer_risk_profile` | dealer_risk_profiles | 经销商贷后画像（只读投影，全量重算 + data_as_of） | `dealer_id` UNIQUE；`idx_drp_tier`、`idx_drp_asof` |

> **表名按任务卡 T2-2**（`repayment_plans` / `dealer_risk_profile`），**字段严格按设计稿**（对应 `repayment_schedules` / `dealer_risk_profiles`）。这是任务卡与设计稿的命名差异，已在迁移头注释与契约文档标注。

> **`loans.due_date` 与 `maturity_date` 并存**：任务卡/契约要求 `due_date`，设计稿原字段为 `maturity_date`；两者在仓储建据时写同值，`due_date` 是对 payment agent 暴露的稳定契约名。

## 3. DPD 分档规则实现情况

`DpdCalculator` 为纯函数（无 I/O、无时钟、无 Spring 上下文；`asOf` 由调用方显式传入）：

```
DPD = max(0, asOf − (dueDate + gracePeriodDays))   // 宽限期不计入 DPD
DPD ≤ 0   → NONE
1–30      → M1  早期逾期
31–60     → M2  风险升高
61–90     → M3  严重逾期
≥ 91      → M4  待认定违约候选
```

### 口径冲突如实说明（三处不一致，按"以设计稿为准"取舍）

| 来源 | 分档口径 |
|---|---|
| 任务卡 AGENT_TASK.md 第 8 条（单测提示） | 0 / 1-29 / 30-59 / 60-90 / 90+ |
| ADR-008 §2.6（0-based） | M1≥31 / M2≥61 / M3≥91 |
| 设计稿 §4.3 种子数据（1-based） | M1=1-30 / M2=31-60 / M3=61-90 / M4≥91 |

**采用设计稿 §4.3 口径**：任务卡第 3 条明确"按设计稿的 DPD 分档规则"，且强调"按设计稿实现不要自己发明"；设计稿 §6.1 也明确将 ADR-008 修订为 1-based（M1=1-30）。参数化单测同时覆盖三套口径列出的全部边界点值（29/30/31、59/60/61、90/91），把边界钉死。

## 4. 契约文档写了什么

`docs/接口契约-V16借据.md` 已冻结：
1. `loans` 完整 DDL，逐列标注契约字段（id/vin/advance_id/dealer_id/status/principal/due_date/created_at）与 status 取值表。
2. `GET /api/loans/by-vin/{vin}` 请求/响应：外层 `ApiResponse{code,message,data,traceId}`，`data` 的 15 个字段逐一列名、类型、是否可为 null；未找到返回 404 + `NOT_FOUND_001`。
3. VIN→借据映射规则：一笔垫款一笔借据；一个 VIN 唯一指向未结清借据（status ∈ DRAFT/ACTIVE/OVERDUE/SETTLING，id DESC 取最新）；同 VIN 多笔未结清按 id DESC 取最新并告警；逾期跨档不增记录。
4. DPD 分档表、相关端点、依赖与 TODO。

> 错误码初稿写作 `LOAN_001`，后发现项目统一字典无此码，已在 Controller 实现时改为 `NOT_FOUND_001` 并同步修正契约（字段本身未改）。

## 5. 单测与编译结果

- `mvn -q -pl loan-service -am test`：**通过**。`DpdCalculatorTest` 22 例（13 参数化边界 + 9 行为测试），0 失败 / 0 错误 / 0 跳过。
- `mvn -q -pl loan-service -am package`：**通过**。
- 无数据库环境：编译与单测不依赖数据库（纯函数 + 编译期检查），未连接 PostgreSQL；运行期需 `localhost:5434/floorplan`（application.yml 既有配置，未改）。

## 6. TODO / 跨 agent 依赖

1. **LOAN_CREATED 消费接线**：仓库中 `OutboxPublisher` 只有接口、无实现 bean。loan-service 已提供 `LoanApplicationService.createFromLoanCreated(...)`，但"谁轮询 outbox_events 并调用它"由主 agent 统一接线（本 agent 不跨目录改代码）。
2. **DRAFT→ACTIVE 时机**：建据后为 DRAFT；当前 `refreshDpd` 在无逾期时会迁到 ACTIVE。更严谨应由放款确认回调驱动状态机，待主 agent 合并时对齐。
3. **DPD 规则中心未建**：设计稿 §4 的 dpd_stage_rules / dpd_stage_actions / default_trigger_rules 及种子数据属配置表，不在本任务五表范围；当前分档固化在 `DpdCalculator` 常量（RULE_VERSION/THRESHOLD_SNAPSHOT），要动态调参需后续接配置表。
4. **画像近似指标**：设计稿的 `default_events` / `deduction_failures` 在五表之外，`defaults_last_12m` 用 `loans.default_flag + defaulted_at` 近似、`deduction_fail_rate_30d` 暂无分母（置 NULL），已标 TODO。
5. ~~**营业日口径**：`day_basis=BUSINESS` 依赖 holidays 表（当前 0 行），一期仅 CALENDAR。~~
   ✅ **Q14 已业务定源（2026-11-22）：一律按自然日 CALENDAR，不切换营业日**；
   `day_basis` / `day_basis_snapshot` 字段保留仅作历史快照，不是可切换开关。该 TODO 关闭。
6. **risk_tier 不参与流程**：按设计稿 §6.2 建议，一期仅展示与人工参考，不参与定价/准入（与 `dealers.risk_grade` 授信评级是两套指标）。
7. **payment agent 调用 by-vin**：所有 `/api/**` 需合法 JWT；按项目口径读接口不设权限点，只读账号即可调用，无需额外授权。

## 7. Commit 列表（按时间顺序，均在本 worktree）

| hash | 说明 |
|---|---|
| `8a9cfcc` | [LOAN] T2-2 新增 V16 迁移：借据/状态留痕/还款计划/逾期/画像五表 |
| `60f3932` | [LOAN] T2-2 冻结 V16 借据跨 agent 契约（DDL + by-vin 响应 + VIN 映射） |
| `d0c4111` | [LOAN] T2-2 领域模型：借据/还款计划/逾期/画像实体与四组状态枚举 |
| `47b06bb` | [LOAN] T2-2 DPD 纯函数与分档边界参数化单测（22 例通过） |
| `60a5387` | [LOAN] T2-2 JdbcLoanRepository：by-vin/建据/DPD回写/逾期升档/画像upsert |
| `fb621b0` | [LOAN] T2-2 应用服务：LOAN_CREATED建据/还款计划/DPD刷新/画像重算 |
| `822f177` | [LOAN] T2-2 LoanController：by-vin等四查询端点与JOB_TRIGGER批处理端点（契约错误码对齐） |
| （本提交） | [LOAN] T2-2 启动类装配与 HANDOFF.md |

## 8. Tag

`agent/loan-done`（交付时打）。
