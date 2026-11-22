"""smoke_w0.py —— W0 事件地基冒烟（卡面验收：机制通，业务留给 W1）。

跑法（启动 + 测试 + 清理必须同一条命令，否则后台 JVM 会随父 shell 退出被回收 —— AGENTS 坑 2）：

    mkdir -p logs/w0 && rm -f logs/w0/*.log
    env -u SERVER__PORT java -jar web/target/floorplan-web-1.0.0-SNAPSHOT.jar > logs/w0/web.log 2>&1 &
    env -u SERVER__PORT java -jar auth-service/target/floorplan-auth-service-1.0.0-SNAPSHOT.jar > logs/w0/auth.log 2>&1 &
    env -u SERVER__PORT java -jar loan-service/target/floorplan-loan-service-1.0.0-SNAPSHOT.jar > logs/w0/loan.log 2>&1 &
    # 按日志 marker（Started XxxApplication）等就绪，而不是固定 sleep 猜时间；
    # 再 python scripts/smoke_w0.py，最后 kill 三个 pid

`env -u SERVER__PORT` 不能省：WorkBuddy 会注入 SERVER__PORT=51136，宽松绑定解析成
server.port 后所有服务都挤到同一个端口，第二个起不来。

多个 agent 并行时不要共用 8080 和 public schema —— 用 scripts/agent_env.sh 给自己分一套
（见 docs/并行开发-W1环境隔离.md）。设了 FLOORPLAN_DB_SCHEMA 后本脚本自动多两条守卫断言：
「会话 current_schema 就是目标 schema」+「public 未被本次运行改动」，后者挂了说明服务其实
还连在 public，这一轮所有断言都不作数。

判据全部落在库里真实状态（event_consumptions / consumer_offsets / outbox_events），
不看 HTTP 200、不看进程存活：任务卡要验的 5 条语义是——
  1) 事件能被轮询到并写 DONE；
  2) 同 (event_type, aggregate_key) 的两次发生各自独立消费（per-occurrence 幂等键，不是类型级去重）；
  3) event_type 订阅过滤生效（未订阅类型既不消费也不推游标）；
  4) 游标只按已订阅类型前进；
  5) 重置游标重放同一 event_id，幂等表仍只有一行（框架层幂等）。

发布端 NOT EXISTS 去重（卡面第 4 步）在这里验不了：JdbcOutboxPublisher 至今零生产调用方
（W2-CORE 才接线），冒烟只能从 HTTP/SQL 入口触发，而这条去重在 SQL 里插两条 PENDING
并不会被拦（拦的是发布端的 WHERE NOT EXISTS，不是表约束）。已如实记入交付说明，
需 W0 补 infrastructure 单测要另行扩权（不在卡面写集内）。
"""
import atexit
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from agent_smoke_lib import (DB_SCHEMA, SEARCH_PATH, assert_eq, get, login, ok, psql,
                             psql_exec, psql_one, summary, wait_ready, wait_until)

WEB = os.environ.get("SMOKE_WEB", "http://localhost:8080")      # 单体（不承载 /api/loans/**，实查无该路由）
AUTH = os.environ.get("SMOKE_AUTH", "http://127.0.0.1:8081")    # 登录：/api/auth/login 只存在于 auth-service
LOAN = os.environ.get("SMOKE_LOAN", "http://127.0.0.1:8085")    # 借据微服务
CREDIT = os.environ.get("SMOKE_CREDIT", "http://127.0.0.1:8083")  # 额度微服务（仅用于登记死契约现状）
CONSUMER = "loan-service"                                      # 幂等表/游标表的键，用服务名不是类名
TAG = "W0%d" % (int(time.time()) % 100000)

# common 里冻结的 LoanSnapshotDto 字段（驼峰，与 JSON 一一对应）。改这个列表就等于改契约，
# 必须先更新 docs/接口契约-事件与Feign.md 并通知全部 W1 agent。
DTO_FIELDS = ["id", "vin", "advanceId", "dealerId", "status", "principal", "dueDate"]

# 本轮造的事件 id 与借据行，跑完清理，不给后续 wave 留脏数据
EVENT_IDS = []
CREATED_LOAN_IDS = []
_CLEANED = False


