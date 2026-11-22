# HANDOFF · W1-LOAN（`LOAN_CREATED` 消费端建据接线）

卡面：`docs/AGENT_TASK-w1-loan.md`。本轮把 W0 留下的**骨架消费者**补成真消费者：
`LoanCreatedConsumer` 不再只打日志，而是调用 `LoanApplicationService.createFromLoanCreated()`
（该方法此前全仓零调用方，`loans` 表恒空 —— PROGRESS §9 / AGENTS §9 的 P0 第 1 条）。

与既有 `loan-service/HANDOFF.md`（上一轮 loan agent 的交付说明）互补：那份讲贷后/DPD/画像，
这份只讲「事件 → 借据」这一条新建链路。

验证结论：`mvn -B clean package` 16/16 SUCCESS（含本服务新增 16 个单测）、
`public` 主干冒烟 211 `[PASS]` / 0 `[FAIL]`、`scripts/smoke_w1_loan.py` 断言 70 条失败 0 条。

---

## 1. 改动清单（文件:行）

| 文件 | 行 | 改了什么 |
|---|---|---|
| `loan-service/.../consumer/LoanCreatedConsumer.java` | 40, 77–132 | 拉下 W0 那行注释：校验载荷 → `loanService.createFromLoanCreated(...)`。`name()` 仍 `"loan-service"`、`eventTypes()` 仍 `Set.of("LOAN_CREATED")`（这两个值是 `event_consumptions.consumer` 与订阅过滤的键，改了会让历史幂等行失效） |
| 同上 | 82, 85, 92, 100, 105, 110, 137 | 六个必填字段 + 日期格式一律 `throw IllegalArgumentException`，理由串里带上 `event_id` / `vin` / 「PAID」字样 |
| 同上 | 142 | 宽限期缺省 0，负数不在消费者这里收敛，交应用层 `Math.max(0, …)`（`LoanApplicationService:82`）—— 消费者只做「有没有」，不做「该怎么修」 |
| `loan-service/src/test/.../LoanCreatedConsumerTest.java` | 新增（152 行 / 9 个 `@Test`） | 纯 Mockito：完整载荷逐字段、缺 `dueDate` 不造默认值、宽限期缺失/负数、日期格式坏要抛、缺 vin/advanceId/归属/金额一律拒 |
| `loan-service/src/test/.../LoanCreateIdempotencyTest.java` | 新增（147 行 / 7 个 `@Test`） | mock `JdbcLoanRepository`：`loan_no='LN-'+advanceId` 幂等键形状、有到期日才生成计划、无到期日仍建据、`insert` 返回 0 时按 VIN 复用、冲突且找不到既有借据要抛 `IllegalStateException` 而不是静默成功 |
| `scripts/smoke_w1_loan.py` | 新增（447 行） | 端到端 8 段判据，全部落库，不看 HTTP 码 |

装配没动：`LoanCreatedConsumer` 带 `@Component`（`:40`），由 `LoanServiceApplication:27` 的
`scanBasePackages = "com.company.floorplan.loan"` 扫到；`LoanApplicationService` 走 `@Import`（`:28`），
两边各一份，不存在双注册。

---

## 2. 为什么缺字段必须抛，不能静默 return

`EventDispatcher` 看的是 `consume()` 有没有抛异常：抛了记 `FAILED`、重试到 `MAX_ATTEMPTS=3` 置 `DEAD`；
正常返回记 `DONE` 并推游标。所以消费者里任何「字段不全就 `return`」的写法，效果是
**框架高高兴兴把这笔债标成已处理，而 `loans` 表里没有它** —— DPD、画像、逾期、对账四条线一起空转，
且不产生任何报警。冒烟第 7 步就是钉这个：缺 `dealerId` 的事件最终 `DEAD`、`attempts=3`、
`last_error` 里能看见缺的是哪个字段、且**没有半条借据、没有状态史孤儿**。

---

## 3. 两层幂等的实测输出

`logs/w-run/smoke_w1_loan.py.log`（2026-11-22 18:47，`public`，同一批服务连跑 w2 → w1）：

```
== 4. 两层幂等：新 event_id 同 advanceId 再投一次 ==
[PASS] 重复投递被框架层记 DONE（耗时 0.6s）
[PASS] 框架层：两个 event_id 各一行幂等记录
[PASS] 业务层：借据仍只一笔（loan_no 唯一键 + 按 VIN 复用）
[PASS] 复用分支未产生第二条状态史
```

