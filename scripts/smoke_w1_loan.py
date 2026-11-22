"""smoke_w1_loan.py —— W1-LOAN 借据消费者（LOAN_CREATED 建据）冒烟。

卡面 docs/AGENT_TASK-w1-loan.md。判据一律查库（loans / repayment_plans /
loan_status_history / event_consumptions），不看 HTTP 200——事件消费是异步的，
「回调返回 200」和「借据建出来了」之间隔着 dispatcher 的 1s 轮询，前者不是后者的证据。

跑法（启动 + 测试 + 清理同一条命令，AGENTS 坑 2）：

    unset FLOORPLAN_DB_SCHEMA SPRING_DATASOURCE_URL      # 交付判据要求跑 public
    bash run-microservices.sh start &                  # 全部服务 + 网关，就绪后保活
    RUN=$!; until grep -q 服务已就绪 logs/w-run/run.log; do sleep 1; done
    PYTHONIOENCODING=utf-8 python scripts/smoke_w1_loan.py
    kill $RUN; bash run-microservices.sh stop

必需进程：gateway(18080) + web(8080，产生真实事件) + auth(8081，登录) +
credit-service(8083，建经销商/额度) + loan-service(8085，消费者与借据查询)。

七条语义：
  1) 端到端：真实链路（申请→放款→银行回调）产生的事件，被消费成一条 DRAFT 借据，
     字段取自事件而不是请求体。**卡面原话是这条「只能在 W1 合并之后加」**——发布端与消费端
     同批落地，所以现在就必须加，否则两侧各自绿、中间没人接线也看不见（AGENTS §12 反模式）；
  2) 载荷没有 dueDate ⇒ 不生成还款计划（本期口径：Q-EVT3 未定源，到期日不凭空造），
     借据仍然建成 —— 缺期限不该挡住建据，否则回款线全部卡死；
  3) loan_status_history 落一条 DRAFT，reason 里带 traceId（跨服务排障要能串起来）；
  4) 两层幂等分工：新 event_id、同 advanceId 的第二次投递被框架层各记一行 DONE，
     业务层靠 loan_no 唯一键只留一笔债；
  5) 带 dueDate 的事件 ⇒ 生成一期还款计划（principal_due = total_due = principal）——
     用来证明「第 2 条没计划」是发布端不发字段，不是消费路径坏了；
  6) 坏载荷（缺 dealerId，loans.dealer_id NOT NULL）⇒ 不许记 DONE，重试到 attempts=3 置 DEAD，
     且不留下半条借据。这是消费者新逻辑最重要的一条回归防线：静默 return 会把坏载荷记成 DONE，
     游标照样前进，债就永远丢了；
  7) GET /api/loans/by-vin/{vin} 的响应字段与 common 冻结的 LoanSnapshotDto 逐字段对齐
     （名字、类型、可空性），查不到走 404 + NOT_FOUND_001 而不是 200 + data=null。

坏载荷测试放在最后一步：事件 FAILED 期间该消费者的游标不前进（EventDispatcher 语义），
先跑它会拖慢前面几条的收敛，而不是让它去追一条已经过去的号。
"""
import atexit
import hashlib
import hmac
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from agent_smoke_lib import (DB_SCHEMA, SEARCH_PATH, assert_eq, assert_fail, call, get, login,
                             login_as, ok, psql, psql_exec, psql_one, summary, wait_ready,
                             wait_until)

WEB = os.environ.get("SMOKE_WEB", "http://localhost:8080")
AUTH = os.environ.get("SMOKE_AUTH", "http://127.0.0.1:8081")
# 业务请求走网关（口径同基线）。/api/dealers/**、/api/credit-lines/** 属 credit-service，
# 直连单体必然 404；/api/loans/** 仍另留一条打 loan-service 直连的契约断言，
# 因为卡面要的是「对真实生产者逐字段校验」，经网关会混进路由层的行为。
GW = os.environ.get("SMOKE_GW", "http://127.0.0.1:18080")
LOAN = os.environ.get("SMOKE_LOAN", "http://127.0.0.1:8085")
WEBHOOK_SECRET = os.environ.get("PAYMENT_WEBHOOK_SECRET", "dev-webhook-secret-change-me")
CONSUMER = "loan-service"        # 幂等表/游标表的键：服务名，不是类名

