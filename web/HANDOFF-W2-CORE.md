# HANDOFF · W2-CORE（`LOAN_CREATED` 发布端接线）

卡面：`docs/AGENT_TASK-w2-core.md`。本轮把 W0 交付的事件机制接上**第一个真实业务调用方**：
银行回调确认放款成功后，`confirmAdvance` 在同一事务内发布 `LOAN_CREATED`。

验证结论见文末「实测输出」；结论口径 = `mvn -B clean package` 16/16 SUCCESS +
`public` 上主干冒烟 211 条 `[PASS]` / 0 `[FAIL]` + `scripts/smoke_w2_core.py` 断言 49 条失败 0 条。

---

## 1. 改动清单（文件:行）

| 文件 | 行 | 改了什么 |
|---|---|---|
| `application/src/main/java/com/company/floorplan/application/event/LoanCreatedCommand.java` | 新增（39 行） | 端口入参 record：`vin / advanceId / dealerId / creditLineId / principal / traceId`。**故意没有 `dueDate`、`gracePeriodDays`** —— 见第 5 节 |
| `application/src/main/java/com/company/floorplan/application/event/LoanCreatedPublisher.java` | 新增（34 行） | 端口 `void publish(LoanCreatedCommand)`。javadoc 写明三条硬约束：与业务写同事务、不许 catch、一笔事实只调一次 |
| `application/.../service/AdvanceApplicationService.java` | 4, 92–96, 135, 148, 550–591 | 构造器新增 `LoanCreatedPublisher` 依赖；`confirmAdvance` 在 `PAID` 落库后调 `publish`（`:587`）。终态早返回在 `:553` 起，重复回调够不着发布行 |
| `web/src/main/java/com/company/floorplan/web/integration/OutboxLoanCreatedPublisher.java` | 新增（104 行） | 适配器：校验必填 → `LinkedHashMap` + `ObjectMapper` 序列化 → `outbox.publish(EVENT_LOAN_CREATED, String.valueOf(advanceId), json)`（`:100`） |
| `web/.../FloorplanApplication.java` | 5, 31–33 | `@Import` 加 `OutboxLoanCreatedPublisher.class`；注释写清为什么不打 `@Component` |
| `web/.../controller/AdvanceController.java` | 124–143 | `confirm` 从 `RequestLoggingFilter.ATTR_TRACE_ID` 取 traceId 后显式传给 application（`:141`） |
| `scripts/smoke_w2_core.py` | 新增（353 行） | 发布端冒烟，走网关 `18080`，判据落 `outbox_events` 库内状态 |
| `scripts/smoke_w0.py` | 30–33, 119–131, 177–184 | 消费端接线后的回归修正，见第 6 节 |

**为什么 `LoanCreatedPublisher` 在 application、适配器在 `web/integration/`（不是卡面写的 `web/adapter/`）**
依赖方向是 `common → application`（`common/pom.xml` 反向依赖），所以 application 层**看不见** `common` 里的
`OutboxPublisher`，端口只能定义在 application；实现端要同时看见端口和 `common.OutboxPublisher`，
只有 `web` 满足（`web/pom.xml:19-31` 同时依赖 application + infrastructure + common）。
包名沿用仓库既有的 `web/integration/`（`FeignGuaranteeLookup` 等适配器都在这里），
新建 `web/adapter/` 会凭空多出一个同义包，收口方还得再搬一次。

---

## 2. traceId 透传链路（一条线，不是两处编码）

```
HTTP 请求 → RequestLoggingFilter 生成 traceId（放 request attribute）
  → AdvanceController.confirm:141 取出 → confirmAdvance(…, traceId):551
  → LoanCreatedCommand.traceId → 事件载荷 traceId（OutboxLoanCreatedPublisher 校验非空）
  → LoanCreatedConsumer 用载荷里这个值写 loan_status_history.reason
  → 同一值又作为 ApiResponse.traceId 返回给调用方
```