def cleanup_rows():
    """删掉本轮造的 outbox / 幂等 / loans 行；重复调用只做一次。

    挂在 atexit 上是必需的而不是洁癖：实测第 5 步之后一旦抛异常（当时是隔壁 agent 没起
    credit-service，一条打印直接崩栈），第 6 步的清理根本不执行，脏事件行留在 schema 里，
    下一轮重跑撞 aggregate_key 的发布端去重、计数类断言也随之漂移——查起来像「代码偶发不稳」。
    """
    global _CLEANED
    if _CLEANED or not EVENT_IDS:
        return
    _CLEANED = True
    ids = ",".join(str(i) for i in EVENT_IDS)
    psql_exec("DELETE FROM event_consumptions WHERE event_id IN (%s)" % ids)
    psql_exec("DELETE FROM outbox_events WHERE id IN (%s)" % ids)
    if CREATED_LOAN_IDS:
        psql_exec("DELETE FROM loans WHERE id IN (%s)" % ",".join(str(i) for i in CREATED_LOAN_IDS))


atexit.register(cleanup_rows)   # 崩溃路径也要清；正常路径由第 6 步显式调用并顺带断言清干净了


def insert_event(event_type, aggregate_key, payload):
    """向 outbox 插一条 PENDING 事件并返回 id。

    这是**测试数据构造**，写在脚本里而非生产代码（AGENTS 铁律：不许在生产代码造数据）。
    绕过发布端直接插是因为 W0 阶段发布端还没有任何生产调用方，冒烟要验的是消费机制。
    """
    sql = ("INSERT INTO outbox_events (event_type, aggregate_key, payload, status) "
           "VALUES ('%s', '%s', '%s', 'PENDING') RETURNING id"
           % (event_type, aggregate_key, json.dumps(payload).replace("'", "''")))
    got = psql_one(sql)
    if not got:
        raise RuntimeError("事件插入失败（aggregate_key=%s），检查 V21/V26 是否已迁移" % aggregate_key)
    EVENT_IDS.append(int(got))
    return int(got)


def consumption(event_id, status=None):
    """查 (event_id, loan-service) 的幂等行，可选按状态过滤；返回行数。"""
    cond = "status = '%s' AND " % status if status else ""
    return int(psql_one("SELECT count(*) FROM event_consumptions WHERE %sevent_id = %d "
                        "AND consumer = '%s'" % (cond, event_id, CONSUMER)) or 0)


def payload(vin, advance_id, trace_id):
    """LOAN_CREATED 载荷：字段与 LoanApplicationService.LoanCreatedEvent 一一对应。

    dueDate 给真实日期而非 null——W1 接线后建借据要用它算还款计划，
    这里先把载荷形状钉死，避免 W1 用一条"日期为空"的事件测出假绿。
    """
    return {"vin": vin, "advanceId": advance_id, "dealerId": 1, "creditLineId": 1,
            "principal": 1000000, "dueDate": "2026-12-31", "gracePeriodDays": 3,
            "traceId": trace_id}


# ---------------------------------------------------------------------------
# 0. 预检：V26 迁移已由 web 启动应用，三个进程都活着
# ---------------------------------------------------------------------------
# 卡面只要求 web + loan-service 两个进程，这里多带一个 auth-service：
# /api/auth/login 只存在于 auth-service（单体没有该控制器），不带它拿不到 JWT，
# 而 web 的 /api/** 全在 JWT 拦截范围内 —— 少了 token，第 5 步只会收到 401，测不到契约。
print("== 预检：迁移与服务 ==")
cur_schema = psql_one("SELECT current_schema()")
print("   search_path=%s  current_schema=%s  （FLOORPLAN_DB_SCHEMA=%r）"
      % (SEARCH_PATH, cur_schema, DB_SCHEMA))
if DB_SCHEMA:
    # 隔离守卫：脚本查的库必须和服务写的库是同一个。
    # 不设这道闸时，脚本会退回去查 public（表都在、游标也在），断言照样全绿——最难查的假绿。
    assert_eq("隔离生效：会话 current_schema 就是目标 schema", DB_SCHEMA, cur_schema)
else:
    print("   [提示] 未设 FLOORPLAN_DB_SCHEMA → 跑 public。合并后全量回归就该这么跑。")