RUN = int(time.time())
TAG = "W1L%d" % (RUN % 100000)
VIN_REAL = "W1LR%06d" % (RUN % 1000000)   # 走完整链路，事件由发布端真实产生
INVOICE = 1_850_000
RATIO = 0.90
EXPECTED = int(round(INVOICE * RATIO))    # 1,665,000
APPROVED_LIMIT = 50_000_000

# 合成事件的假 advanceId 号段。为什么用 9.2e9 起：真实 advances.id 是小整数序列，够不着；
# 9.0e9+epoch 已被 smoke_w0.py 的每轮唯一号占用，换一个基数才不会在同一秒里撞上它的键。
SYN1 = 9_200_000_000 + RUN     # 带 dueDate（第 5 步）
SYN2 = SYN1 + 1                # 坏载荷缺 dealerId（第 6 步）
DUE = "2027-03-31"

DTO_FIELDS = ["id", "vin", "advanceId", "dealerId", "status", "principal", "dueDate"]

ROLE_PWD = {"operator": "operator123", "review_op": "review123", "cashier": "cashier123"}
TOK = {}


def role(name):
    if name not in TOK:
        TOK[name] = login_as(name, ROLE_PWD[name], base=AUTH)
    return TOK[name]


def sign(adv_no, ts, ref_or_msg):
    return hmac.new(WEBHOOK_SECRET.encode("utf-8"),
                    ("%s.%s.%s" % (adv_no, ts, ref_or_msg or "")).encode("utf-8"),
                    hashlib.sha256).hexdigest()


def bank_confirm(adv_no, payment_ref=None):
    ts = str(int(time.time()))
    return call("POST", "/api/advances/%s/confirm" % adv_no,
                {"paymentRef": payment_ref} if payment_ref else {}, token="",
                headers={"X-Payment-Signature": sign(adv_no, ts, payment_ref),
                         "X-Payment-Timestamp": ts}, base=GW)


# 本轮清理对象：合成事件 id、真实+合成的 aggregate_key、被删借据的 loan_id
EVENT_IDS = []
AGG_KEYS = []
CLEANED_LOAN_IDS = []
_CLEANED = False


def insert_event(agg_key, payload, status="PENDING"):
    """psql 直插一条 LOAN_CREATED（测试数据构造只写在脚本里，AGENTS 铁律）。

    绕过发布端直插是**故意的**：第 4/5/6 步要造的是「框架已投递/坏载荷/带期限」这三种
    发布端发不出来的形状。真实发布链路只在第 1 步走 HTTP，两种入口各验各的语义。
    """
    sql = ("INSERT INTO outbox_events (event_type, aggregate_key, payload, status) "
           "VALUES ('LOAN_CREATED', '%s', '%s', '%s') RETURNING id"
           % (agg_key, json.dumps(payload).replace("'", "''"), status))
    got = psql_one(sql)
    if not got:
        raise RuntimeError("合成事件插入失败（aggregate_key=%s），检查 V21/V26 是否已迁移" % agg_key)
    EVENT_IDS.append(int(got))
    if str(agg_key) not in [str(k) for k in AGG_KEYS]:
        AGG_KEYS.append(str(agg_key))
    return int(got)


def drop_loans(nos):
    """按 loan_no 删借据并连子表一起删；返回被删掉的 loan_id 列表。

    loans / repayment_plans / loan_status_history 之间没有外键（V16 实查），删父表不级联；
    孤儿还款计划在 loan_id 被复用那天会撞 uk_rp_loan_period(loan_id, period_no)。
    """
    if not nos:
        return []
    cond = "loan_no IN (%s)" % ",".join("'%s'" % n for n in nos)
    rows = psql("SELECT id FROM loans WHERE %s" % cond) or []
    ids = [r[0] for r in rows]
    if ids:
        inlist = ",".join(ids)
        psql_exec("DELETE FROM repayment_plans WHERE loan_id IN (%s)" % inlist)
        psql_exec("DELETE FROM loan_status_history WHERE loan_id IN (%s)" % inlist)
    psql_exec("DELETE FROM loans WHERE %s" % cond)
    return ids