冒烟里两侧都有断言钉住：`smoke_w2_core.py` 第 6 步断言「事件里的 traceId == 回调响应信封的 traceId」，
`smoke_w1_loan.py` 第 3 步断言「reason 里的 traceId 就是事件里那个，不是消费者另造的」。

**取到 `"null"` 就让它抛，不兜底 UUID**：`String.valueOf(attr)` 在属性缺失时返回字符串 `"null"`，
适配器把它连同空白值一起拒掉。宁可回调 500 也不发一条断链的事件 —— 兜底生成新 UUID 等于
「事件发出去了、但没人能从日志找回这条链路」，那是比红更贵的绿。

---

## 3. 为什么接线点选 `confirmAdvance`（不是 `disburseAdvance`）

`docs/并行开发-剩余需求拆分方案.md §11` 表下那条更正说明已把这事定源（用户 2026-11-22 点头），此处不重新论证。
一句话：`disburseAdvance` 只把垫款推到 `PROCESSING`（钱没到账），契约 §2 的 `LOAN_CREATED`
定义在银行回调 `PAID` 之后；按方案原句接会让借据变成空账记账。

---

## 4. 机制层去重（W0-3 的 W2-CORE 这一半）实测输出

`smoke_w2_core.py` 第 8 步原文（`logs/w-run/smoke_w2_core.py.log`，2026-11-22 18:47 跑 `public`）：

```
== 8. 机制层去重（发布端 WHERE NOT EXISTS，不是表约束）==
[PASS] 夹具已插入：同 (event_type, aggregate_key) 已有一行 SENT
[PASS] 夹具计数=1
[PASS] 合法回调仍 200（去重不影响业务成功）
[PASS] 回调后该键仍只一行（第二次插被 WHERE NOT EXISTS 拦住）
[PASS] 留下的那行仍是夹具（说明不是把夹具删了再插新的）
[PASS] 垫款已 PAID
   [已知窗口] NOT EXISTS 是读-判-写，两个并发事务可能各插一条（JdbcOutboxPublisher 类注释已
   如实记录）。兜底是消费端 event_consumptions 主键 + loan_no 唯一键两层幂等；本脚本单线程
   跑不出这个窗口，不拿「没复现」当「不存在」。
```

取法说明（卡面 W0-3 要求交代给执行人）：这一半走的是「**先造 SENT 夹具，再从 HTTP 打真回调**」，
比原计划的「删 `payment_matches` 保留 outbox」更直接 —— 它绕不开发布端，因为事件本身就是发布端写的。
W1-BANK 那半（`PAYMENT_MATCHED`）仍按 `docs/AGENT_TASK-w1-bank.md` 任务 6 交实测输出，
两边都交到之后收口方才会把 §7 W0-3 翻成已闭环。

另两条容易混的：
- 第 7 步「重复回调仍只一行」测的是 `confirmAdvance` 的**终态早返回**（业务层幂等），不是 `NOT EXISTS`；
- 表上没有 `UNIQUE (event_type, aggregate_key)` 约束（V21 实查），所以去重失效不会报错、只会多一行。

---

## 5. `financing_terms` 缺写入方这条定源需求的处置建议

本轮**没有**在载荷里发 `dueDate` / `gracePeriodDays`，也不是偷懒：

- Q-EVT3（到期日权威源）指向 `financing_terms`，而该表**全仓零写入方**（只有 V 迁移建的空表）；
- 未签字的口径不许按理解实现（AGENTS 铁律 3）。造一个「看起来合理」的到期日
  等于凭空生成本金清偿义务，会顺着 `generateRepaymentPlan` 生成一条真的还款计划。

所以现在的行为是：发布端不发这两个字段 → `LoanCreatedConsumer` 把 `dueDate` 解析成 `null` →
`LoanApplicationService.generateRepaymentPlan:95-96` 见 null 只 `log.warn` 跳过 →
借据落成 `DRAFT` + `due_date IS NULL` + `repayment_plans` 零行。`smoke_w2_core.py` 第 5 步
（断言载荷不含这两个键）与 `smoke_w1_loan.py` 第 2 步（断言计划表为空）把这条**临时口径钉住**，
签字之后这两条断言要随之改，不是悄悄改代码。