# 表存在性一律用 to_regclass（按 search_path 解析），和 JDBC 侧不带 schema 的写法同一个解析口径；
# 用 information_schema.table_name 过滤会把 public 的表也算进来，隔离时就骗人了。
tabs = psql("SELECT coalesce(to_regclass('event_consumptions')::text,'MISSING'), "
             "coalesce(to_regclass('consumer_offsets')::text,'MISSING')")
ok(tabs and "MISSING" not in tabs[0], "V26 两张表在当前 schema 已存在",
   "解析结果=%r" % (tabs,))
assert_eq("Flyway 在当前 schema 记录到 V26 成功", "26",
          psql_one("SELECT version FROM flyway_schema_history WHERE version = '26' AND success = true"))
PUBLIC_MAX_BEFORE = psql_one("SELECT coalesce(max(id),0) FROM public.outbox_events")
ok(wait_ready("/api/loans/by-vin/" + TAG, base=WEB, timeout=120),
   "web(8080) 已就绪（装配证据：单体带着 4 个 Feign client + EventDispatcher 能起来）", "120s 内未响应")
ok(wait_ready("/api/loans/by-vin/" + TAG, base=LOAN, timeout=120),
   "loan-service(8085) 已就绪", "120s 内未响应")
ok(wait_ready("/api/auth/login", base=AUTH, timeout=120),
   "auth-service(8081) 已就绪", "120s 内未响应")
login(base=AUTH)
print("   token 已取得（登录只有 auth-service 提供；token 靠 FLOORPLAN_JWT_SECRET 默认值跨服务通用）")
# credit-service 只用于第 5 步末尾那条「死契约」现状登记，不是必需进程 → 探测但不断言。
st_probe, _ = get("/api/credit-lines/34/available", base=CREDIT)
print("   credit-service(%s)：%s" % (CREDIT, "可达" if st_probe else "未运行（不影响本脚本判据）"))

# ---------------------------------------------------------------------------
# 1. 端到端消费：插一条 LOAN_CREATED → 轮询写 DONE
# ---------------------------------------------------------------------------
print("\n== 1. 事件消费闭环（LOAN_CREATED → DONE）==")
e1 = insert_event("LOAN_CREATED", TAG + "-A", payload(TAG + "VIN1", 900001, TAG + "-T1"))
done1, _, cost1 = wait_until(lambda: consumption(e1, "DONE") == 1, timeout=60)
ok(done1, "60s 内 event_consumptions 出现 (%d, '%s', 'DONE')" % (e1, CONSUMER),
   "实测耗时 %.1fs，最后状态=%r" % (cost1, psql_one(
       "SELECT status FROM event_consumptions WHERE event_id = %d AND consumer='%s'" % (e1, CONSUMER))))
assert_eq("消费成功时 attempts=1（首次即成功，没有重试）", "1", psql_one(
    "SELECT attempts FROM event_consumptions WHERE event_id = %d AND consumer='%s'" % (e1, CONSUMER)))
ok(psql_one("SELECT last_error FROM event_consumptions WHERE event_id = %d AND consumer='%s'"
            % (e1, CONSUMER)) in (None, ""), "成功路径 last_error 为空")

# ---------------------------------------------------------------------------
# 2. 同 aggregate_key 的两次发生 → 各自独立消费（幂等键是 event_id，不是类型+聚合键）
# ---------------------------------------------------------------------------
print("\n== 2. per-occurrence：同 aggregate_key 二次发生不被吞 ==")
e2 = insert_event("LOAN_CREATED", TAG + "-A", payload(TAG + "VIN2", 900002, TAG + "-T2"))
done2, _, cost2 = wait_until(lambda: consumption(e2, "DONE") == 1, timeout=60)
ok(done2, "第二条同 aggregate_key 事件也被消费（aggregate_key 只用于发布端去重，不是消费去重键）",
   "耗时 %.1fs" % cost2)
assert_eq("两条事件各一行幂等记录", "2", psql_one(
    "SELECT count(*) FROM event_consumptions WHERE consumer='%s' AND event_id IN (%d,%d)"
    % (CONSUMER, e1, e2)))