def cleanup():
    global _CLEANED
    if _CLEANED:
        return
    _CLEANED = True
    if EVENT_IDS:
        ids = ",".join(str(i) for i in EVENT_IDS)
        psql_exec("DELETE FROM event_consumptions WHERE event_id IN (%s)" % ids)
        psql_exec("DELETE FROM outbox_events WHERE id IN (%s)" % ids)
    if AGG_KEYS:
        # 真实链路那条事件的行不在 EVENT_IDS 里（是发布端写的），按键兜一遍才清得干净
        keys = ",".join("'%s'" % k for k in AGG_KEYS)
        psql_exec("DELETE FROM event_consumptions WHERE event_id IN "
                  "(SELECT id FROM outbox_events WHERE event_type='LOAN_CREATED' AND aggregate_key IN (%s))" % keys)
        psql_exec("DELETE FROM outbox_events WHERE event_type='LOAN_CREATED' AND aggregate_key IN (%s)" % keys)
    CLEANED_LOAN_IDS.extend(drop_loans(["LN-%s" % k for k in AGG_KEYS]))


atexit.register(cleanup)


# ---------------------------------------------------------------------------
# 0. 预检
# ---------------------------------------------------------------------------
print("== 预检：三个进程与消费端所在 schema ==")
cur_schema = psql_one("SELECT current_schema()")
print("   search_path=%s  current_schema=%s  （FLOORPLAN_DB_SCHEMA=%r）" % (SEARCH_PATH, cur_schema, DB_SCHEMA))
if DB_SCHEMA:
    assert_eq("隔离生效：脚本会话 current_schema 就是目标 schema", DB_SCHEMA, cur_schema)
else:
    print("   [提示] 未设 FLOORPLAN_DB_SCHEMA → 跑 public")
ok(wait_ready("/api/loans/by-vin/" + TAG, base=LOAN, timeout=120), "loan-service(8085) 已就绪", "120s 内无响应")
ok(wait_ready("/api/advances/" + TAG, base=WEB, timeout=120), "web(8080) 已就绪", "120s 内无响应")
ok(wait_ready("/api/auth/login", base=AUTH, timeout=120), "auth-service(8081) 已就绪", "120s 内无响应")
ok(wait_ready("/api/advances/" + TAG, base=GW, timeout=120), "gateway(18080) 已就绪", "120s 内无响应")
login(base=AUTH)
for _n in ("operator", "review_op", "cashier"):
    assert_eq("岗位令牌已取得：%s" % _n, True, bool(role(_n)))
def q(name):
    """表名按目标 schema 显式限定。不带限定名的 to_regclass 会顺 search_path 掉回 public，
    于是「隔离 schema 里没迁移成功」也能报「表在」——和 smoke_w0.py 同一个洞（PROGRESS W0-9）。"""
    return ("%s.%s" % (DB_SCHEMA, name)) if DB_SCHEMA else name


def one_json(sql):
    """把一行多列读成字典：SQL 必须只返回**一列** JSON 文本。

    为什么不能直接多列 SELECT：公共库 psql() 按 tab 拆列，而 `psql -tA` 的列分隔符默认是竖线，
    实测 `SELECT 1,'a|b',3` 回来是 [['1|a|b|3']]——多列读会整体错位成第一个字段，
    「第 0 行第 1 列」这种下标要么 IndexError 要么拿到黏在一起的字符串。
    根治是给 agent_smoke_lib 加 `-F` 制表符，但那是全波次共用文件，需 owner 提案 + 用户点头，
    所以本轮先在脚本侧用 JSON 单列，并把这条坑写进 HANDOFF。JSON 还顺带把 NULL 显式化成 null，
    不像 psql 的 -tA 把 NULL 打成空串、与空字符串分不开。
    """
    raw = psql_one(sql)
    return json.loads(raw) if raw else None