两层各挡什么，别混：
- **框架层** `event_consumptions (event_id, consumer)` 主键：挡**同一个 event_id** 重放（游标回退、多实例互踩）；
- **业务层** `loan_no = 'LN-'+advanceId` 唯一键 + `ON CONFLICT DO NOTHING` + `findActiveByVin` 复用：
  挡**同一次业务事实被发成两个 event_id**（发布端 `NOT EXISTS` 的读-判-写窗口真踩到时，靠这层兜）。

冒烟第 4 步用的是第二个 `event_id`（`psql` 直插同 `advanceId`），所以它测的是**业务层** ——
框架层那条在 `smoke_w0.py` 第 4 步（重置游标重放同一 event_id，幂等表仍只一行）。
发布端 `NOT EXISTS` 的机制层在 `smoke_w2_core.py` 第 8 步，三处不要互相代绿。

---

## 4. `GET /api/loans/by-vin/{vin}` 与冻结契约的漂移登记

实查（同一次运行打印）：真实响应是 **`Loan` 实体全字段 23 个**，
`docs/接口契约-事件与Feign.md` 冻结的 `LoanSnapshotDto` 只声明 **7 个**（`id/vin/advanceId/dealerId/status/principal/dueDate`）。两条必须知道：

1. **`spring.jackson.default-property-inclusion: non_null`**（`loan-service/src/main/resources/application.yml:18`、
   `web/src/main/resources/application.yml:53`）会把值为 null 的字段**连键一起丢掉**。
   所以本期 `dueDate` 在 JSON 里是「键不存在」，而不是 `"dueDate": null`。
   冒烟因此按**值语义**断言（`data.get("dueDate") is None`），不写「键必须存在」——
   后者会把正确实现判成红。契约 §2.1 把 `dueDate` 标「建议/可空」，与此一致。
2. `LoanSnapshotDto` / `LoanQueryClient` **至今零生产调用方**（全仓 grep 只有定义与本脚本）。
   这条对齐是拿真实生产者测的；第一个真实调用方出现时它才真正兜住漂移。
   契约文档需要写清「DTO 是实体子集、以实体响应为准」，否则按 DTO 编码的人会以为响应只有 7 个字段。

---

## 5. 留给 W2 / W3 的 TODO

| 项 | 现状 | 归属 |
|---|---|---|
| 借据 `DRAFT → ACTIVE` 激活 | 本轮只到 `DRAFT`（初值），激活是另一条状态迁移，卡面明令不越界 | W1-LOAN 贷后半段 / W3 |
| `PAYMENT_MATCHED` 消费（卡面任务 4，可选 S） | **未做**，没有留半截代码：`LoanCreatedConsumer.eventTypes()` 仍只订阅 `LOAN_CREATED`。等 W1-BANK 把发布端接上（现在 `PAYMENT_MATCHED` 零生产者）再实现，否则测出来的是自己造的事件 | W1-BANK 之后 |
| `DEAD` 事件人工重放 | 无接口，游标已越过该 id。冒烟第 7 步只验到「置 DEAD」，**不验**「重放后能补建」；接缝留在 `EventDispatcher` 类注释 | W3 |
| 多实例共用一条 `consumer_offsets` | 单实例才安全，`FOR UPDATE SKIP LOCKED` 或按实例分片是部署前置条件（PROGRESS §7 W0-5） | W3 / 运维 |
| 主干合并 | `smoke_w1_loan.py` 的判据由收口方合并进 `smoke-test.py`，本轮不碰该文件 | W3 |
| Q-EVT3（到期日权威源） | 签字后 `dueDate`/`gracePeriodDays` 才会进真实事件；本脚本第 2 步「计划表为空」与第 5 步「合成带期日 ⇒ 生成一期计划」要随之调整 | 业务签字 |

---

## 6. 本地怎么验

```bash
mvn -B -pl loan-service -am test          # 16 个新单测 + 既有，全部不依赖数据源
# 冒烟需 web + auth + loan + credit + gateway 在跑（同一条命令内启停，AGENTS 坑 2）
unset FLOORPLAN_DB_SCHEMA SPRING_DATASOURCE_URL
bash run-microservices.sh start > logs/w-run/run.log 2>&1 &
RUN=$!; until grep -q 服务已就绪 logs/w-run/run.log; do sleep 2; done
PYTHONIOENCODING=utf-8 python scripts/smoke_w1_loan.py
kill $RUN; bash run-microservices.sh stop
```

`smoke_w1_loan.py` 第 1 步依赖 W2-CORE 的发布端已接线（`web` 进程里 `confirmAdvance` 真发事件）；
两侧红法不同，一眼能看出断点在发布还是消费。