# ---------------------------------------------------------------------------
# 3. event_type 订阅过滤：未订阅类型既不消费也不推游标
# ---------------------------------------------------------------------------
print("\n== 3. 订阅过滤：未订阅的 event_type 不消费 ==")
e3 = insert_event("PAYMENT_MATCHED", TAG + "-P", {"paymentRef": TAG + "-PR", "traceId": TAG + "-T3"})
e4 = insert_event("CONTROL_EVENT_W0", TAG + "-C", {"traceId": TAG + "-T4"})
# 让 e1/e2 先被处理干净，再确认 e3/e4 永远排不进队列（同一批轮询里会被 IN 过滤掉）
time.sleep(4)
assert_eq("未订阅类型不写 event_consumptions（0 行）", 0, consumption(e3) + consumption(e4))
assert_eq("未订阅类型在 outbox 仍是 PENDING（连投递标记都没打）", 2, int(psql_one(
    "SELECT count(*) FROM outbox_events WHERE id IN (%d,%d) AND status='PENDING'" % (e3, e4)) or 0))
assert_eq("已订阅类型已被标 SENT", 2, int(psql_one(
    "SELECT count(*) FROM outbox_events WHERE id IN (%d,%d) AND status='SENT'" % (e1, e2)) or 0))

max_lc = int(psql_one("SELECT max(id) FROM outbox_events WHERE event_type='LOAN_CREATED'") or 0)
offset = int(psql_one("SELECT last_event_id FROM consumer_offsets WHERE consumer='%s'" % CONSUMER) or -1)
assert_eq("游标恰好停在最后一条已订阅事件，未越过 id 更大的未订阅事件", str(max_lc), str(offset))

# ---------------------------------------------------------------------------
# 4. 重置游标重放：同 event_id 再来一遍，幂等表仍只有一行
# ---------------------------------------------------------------------------
print("\n== 4. 重置游标重放 → 框架层幂等 ==")
before_updated = psql_one("SELECT updated_at FROM event_consumptions WHERE event_id = %d AND consumer='%s'"
                          % (e1, CONSUMER))
# 把游标倒回 0：dispatcher 的 writeOffset 带 last_event_id < :id，倒回后必然重新扫到 e1/e2。
# 这一步模拟的是「多实例互相覆盖游标」和「人工重放」两种真实场景。
psql_exec("UPDATE consumer_offsets SET last_event_id = 0, updated_at = now() WHERE consumer = '%s'" % CONSUMER)
back, _, cost4 = wait_until(
    lambda: int(psql_one("SELECT last_event_id FROM consumer_offsets WHERE consumer='%s'" % CONSUMER) or 0) >= max_lc,
    timeout=60)
ok(back, "重置游标后确实重新扫到本批事件（游标回到 %d）" % max_lc, "耗时 %.1fs" % cost4)
assert_eq("重放不新增幂等行：e1 仍只 1 行", 1, consumption(e1))
assert_eq("重放不新增幂等行：e2 仍只 1 行", 1, consumption(e2))
assert_eq("重放不重复累加 attempts", "1", psql_one(
    "SELECT attempts FROM event_consumptions WHERE event_id = %d AND consumer='%s'" % (e1, CONSUMER)))
assert_eq("已 DONE 的事件状态不被改写", "DONE", psql_one(
    "SELECT status FROM event_consumptions WHERE event_id = %d AND consumer='%s'" % (e1, CONSUMER)))
after_updated = psql_one("SELECT updated_at FROM event_consumptions WHERE event_id = %d AND consumer='%s'"
                         % (e1, CONSUMER))
ok(before_updated == after_updated,
   "重放走的是『跳过已终结事件』分支（consume 未被二次调用，updated_at 未变）",
   "before=%r after=%r" % (before_updated, after_updated))

# ---------------------------------------------------------------------------
# 5. 借据查询契约：拿真实生产者的响应逐字段校验 LoanSnapshotDto（不是自说自话）
# ---------------------------------------------------------------------------
# 为什么值得单独造一条 loans 行：LoanSnapshotDto 的 7 个字段是 W1 三个 agent 的编码依据，
# 如果字段名或可空性与 loan-service 真实响应不一致，最坏情况是 W1 全绿上线后拿到 null。
# 这里把「DTO 声明的字段」和「生产者实际吐的字段」放在一起比，漂移当场暴露。
print("\n== 5. 借据查询契约（对真实生产者校验冻结字段）==")
row_id = psql_one(
    "INSERT INTO loans (loan_no, advance_id, vin, dealer_id, credit_line_id, principal, due_date, "
    "status, grace_period_days, term_source, principal_outstanding) "
    "VALUES ('LN-%s', %d, '%sVIN9', 12, 34, 5400000, DATE '2026-12-31', 'ACTIVE', 3, 'CONTRACT', 5400000) "
    "RETURNING id" % (TAG, 900777, TAG))