def many_json(sql):
    """同 one_json，但用于多行（SQL 需包成 json_agg）。"""
    raw = psql_one(sql)
    return json.loads(raw) if raw else []


for _t in ("loans", "repayment_plans", "event_consumptions"):
    # 逐表单列判定。旧写法 psql("SELECT a,b,c") 配 `"MISSING" not in tabs[0]` 是**恒真**的：
    # tabs[0] 是列表，in 走的是元素相等而不是子串，MISSING 再怎么也在里面也照样 PASS。
    # 也不能比布尔文本：本库实测 (x IS NOT NULL)::text 返回 'true'，不是 psql 常见的 't'。
    reg = psql_one("SELECT coalesce(to_regclass('%s')::text,'MISSING')" % q(_t))
    ok(reg != "MISSING", "判据落库的表在当前 schema 已存在：%s" % _t, "to_regclass=%r" % reg)

st, res = call("POST", "/api/dealers", {"dealerCode": "DL-" + TAG, "name": "W1 消费端冒烟经销商",
                                        "riskGrade": "B", "businessYears": 10}, base=GW)
ok(st == 201, "创建经销商 -> %s" % st, json.dumps(res, ensure_ascii=False)[:200])
DEALER_ID = (res.get("data") or {}).get("id")
st, res = call("POST", "/api/credit-lines", {"dealerId": DEALER_ID, "approvedLimit": APPROVED_LIMIT}, base=GW)
ok(st == 201, "核定额度 -> %s" % st, json.dumps(res, ensure_ascii=False)[:200])
CREDIT_LINE_ID = (res.get("data") or {}).get("id")
if not (DEALER_ID and CREDIT_LINE_ID):
    cleanup()
    sys.exit(summary("smoke_w1_loan(%s)：预检未建出经销商/额度" % TAG))

# ---------------------------------------------------------------------------
# 1. 端到端：真实链路 → 事件 → DRAFT 借据
# ---------------------------------------------------------------------------
print("\n== 1. 端到端（真实发布 + 真实消费）==")
st, res = call("POST", "/api/vehicles/batch-ingest", {
    "dealerId": DEALER_ID, "creditLineId": CREDIT_LINE_ID,
    "purchaseChannel": "OEM", "supplierName": "W1 冒烟厂家",
    "vehicles": [{"vin": VIN_REAL, "brand": "TOYOTA", "model": "W1L", "modelYear": 2026,
                  "invoiceAmount": INVOICE, "advanceRatio": RATIO, "color": "WHITE"}],
    "idempotencyKey": "ingest-" + VIN_REAL}, token=role("review_op"), base=GW)
assert_eq("建账 -> 201", 201, st)
st, res = call("POST", "/api/advances", {"creditLineId": CREDIT_LINE_ID, "vin": VIN_REAL},
               headers={"Idempotency-Key": "adv-" + VIN_REAL}, token=role("operator"), base=GW)
assert_eq("垫款申请 -> 201", 201, st)
adv_no = (res.get("data") or {}).get("advanceId")
st, res = call("POST", "/api/advances/%s/disburse" % adv_no, {}, token=role("cashier"), base=GW)
assert_eq("放款 -> 200", 200, st)
ADV_ID = int(psql_one("SELECT id FROM advances WHERE advance_no='%s'" % adv_no) or 0)
AGG_KEYS.append(str(ADV_ID))
st, conf = bank_confirm(adv_no, "BANK-REF-" + VIN_REAL)
assert_eq("银行回调 -> 200", 200, st)
LOAN_NO = "LN-%d" % ADV_ID

got, _, cost = wait_until(
    lambda: psql_one("SELECT id FROM loans WHERE loan_no='%s'" % LOAN_NO), timeout=60)