建议处置（等业务方签字，不要由开发侧默认）：
1. **`financing_terms` 的写入方**先定源 —— 是授信审批时录入，还是随垫款申请提交？决定 D6（客户支付确认凭证）之外的又一张表；
2. 签字后由 W2-CORE 侧把 `dueDate`/`gracePeriodDays` 加进 `LoanCreatedCommand`（字段顺序已预留注释位），
   同步改契约文档 §2.1 的「建议」→「必填」，并让 `smoke_w1_loan.py` 第 5 步（合成带 `dueDate` 的事件
   ⇒ 生成一期计划）从「预置能力验证」升级为主链路断言；
3. 在签字之前，任何「先用 `paidAt + 90 天`」的实现都应当被 reviewer 挡掉。

---

## 6. 回归副作用（本轮改动波及的既有冒烟）

`LoanCreatedConsumer` 从「只留痕」变成「真建据」之后，`scripts/smoke_w0.py` 那两条 `psql` 直插的
`LOAN_CREATED` 事件**会真的建出借据**，写死 `advanceId=900001/900002` 第二轮就撞 `loan_no` 唯一键。
修在同一次提交里：

1. `ADV1 = 9_000_000_000 + int(time.time())` —— 每轮唯一，撞不上业务幂等键；
2. `drop_loans()` 先删 `repayment_plans` / `loan_status_history` 再删 `loans`（三表都无外键，V16 实查）；
3. 表存在性探针原先写的是 `psql("SELECT a,b")` + `"MISSING" not in tabs[0]`，那是**恒真**（列表成员比较，
   不是子串判断）—— 改成逐表单列 `to_regclass` 判定。这条假绿是 W0-8 脚手架自带的，本轮才暴露。

---

## 7. 留给收口方 / W3 的 TODO

| 项 | 现状 | 归属 |
|---|---|---|
| `AdvanceController:82 :98 :115 :196` 仍用 `UUID.randomUUID()` 当响应 traceId | `confirm` 已改成透传过滤器的值，其余四个端点同样该改，但它们在 `app.js` 与主干冒烟里有既有期望，属共享层收口 | W3 |
| `PAYMENT_MATCHED` / `COLLATERAL_CHANGED` 发布端 | 仍零调用方；键与口径分别见 §7 W0-1、W0-7 | W1-BANK / 业务签字 |
| 主干合并 | `scripts/smoke_w2_core.py` + `smoke_w1_loan.py` + `smoke_w0.py` 修正由收口方合并进 `smoke-test.py`，本轮不碰 `smoke-test.py` | W3 |
| `agent_smoke_lib.psql()` 多列读取分隔符 bug | `psql -tA` 默认列分隔符是 `\|`，公共库按 tab 拆 ⇒ 多列 SELECT 全部错位（实测 `psql("SELECT 1,'a\|b',3") -> [['1\|a\|b\|3']]`）。本轮**没有**改这个共享禁区文件，改为在脚本侧统一用单列 `row_to_json` / `json_agg` 读法规避。建议修法：`local`/`docker` 两条命令各加 `"-F", "\t"`。**需 W0 owner 提案 + 用户书面点头**（AGENTS §7.1） | 待点头 |

---

## 8. 本地怎么验（同一条命令内完成，AGENTS 坑 2）

```bash
unset FLOORPLAN_DB_SCHEMA SPRING_DATASOURCE_URL          # 交付判据要求跑 public
bash run-microservices.sh start > logs/w-run/run.log 2>&1 &
RUN=$!; until grep -q 服务已就绪 logs/w-run/run.log; do sleep 2; done
PYTHONIOENCODING=utf-8 python scripts/smoke_w2_core.py   # 必需进程：gateway + web + auth + credit
kill $RUN; bash run-microservices.sh stop
```