ok(row_id is not None, "测试借据已构造（psql 造数，仅存在于本脚本）", "insert 未返回 id")
CREATED_LOAN_IDS.append(int(row_id or 0))   # 构造失败也不炸栈：后面的命中断言会明确红并给出原因

st_hit, body_hit = get("/api/loans/by-vin/" + TAG + "VIN9", base=LOAN)
assert_eq("命中的 VIN → 200", 200, st_hit)
assert_eq("成功信封 code=OK", "OK", body_hit.get("code"))
data = body_hit.get("data") or {}
missing = [f for f in DTO_FIELDS if f not in data]
ok(not missing, "LoanSnapshotDto 声明的 %d 个字段在真实响应里全部存在" % len(DTO_FIELDS),
   "缺失=%s，实际字段=%s" % (missing, sorted(data.keys())))
assert_eq("vin 字段名与值都对得上", TAG + "VIN9", data.get("vin"))
assert_eq("advanceId 是驼峰而非 advance_id", 900777, data.get("advanceId"))
assert_eq("金额是整数日元（BIGINT，不是小数/字符串）", 5400000, data.get("principal"))
assert_eq("status 用枚举名字符串", "ACTIVE", data.get("status"))
assert_eq("dueDate 是 ISO 日期串", "2026-12-31", data.get("dueDate"))

st_miss, body_miss = get("/api/loans/by-vin/" + TAG + "NOTEXIST", base=LOAN)
assert_eq("查不到 → 404（契约冻结的是 404，不是 200+data=null）", 404, st_miss)
assert_eq("404 仍是统一 ApiResponse，code=NOT_FOUND_001", "NOT_FOUND_001", body_miss.get("code"))

# 只打印不断言：GET /api/credit-lines/{id}/available 目前全仓无实现（credit-service 回 500，
# 走静态资源兜底）。W1-CREDIT 实现后这里应变成 200 + 整数；若此刻写成断言，
# 要么现在红、要么将来绿了反而红，两种都是假信号。留给文档登记。
# credit-service 不在本脚本的必需进程清单里（web/auth/loan 才必需），没起时 st=0 → 如实打印，
# 不拿它当结论，也不许它把整轮冒烟带崩。
st_avail, body_avail = get("/api/credit-lines/34/available", base=CREDIT)
if st_avail == 0:
    print("   [现状登记·跳过] credit-service(%s) 未运行 → %s 这条契约本轮未取到响应"
          % (CREDIT, body_avail.get("message", "")[:90]))
else:
    print("   [现状登记] CreditLineQueryClient 的 GET /api/credit-lines/34/available → HTTP %s %s"
          % (st_avail, json.dumps(body_avail, ensure_ascii=False)[:120]))
    print("   （W1-CREDIT 需实现为 200 + ApiResponse<Long>；W0 不擅自判定这条未定源契约）")

# ---------------------------------------------------------------------------
# 6. 清理本轮造的测试数据（幂等表/事件表都留给后续 wave 复用，只删自己的行）
# ---------------------------------------------------------------------------
print("\n== 清理 ==")
ids = ",".join(str(i) for i in EVENT_IDS)
cleanup_rows()
if CREATED_LOAN_IDS:
    assert_eq("本轮测试借据已删除", "0", psql_one(
        "SELECT count(*) FROM loans WHERE id IN (%s)" % ",".join(str(i) for i in CREATED_LOAN_IDS)))
assert_eq("本轮事件与幂等行已清空", "0", psql_one(
    "SELECT count(*) FROM outbox_events WHERE id IN (%s)" % ids))
if DB_SCHEMA:
    # 真隔离的判据不是「我的 schema 干净」，而是「public 一行都没被我碰到」。
    # 这条挂了就是 schema 没生效（服务连的还是 public），后面所有断言都不作数。
    assert_eq("public.outbox_events 未被本次运行改动", PUBLIC_MAX_BEFORE,
              psql_one("SELECT coalesce(max(id),0) FROM public.outbox_events"))
print("游标保持在 %d（不回滚：历史事件重放会撞已删的幂等行，保留更安全）" % max_lc)

sys.exit(summary("smoke_w0(%s)" % TAG))