ok(got, "60s 内借据 %s 被消费建出" % LOAN_NO, "耗时 %.1fs，最后查询=%r" % (cost, LOAN_NO))
if not got:
    # 建据没发生，后面所有 loans 断言只会刷一堆 None 比较；先自证是消费没跑还是脚本没等够
    print("   [诊断] event_consumptions 行=%r" % (psql(
        "SELECT id, status, attempts, last_error FROM event_consumptions WHERE consumer='%s' "
        "AND event_id IN (SELECT id FROM outbox_events WHERE aggregate_key='%s')"
        % (CONSUMER, ADV_ID)),))
    cleanup()
    sys.exit(summary("smoke_w1_loan(%s)：借据未建出，后续断言无法进行" % TAG))
LOAN_ID = int(psql_one("SELECT id FROM loans WHERE loan_no='%s'" % LOAN_NO) or 0)

row = one_json("SELECT row_to_json(x) FROM (SELECT loan_no, advance_id, vin, dealer_id, "
               "credit_line_id, principal, status, due_date::text AS due_date, term_source "
               "FROM loans WHERE id=%d) x" % LOAN_ID)
if not ok(row is not None, "借据行可读回", "loan_no=%s" % LOAN_NO):
    cleanup()
    sys.exit(summary("smoke_w1_loan(%s)：借据行读不回，后续断言无法进行" % TAG))
assert_eq("loan_no = 'LN-' + advanceId（业务幂等键的形状）", LOAN_NO, row["loan_no"])
assert_eq("advance_id 取自事件", ADV_ID, row["advance_id"])
assert_eq("vin 取自事件", VIN_REAL, row["vin"])
assert_eq("dealer_id 取自事件（车辆归属）", int(DEALER_ID), row["dealer_id"])
assert_eq("credit_line_id 取自事件", int(CREDIT_LINE_ID), row["credit_line_id"])
assert_eq("principal = 库内垫款金额（整数日元，BIGINT）", EXPECTED, row["principal"])
assert_eq("新借据初值 DRAFT（不是 ACTIVE——激活是另一条状态迁移）", "DRAFT", row["status"])
assert_eq("term_source = CONTRACT", "CONTRACT", row["term_source"])

# ---------------------------------------------------------------------------
# 2. 无 dueDate ⇒ 无还款计划，但借据照建
# ---------------------------------------------------------------------------
print("\n== 2. 载荷无 dueDate ⇒ 不生成还款计划（Q-EVT3 未定源的本期口径）==")
assert_eq("载荷无 dueDate ⇒ 借据 due_date 为 NULL（JSON 里是 null，不是空串）", None, row["due_date"])
assert_eq("repayment_plans 零行", "0", psql_one(
    "SELECT count(*) FROM repayment_plans WHERE loan_id=%d" % LOAN_ID))
print("   [口径] 发布端不发 dueDate/gracePeriodDays，消费端 generateRepaymentPlan 见 null 只 WARN，")
print("   所以「有计划表为空」是正确状态，不是缺陷；权威源签字后这条断言要随之改。")

# ---------------------------------------------------------------------------
# 3. 状态史带 traceId
# ---------------------------------------------------------------------------
print("\n== 3. loan_status_history 落一条 DRAFT，reason 带事件里的 traceId ==")
hist = many_json("SELECT json_agg(x)::text FROM (SELECT from_status, to_status, operator, reason "
                 "FROM loan_status_history WHERE loan_id=%d ORDER BY occurred_at DESC LIMIT 1) x" % LOAN_ID)
assert_eq("状态史恰一条", 1, len(hist))
ev_payload = psql_one("SELECT payload FROM outbox_events WHERE event_type='LOAN_CREATED' "
                      "AND aggregate_key='%s' ORDER BY id LIMIT 1" % ADV_ID)
ev_trace = (json.loads(ev_payload or "{}") or {}).get("traceId")
if hist:
    h = hist[0]
    assert_eq("首条 from_status 为 null（从无状态到 DRAFT，不是字符串 'null'）", None, h["from_status"])
    assert_eq("to_status = DRAFT", "DRAFT", h["to_status"])
    assert_eq("operator 记 system（事件驱动，不是某个岗的人）", "system", h["operator"])
    ok("traceId=" in (h["reason"] or ""), "reason 里带 traceId（跨服务排障能串起来）",
       "reason=%r" % h["reason"])
    assert_eq("事件里的 traceId == 银行回调响应信封的 traceId（消费端用的就是这个值）",
              conf.get("traceId"), ev_trace)
    ok(bool(ev_trace) and ev_trace in (h["reason"] or ""),
       "reason 里的 traceId 就是事件里那个，不是消费者另造的",
       "reason=%r traceId=%r" % (h["reason"], ev_trace))

# ---------------------------------------------------------------------------
# 4. 两层幂等：第二次投递（新 event_id、同 advanceId）
# ---------------------------------------------------------------------------
print("\n== 4. 两层幂等：新 event_id 同 advanceId 再投一次 ==")
# 这里必须 psql 直插：走真回调会被发布端 WHERE NOT EXISTS 拦掉（smoke_w2_core 第 8 步验的正是它），
# 而本步要验的是「拦不住的时候业务层还剩不剩第二笔债」。
dup_payload = {"vin": VIN_REAL, "traceId": TAG + "-DUP", "advanceId": ADV_ID,
               "dealerId": int(DEALER_ID), "creditLineId": int(CREDIT_LINE_ID),
               "principal": EXPECTED}
e_dup = insert_event(str(ADV_ID), dup_payload)
got, _, cost = wait_until(lambda: psql_one(
    "SELECT status FROM event_consumptions WHERE event_id=%d AND consumer='%s'" % (e_dup, CONSUMER)) == "DONE",
    timeout=60)
ok(got, "重复投递被框架层记 DONE（耗时 %.1fs）" % cost, "实测 status=%r" % psql_one(
    "SELECT status FROM event_consumptions WHERE event_id=%d AND consumer='%s'" % (e_dup, CONSUMER)))
assert_eq("框架层：两个 event_id 各一行幂等记录", 2, int(psql_one(
    "SELECT count(*) FROM event_consumptions WHERE consumer='%s' AND status='DONE' "
    "AND event_id IN (SELECT id FROM outbox_events WHERE aggregate_key='%s')" % (CONSUMER, ADV_ID)) or 0))
assert_eq("业务层：借据仍只一笔（loan_no 唯一键 + 按 VIN 复用）", "1", psql_one(
    "SELECT count(*) FROM loans WHERE loan_no='%s'" % LOAN_NO))
assert_eq("复用分支未产生第二条状态史", "1", psql_one(
    "SELECT count(*) FROM loan_status_history WHERE loan_id=%d" % LOAN_ID))

# ---------------------------------------------------------------------------
# 5. 带 dueDate 的合法事件 ⇒ 一期还款计划（证明第 2 条是发布端不发，不是消费路径坏）
# ---------------------------------------------------------------------------
print("\n== 5. 带 dueDate 的事件 ⇒ 生成一期还款计划 ==")
syn_vin = TAG + "V5"
e5 = insert_event(str(SYN1), {"vin": syn_vin, "traceId": TAG + "-T5", "advanceId": SYN1,
                              "dealerId": 12, "creditLineId": 34, "principal": 5_400_000,
                              "dueDate": DUE, "gracePeriodDays": 3})
got, _, cost = wait_until(lambda: psql_one(
    "SELECT id FROM loans WHERE loan_no='LN-%d'" % SYN1), timeout=60)
ok(got, "60s 内合成事件建出借据 LN-%d" % SYN1, "耗时 %.1fs" % cost)
syn_id = psql_one("SELECT id FROM loans WHERE loan_no='LN-%d'" % SYN1)
if syn_id:
    assert_eq("due_date 落到事件给的那一天", DUE, psql_one(
        "SELECT due_date::date::text FROM loans WHERE id=%s" % syn_id))
    assert_eq("grace_period_days=3 原样落库", "3", psql_one(
        "SELECT grace_period_days FROM loans WHERE id=%s" % syn_id))
    plan = many_json("SELECT json_agg(x)::text FROM (SELECT period_no, "
                     "due_date::text AS due_date, principal_due, interest_due, total_due, status "
                     "FROM repayment_plans WHERE loan_id=%s) x" % syn_id)
    assert_eq("生成一期计划", 1, len(plan))
    if plan:
        p = plan[0]
        assert_eq("期号=1", 1, p["period_no"])
        assert_eq("计划到期日=借据到期日", DUE, p["due_date"])
        assert_eq("本金=事件 principal（一次性到期还本）", 5_400_000, p["principal_due"])
        assert_eq("利息 0（本卡不涉利率口径）", 0, p["interest_due"])
        assert_eq("合计=本金", 5_400_000, p["total_due"])
        assert_eq("计划状态 PENDING", "PENDING", p["status"])
else:
    assert_fail("带 dueDate 的合成事件未建出借据，第 5 步无法继续")

# ---------------------------------------------------------------------------
# 6. 契约字段对齐：对真实借据逐字段比 LoanSnapshotDto
# ---------------------------------------------------------------------------
print("\n== 6. by-vin 契约与 LoanSnapshotDto 对齐 ==")
st, body = get("/api/loans/by-vin/" + VIN_REAL, base=LOAN)
assert_eq("命中 VIN -> 200", 200, st)
assert_eq("成功信封 code=OK", "OK", body.get("code"))
data = body.get("data") or {}
# 契约 §2.1 的 7 个字段里 dueDate 标「可空」，其余本期必有值 ⇒ 断言按**值语义**分两组，
# 不笼统要求「7 个键都在」。两侧都配了 spring.jackson.default-property-inclusion=non_null
# （web/src/main/resources/application.yml:53、loan-service/src/main/resources/application.yml:18），
# null 字段连键一起被丢掉：把「键必须存在」写成断言，是在把正确实现判成红；
# 真正要防的是「W1 按 DTO 取 dueDate 会不会拿到意外的东西」，答案是缺键映射成 null，可空类型兜住。
REQUIRED_FIELDS = ["id", "vin", "advanceId", "dealerId", "status", "principal"]
missing = [f for f in REQUIRED_FIELDS if f not in data]
ok(not missing, "本期必有值的 %d 个契约字段在真实响应里全部存在" % len(REQUIRED_FIELDS),
   "缺失=%s 实际=%s" % (missing, sorted(data.keys())))
assert_eq("id 就是刚建出的借据主键", LOAN_ID, data.get("id"))
assert_eq("advanceId 是驼峰数字主键", ADV_ID, data.get("advanceId"))
assert_eq("金额是整数不是字符串/小数", EXPECTED, data.get("principal"))
assert_eq("status 用枚举名字符串", "DRAFT", data.get("status"))
assert_eq("vin 与建账 VIN 相同", VIN_REAL, data.get("vin"))
assert_eq("dealerId 取自车辆归属", int(DEALER_ID), data.get("dealerId"))
ok(data.get("dueDate") is None,
   "dueDate 本期取不到值（发布端按 Q-EVT3 未签字不发 + non_null 把 null 键丢掉）",
   "值=%r 键在否=%s" % (data.get("dueDate"), "dueDate" in data))
print("   [漂移登记] 真实响应是 Loan 实体全字段（%d 个），冻结契约 LoanSnapshotDto 只声明 %d 个，"
      "多出 %d 个。多字段本身不是缺陷（消费端反序列化忽略未知字段），但契约文档要写清"
      "「DTO 是实体子集、以实体响应为准」，否则按 DTO 编码的人会以为响应只有 7 个字段。"
      % (len(data), len(DTO_FIELDS), len(data) - len(DTO_FIELDS)))
print("   [死契约] LoanSnapshotDto / LoanQueryClient 全仓仍零生产调用方（实查只有定义与本轮冒烟），")
print("   这条对齐是拿真实生产者测的；第一个真实调用方出现时它才真正兜住漂移。")
st, body = get("/api/loans/by-vin/" + TAG + "MISSING", base=LOAN)
assert_eq("查不到 -> 404（契约冻结 404，不是 200+data=null）", 404, st)
assert_eq("404 仍是统一 ApiResponse", "NOT_FOUND_001", body.get("code"))

# ---------------------------------------------------------------------------
# 7. 坏载荷 ⇒ 不许记 DONE，重试三次置 DEAD，且不留半条借据
# ---------------------------------------------------------------------------
print("\n== 7. 坏载荷（缺 dealerId）⇒ FAILED×3 → DEAD，不建半条借据 ==")
bad_vin = TAG + "V7"
e7 = insert_event(str(SYN2), {"vin": bad_vin, "traceId": TAG + "-T7", "advanceId": SYN2,
                              "creditLineId": 34, "principal": 100})   # 故意不给 dealerId
got, _, cost = wait_until(lambda: psql_one(
    "SELECT status FROM event_consumptions WHERE event_id=%d AND consumer='%s'" % (e7, CONSUMER)) == "DEAD",
    timeout=90)
ok(got, "坏载荷最终置 DEAD（不是 DONE）", "耗时 %.1fs，实测=%r" % (
    cost, psql_one("SELECT status FROM event_consumptions WHERE event_id=%d AND consumer='%s'" % (e7, CONSUMER))))
assert_eq("attempts 达到重试上限 3", "3", psql_one(
    "SELECT attempts FROM event_consumptions WHERE event_id=%d AND consumer='%s'" % (e7, CONSUMER)))
err = psql_one("SELECT last_error FROM event_consumptions WHERE event_id=%d AND consumer='%s'" % (e7, CONSUMER))
ok("dealerId" in (err or ""), "last_error 说清缺的是哪个字段（排障不用翻代码）", "last_error=%r" % (err or "")[:200])
assert_eq("坏载荷没有建出借据", "0", psql_one(
    "SELECT count(*) FROM loans WHERE loan_no='LN-%d'" % SYN2))
assert_eq("坏载荷没有留下状态史孤儿", "0", psql_one(
    "SELECT count(*) FROM loan_status_history WHERE reason LIKE '%%%s%%'" % (TAG + "-T7")))
print("   [为什么重要] 消费者若把缺字段静默 return，框架会记 DONE、游标照常前进，这笔债就永远")
print("   不存在了——DPD、画像、逾期、对账四条线一起空转，而且没有任何报警。")

# ---------------------------------------------------------------------------
# 8. 边界自证
# ---------------------------------------------------------------------------
print("\n== 8. 边界自证 ==")
print("   [不在本轮] 借据 ACTIVE 化、逾期 DPD 计算、解押回补属 W1-LOAN 卡面的贷后部分，")
print("   本脚本只验「事件 → 借据」这一条新建的链路。")
print("   [不在本轮] DEAD 事件的人工重放没有实现（EventDispatcher 类注释已留接缝给 W3 收口），")
print("   所以第 7 步只验到「置 DEAD」，不验「重放后能补建」。")
print("   [依赖声明] 第 1 步的端到端依赖 W2-CORE 的发布端已接线；发布端自身的键与载荷")
print("   形状在 smoke_w2_core.py 里独立成断言，两侧红法不同，能一眼看出断点在发布还是消费。")

print("\n== 清理 ==")
cleanup()
for k in AGG_KEYS:
    assert_eq("借据 %s 已删除（含子表）" % ("LN-%s" % k), "0", psql_one(
        "SELECT count(*) FROM loans WHERE loan_no='LN-%s'" % k))
assert_eq("本轮事件行已清空", "0", psql_one(
    "SELECT count(*) FROM outbox_events WHERE id IN (%s) OR aggregate_key IN (%s)"
    % (",".join(str(i) for i in (EVENT_IDS or [0])),
       ",".join("'%s'" % k for k in AGG_KEYS))))
print("说明：本轮经销商/额度/车辆/垫款保留在库里（时间戳唯一号，不参与唯一键冲突）。")

sys.exit(summary("smoke_w1_loan(%s)" % TAG))
