"""smoke_w2_core.py —— W2-CORE 借据生产者（LOAN_CREATED 发布端）冒烟。

卡面 docs/AGENT_TASK-w2-core.md。判据一律落在 **outbox_events 库内真实状态**，不看 HTTP 200：
「200 + 没发事件」是这个项目最经典的假绿（AGENTS §12「表建了就算功能有了」的孪生版本）。

跑法（启动 + 测试 + 清理必须在同一条命令里，AGENTS 坑 2：Git Bash 后台 JVM 随父 shell 回收）：

    unset FLOORPLAN_DB_SCHEMA SPRING_DATASOURCE_URL      # 交付判据要求跑 public
    bash run-microservices.sh start &                  # 全部服务 + 网关，就绪后保活
    RUN=$!; until grep -q 服务已就绪 logs/w-run/run.log; do sleep 1; done
    PYTHONIOENCODING=utf-8 python scripts/smoke_w2_core.py
    kill $RUN; bash run-microservices.sh stop

必需进程：gateway(18080，业务请求入口) + web(8080，发布端与垫款/车辆都在这进程里) +
auth(8081，登录与岗位令牌) + credit-service(8083，/api/dealers/**、/api/credit-lines/** 归它)。
loan-service **不需要**：本脚本只验「事件发出去了没」，「消费成不成」在 smoke_w1_loan.py。
这也是卡面的边界自证——发布端与消费端各自的断言分开写，一处红不会伪装成另一处绿。

八条语义（对应卡面「验收」清单）：
  1) 放款停在 PROCESSING 时没有事件 —— 钱没到，债不成立；
  2) 非法签名的回调被拒（401 + PAYMENT_001）：状态不翻、事件不产生 —— 签名校验挡在进入业务
     方法之前，所以这条验的是「够不着业务就不留痕」，不是事务原子性本身（后者见第 9 节）；
  3) 合法 HMAC 回调 → outbox 恰一行 LOAN_CREATED；
  4) aggregate_key = advances.id（这一次发生的主键），**不是 vin**（AGENTS §6.3 禁令）；
  5) 载荷逐字段对得上库内事实，principal 是整数日元，且**不含 dueDate/gracePeriodDays**
     —— Q-EVT3（到期日权威源 = 空表 financing_terms）未签字，造一个"看起来合理"的到期日
     等于凭空生成本金清偿义务，属于铁律 3 禁止的自造口径；
  6) 响应体 traceId == 事件里的 traceId —— 全链路号是透传的，不是发布端新造的 UUID；
  7) 重复回调走终态早返回：仍 200、仍一行事件、payment_ref 不被改写（业务层幂等）；
  8) 已有同 (event_type, aggregate_key) 的 SENT 行时，真回调仍只有一行（发布端
     WHERE NOT EXISTS 去重）。这条早期版本在 smoke_w0.py 里验不了，因为那时发布端零调用方；
     接线之后必须从 HTTP 入口验——psql 直插绕开了发布端，测不到它。
"""
import atexit
import hashlib
import hmac
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from agent_smoke_lib import (DB_SCHEMA, SEARCH_PATH, assert_eq, call, login, login_as,
                             ok, psql, psql_exec, psql_one, summary, wait_ready)

WEB = os.environ.get("SMOKE_WEB", "http://localhost:8080")
AUTH = os.environ.get("SMOKE_AUTH", "http://127.0.0.1:8081")
# 业务请求一律走网关（与基线 smoke-test.py 同一个 SMOKE_BASE 口径）。
# 为什么不能直连 web:8080：/api/dealers/**、/api/credit-lines/** 归 credit-service，
# 单体里没有这两个 Controller，直连必然 404（实测踩过：预检就红在 NOT_FOUND_001）。
# 走网关顺带把「新增的这条业务链路真的经路由可达」也验了，不是多此一举。
GW = os.environ.get("SMOKE_GW", "http://127.0.0.1:18080")
WEBHOOK_SECRET = os.environ.get("PAYMENT_WEBHOOK_SECRET", "dev-webhook-secret-change-me")

RUN = int(time.time())
TAG = "W2C%d" % (RUN % 100000)
VIN_A = "W2CA%06d" % (RUN % 1000000)      # 正常链路：合法回调 → 事件
VIN_B = "W2CB%06d" % (RUN % 1000000)      # 去重链路：已有 SENT 夹具后再回调
INVOICE = 1_850_000
RATIO = 0.90
EXPECTED = int(round(INVOICE * RATIO))    # 1,665,000 —— 与基线冒烟同一套发票/比例，便于比对
APPROVED_LIMIT = 50_000_000

# 岗位 → 口令（登录名与口令都和基线 smoke-test.py 一致，不新造账号）。
# 为什么不用 admin 跑全流程：新权限模型下管理员不持垫款申请/放款权限点，
# 用 admin 等于把「双人复核」测没了（基线同一条理由）。
ROLE_PWD = {"operator": "operator123",     # 业务经办岗：发起垫款
            "review_op": "review123",      # 采购审核岗：批量到货建账（确权）
            "cashier": "cashier123"}       # 资金出纳岗：提交支付通道
TOK = {}


def role(name):
    """按岗位登录并缓存令牌；拿不到就返回空串，让后续请求 401 由断言暴露出来。"""
    if name not in TOK:
        TOK[name] = login_as(name, ROLE_PWD[name], base=AUTH)
    return TOK[name]


def sign(adv_no, ts, ref_or_msg):
    """银行回调签名原文 = advanceNo.timestamp.(paymentRef|message)，HmacSHA256 十六进制。"""
    return hmac.new(WEBHOOK_SECRET.encode("utf-8"),
                    ("%s.%s.%s" % (adv_no, ts, ref_or_msg or "")).encode("utf-8"),
                    hashlib.sha256).hexdigest()


def bank_confirm(adv_no, payment_ref=None):
    ts = str(int(time.time()))
    return call("POST", "/api/advances/%s/confirm" % adv_no,
                {"paymentRef": payment_ref} if payment_ref else {}, token="",
                headers={"X-Payment-Signature": sign(adv_no, ts, payment_ref),
                         "X-Payment-Timestamp": ts}, base=GW)


# 本轮造的垫款主键 = 事件 aggregate_key，清理与断言都以它为轴
ADVANCE_IDS = []
DEALER_ID = None
CREDIT_LINE_ID = None
_CLEANED = False


def events(key):
    """取某 aggregate_key 下的 LOAN_CREATED 行，返回字典列表 [{"id","status","aggregate_key"}, ...]。

    为什么走单列 JSON 而不是多列 SELECT：公共库 psql() 按 tab 拆列，而 `psql -tA` 的默认列
    分隔符是竖线——实测 `SELECT 1,'a|b',3` 回来是 [['1|a|b|3']]，多列读全部错位（第 3 步就是这么
    IndexError 的）。根治要给 agent_smoke_lib 加 `-F` 制表符，但那是全波次共用文件，需 owner 提案
    + 用户点头；这里先用不受分隔符影响的写法，并把坑记进 HANDOFF。
    """
    raw = psql_one("SELECT json_agg(t)::text FROM (SELECT id, status, aggregate_key "
                   "FROM outbox_events WHERE event_type='LOAN_CREATED' AND aggregate_key='%s' "
                   "ORDER BY id) t" % key)
    return json.loads(raw) if raw else []


def payload_of(event_id):
    """单列取回载荷原文（不解析），用于「序列化后不带小数点」这类文本级判据。"""
    return psql_one("SELECT payload FROM outbox_events WHERE id=%d" % int(event_id))


def count_events(key):
    return int(psql_one("SELECT count(*) FROM outbox_events WHERE event_type='LOAN_CREATED' "
                        "AND aggregate_key='%s'" % key) or 0)


def cleanup():
    """删掉本轮的事件行、其消费幂等行，以及（若 loan-service 同库在跑）由本轮垫款建出的借据。

    按 aggregate_key 而不是按已知事件 id 删，是为了「哪怕发布端去重失效多插了一条也照样清干净」——
    清理逻辑如果依赖被测行为的正确性，测出 bug 的那一轮就会留下脏数据。
    借据要一起删：loan_no='LN-'+advanceId 是唯一键，本轮号是新号虽然不撞，但留着会让 W1 冒烟
    和后续对账线数到多出来的债。loans/子表无外键（V16 实查），子表显式删。
    """
    global _CLEANED
    if _CLEANED or not ADVANCE_IDS:
        return
    _CLEANED = True
    keys = ",".join("'%d'" % i for i in ADVANCE_IDS)
    inlist = ",".join(str(i) for i in ADVANCE_IDS)
    psql_exec("DELETE FROM event_consumptions WHERE event_id IN "
              "(SELECT id FROM outbox_events WHERE event_type='LOAN_CREATED' AND aggregate_key IN (%s))" % keys)
    psql_exec("DELETE FROM outbox_events WHERE event_type='LOAN_CREATED' AND aggregate_key IN (%s)" % keys)
    rows = psql("SELECT id FROM loans WHERE advance_id IN (%s)" % inlist) or []
    loan_ids = [r[0] for r in rows]
    if loan_ids:
        llist = ",".join(loan_ids)
        psql_exec("DELETE FROM repayment_plans WHERE loan_id IN (%s)" % llist)
        psql_exec("DELETE FROM loan_status_history WHERE loan_id IN (%s)" % llist)
    psql_exec("DELETE FROM loans WHERE advance_id IN (%s)" % inlist)


atexit.register(cleanup)      # 崩溃路径也要清，理由见 smoke_w0.py 的同名注释


def build_advance(vin, confirm=False):
    """建账 → 申请 → 放款（可选银行回调），返回 (advance_no, advances.id)。

    与基线 ingest_and_advance 同一条链路、同一套岗位，差别只在额外把 advances.id 从库里查出来：
    发布端的键是数字主键，而 HTTP 响应里的 `advanceId` 字段其实是业务单号 advance_no——
    两者搞混是这条链路最容易写错的地方，所以不拿响应值当键用。
    """
    st, res = call("POST", "/api/vehicles/batch-ingest", {
        "dealerId": DEALER_ID, "creditLineId": CREDIT_LINE_ID,
        "purchaseChannel": "OEM", "supplierName": "W2 冒烟厂家",
        "vehicles": [{"vin": vin, "brand": "TOYOTA", "model": "W2C", "modelYear": 2026,
                      "invoiceAmount": INVOICE, "advanceRatio": RATIO, "color": "WHITE"}],
        "idempotencyKey": "ingest-" + vin}, token=role("review_op"), base=GW)
    ok(st == 201 and (res.get("data") or {}).get("accepted") == 1, "建账 %s -> %s" % (vin, st),
       json.dumps(res, ensure_ascii=False)[:200])
    st, res = call("POST", "/api/advances", {"creditLineId": CREDIT_LINE_ID, "vin": vin},
                   headers={"Idempotency-Key": "adv-" + vin}, token=role("operator"), base=GW)
    ok(st == 201, "垫款申请 %s -> %s" % (vin, st), json.dumps(res, ensure_ascii=False)[:200])
    adv_no = (res.get("data") or {}).get("advanceId")
    st, res = call("POST", "/api/advances/%s/disburse" % adv_no, {},
                   token=role("cashier"), base=GW)
    ok(st == 200, "放款（提交支付通道）%s -> %s" % (adv_no, st),
       json.dumps(res, ensure_ascii=False)[:200])
    adv_id = psql_one("SELECT id FROM advances WHERE advance_no='%s'" % adv_no)
    if not ok(adv_id is not None, "advances.id 已取到（发布端键的来源）", "advance_no=%s" % adv_no):
        return adv_no, 0
    ADVANCE_IDS.append(int(adv_id))
    if confirm:
        st, conf = bank_confirm(adv_no, "BANK-REF-" + vin)
        ok(st == 200, "银行回调确认 %s -> %s" % (adv_no, st),
           json.dumps(conf, ensure_ascii=False)[:200])
    return adv_no, int(adv_id)


# ---------------------------------------------------------------------------
# 0. 预检：进程、登录、岗位令牌、当前 schema
# ---------------------------------------------------------------------------
print("== 预检：发布端所在进程 ==")
cur_schema = psql_one("SELECT current_schema()")
print("   search_path=%s  current_schema=%s  （FLOORPLAN_DB_SCHEMA=%r）" % (SEARCH_PATH, cur_schema, DB_SCHEMA))
if DB_SCHEMA:
    assert_eq("隔离生效：脚本会话 current_schema 就是目标 schema", DB_SCHEMA, cur_schema)
else:
    print("   [提示] 未设 FLOORPLAN_DB_SCHEMA → 跑 public（交付判据要求的就是这一档）")
ok(wait_ready("/api/advances/" + TAG, base=WEB, timeout=120),
   "web(8080) 已就绪（发布端就在这个进程里）", "120s 内无响应")
ok(wait_ready("/api/auth/login", base=AUTH, timeout=120),
   "auth-service(8081) 已就绪（登录只有它提供，单体无该控制器）", "120s 内无响应")
ok(wait_ready("/api/advances/" + TAG, base=GW, timeout=120),
   "gateway(18080) 已就绪（业务请求都从它进）", "120s 内无响应")
login(base=AUTH)
for _name in ("operator", "review_op", "cashier"):
    assert_eq("岗位令牌已取得：%s" % _name, True, bool(role(_name)))

# 本轮专用的一套经销商与额度（口径同基线：POST /api/dealers + /api/credit-lines）
st, res = call("POST", "/api/dealers", {"dealerCode": "DL-" + TAG, "name": "W2 发布端冒烟经销商",
                                        "riskGrade": "B", "businessYears": 10}, base=GW)
ok(st == 201, "创建经销商 -> %s" % st, json.dumps(res, ensure_ascii=False)[:200])
DEALER_ID = (res.get("data") or {}).get("id")
st, res = call("POST", "/api/credit-lines",
               {"dealerId": DEALER_ID, "approvedLimit": APPROVED_LIMIT}, base=GW)
ok(st == 201, "核定额度 -> %s" % st, json.dumps(res, ensure_ascii=False)[:200])
CREDIT_LINE_ID = (res.get("data") or {}).get("id")
if not (DEALER_ID and CREDIT_LINE_ID):
    # 后面每条断言都要用这两个 id；缺了就直接收摊，别让 None 参与比较刷出一屏假红
    cleanup()
    sys.exit(summary("smoke_w2_core(%s)：预检未建出经销商/额度，后续断言无法进行" % TAG))

# ---------------------------------------------------------------------------
# 1. 停在 PROCESSING：没有事件
# ---------------------------------------------------------------------------
print("\n== 1. 放款未确认（PROCESSING）不发事件 ==")
adv_no_a, adv_id_a = build_advance(VIN_A, confirm=False)
assert_eq("垫款停在 PROCESSING", "PROCESSING",
          psql_one("SELECT status FROM advances WHERE id=%d" % adv_id_a))
assert_eq("outbox 里还没有这笔的 LOAN_CREATED", 0, count_events(str(adv_id_a)))

# ---------------------------------------------------------------------------
# 2. 非法签名被拒 → 事务回滚 → 依旧没有事件
# ---------------------------------------------------------------------------
print("\n== 2. 非法签名回调：401、状态不变、且不产生事件 ==")
ts = str(int(time.time()))
st, res = call("POST", "/api/advances/%s/confirm" % adv_no_a, {"paymentRef": "X"}, token="",
               headers={"X-Payment-Signature": "bad", "X-Payment-Timestamp": ts}, base=GW)
assert_eq("错误签名 -> 401", 401, st)
assert_eq("错误码 PAYMENT_001", "PAYMENT_001", res.get("code"))
assert_eq("被拒的回调没有留下事件", 0, count_events(str(adv_id_a)))
assert_eq("被拒的回调也没把垫款翻成 PAID", "PROCESSING",
          psql_one("SELECT status FROM advances WHERE id=%d" % adv_id_a))

# ---------------------------------------------------------------------------
# 3~6. 合法回调 → 恰一行事件，键与载荷逐字段核到库内事实
# ---------------------------------------------------------------------------
print("\n== 3~6. 合法 HMAC 回调 → 一行事件，键=advances.id，载荷逐字段 ==")
st, conf = bank_confirm(adv_no_a, "BANK-REF-" + VIN_A)
assert_eq("合法回调 -> 200", 200, st)
assert_eq("垫款已 PAID", "PAID", psql_one("SELECT status FROM advances WHERE id=%d" % adv_id_a))
rows = events(str(adv_id_a))
assert_eq("outbox 恰一行 LOAN_CREATED", 1, len(rows))
if not rows:
    # 后面每一行都要读 rows[0]，这里不挡就会 IndexError 把结论刷成一栈噪声
    cleanup()
    sys.exit(summary("smoke_w2_core(%s)：第 3 步未取到事件行，后续断言无法进行" % TAG))
ok(rows[0]["status"] in ("PENDING", "SENT"),
   "事件行状态可投递（PENDING）或已被投递（SENT）——不断言「等于 PENDING」："
   "同库在跑的 loan-service 一轮轮询最多 1s 就会把它标成 SENT，写死初值就是时绿时红",
   "status=%s" % rows[0]["status"])

print("\n-- 4. aggregate_key 的选择 --")
assert_eq("aggregate_key 就是 advances.id", str(adv_id_a), rows[0]["aggregate_key"])
ok(rows[0]["aggregate_key"] != VIN_A,
   "aggregate_key 不是 VIN（AGENTS §6.3：用 vin 会吞掉同车第二笔垫款）",
   "aggregate_key=%s vin=%s" % (rows[0]["aggregate_key"], VIN_A))

print("\n-- 5. 载荷逐字段 --")
payload_raw = payload_of(rows[0]["id"])
pl = json.loads(payload_raw)
assert_eq("vin 与建账 VIN 相同", VIN_A, pl.get("vin"))
assert_eq("advanceId 是数字主键（不是 advance_no 业务单号）", adv_id_a, pl.get("advanceId"))
assert_eq("dealerId 取自车辆归属（不是请求体里传的）", int(DEALER_ID), pl.get("dealerId"))
assert_eq("creditLineId 取自垫款占用的额度", int(CREDIT_LINE_ID), pl.get("creditLineId"))
db_amount = int(psql_one("SELECT amount FROM advances WHERE id=%d" % adv_id_a) or -1)
assert_eq("库内垫款金额 = 发票×比例", EXPECTED, db_amount)
assert_eq("principal 与库内金额同值", db_amount, pl.get("principal"))
ok(isinstance(pl.get("principal"), int) and "." not in payload_raw,
   "principal 是整数日元（BIGINT，序列化后不带小数点）", "payload=%s" % payload_raw[:200])
ok(not {"dueDate", "gracePeriodDays"} & set(pl.keys()),
   "载荷不含 dueDate/gracePeriodDays —— Q-EVT3 未签字，到期日权威源是空表 financing_terms，"
   "发布端不许自造 paidAt+90 天", "keys=%s" % sorted(pl.keys()))
ok(bool(pl.get("traceId")), "traceId 非空", "traceId=%r" % pl.get("traceId"))

print("\n-- 6. traceId 与响应体同源 --")
assert_eq("事件里的 traceId == 回调响应信封的 traceId（同一条请求链路，不是发布端新造 UUID）",
          conf.get("traceId"), pl.get("traceId"))

# ---------------------------------------------------------------------------
# 7. 重复回调 → 终态早返回，不重复发事件
# ---------------------------------------------------------------------------
print("\n== 7. 重复回调（业务层幂等）==")
st, conf2 = bank_confirm(adv_no_a, "BANK-REF-DUP")
assert_eq("重复回调仍 200（幂等早返回，不是 4xx）", 200, st)
assert_eq("仍只一行事件（终态分支不经过发布端）", 1, count_events(str(adv_id_a)))
assert_eq("payment_ref 未被第二次回调改写", "BANK-REF-" + VIN_A,
          psql_one("SELECT payment_ref FROM advances WHERE id=%d" % adv_id_a))

# ---------------------------------------------------------------------------
# 8. 发布端 WHERE NOT EXISTS：已有同键 SENT 行时不再插
# ---------------------------------------------------------------------------
# 夹具载荷故意写成与真实事件同形：若给缺字段的坏载荷，同库在跑的 loan-service 会重试三次置 DEAD，
# 这一轮就变成一次死信演练而不是去重验证。夹具本身会被消费建出一笔借据，属预期，清理阶段删掉。
print("\n== 8. 机制层去重（发布端 WHERE NOT EXISTS，不是表约束）==")
adv_no_b, adv_id_b = build_advance(VIN_B, confirm=False)
fixture_payload = json.dumps({"vin": VIN_B, "traceId": TAG + "-FIXTURE", "advanceId": adv_id_b,
                              "dealerId": int(DEALER_ID), "creditLineId": int(CREDIT_LINE_ID),
                              "principal": EXPECTED})
ok(psql_exec("INSERT INTO outbox_events (event_type, aggregate_key, payload, status) "
             "VALUES ('LOAN_CREATED', '%d', '%s', 'SENT')" % (adv_id_b, fixture_payload)),
   "夹具已插入：同 (event_type, aggregate_key) 已有一行 SENT")
fixture_rows = events(str(adv_id_b))
assert_eq("夹具计数=1", 1, len(fixture_rows))
fixture_id = int(fixture_rows[0]["id"]) if fixture_rows else -1
st, conf_b = bank_confirm(adv_no_b, "BANK-REF-" + VIN_B)
assert_eq("合法回调仍 200（去重不影响业务成功）", 200, st)
after = events(str(adv_id_b))
assert_eq("回调后该键仍只一行（第二次插被 WHERE NOT EXISTS 拦住）", 1, len(after))
assert_eq("留下的那行仍是夹具（说明不是把夹具删了再插新的）", fixture_id,
          int(after[0]["id"]) if after else -1)
assert_eq("垫款已 PAID", "PAID", psql_one("SELECT status FROM advances WHERE id=%d" % adv_id_b))
print("   [已知窗口] NOT EXISTS 是读-判-写，两个并发事务可能各插一条（JdbcOutboxPublisher 类注释已")
print("   如实记录）。兜底是消费端 event_consumptions 主键 + loan_no 唯一键两层幂等；本脚本单线程")
print("   跑不出这个窗口，不拿「没复现」当「不存在」。")

# ---------------------------------------------------------------------------
# 9. 边界自证：本脚本证明不了什么
# ---------------------------------------------------------------------------
print("\n== 9. 边界自证 ==")
print("   [黑盒测不到] 「业务写与事件写同一事务」是 JdbcOutboxPublisher 不加 @Transactional、")
print("   confirmAdvance 有 @Transactional 这两处代码约定保证的；要真证明它，得在事件插入后强制")
print("   回滚再看 outbox 是否空，那需要故障注入（本期没有），故不写成断言、只登记为约定。")
print("   [不在本轮] 「同 VIN、不同 advanceId 的两次发生各自成事件」属 ADR-010 同 VIN 多笔垫款")
print("   防线的场景，需要先放行第二笔申请，本脚本不擅自构造（会被门禁拒付，测出来的是策略不是键）。")
print("   该语义的镜像（同 aggregate_key 两次发生各自消费）已在 smoke_w0.py 第 2 步钉住。")
print("   [不在本轮] 消费结果（loans 是否建档、坏载荷是否 DEAD、还款计划是否生成）在")
print("   smoke_w1_loan.py 验。发布端绿不等于闭环绿，两条断言分开才看得见断点在哪一侧。")

# ---------------------------------------------------------------------------
# 10. 清理
# ---------------------------------------------------------------------------
print("\n== 清理 ==")
cleanup()
assert_eq("本轮事件行已清空", "0", psql_one(
    "SELECT count(*) FROM outbox_events WHERE aggregate_key IN (%s) AND event_type='LOAN_CREATED'"
    % ",".join("'%d'" % i for i in ADVANCE_IDS)))
assert_eq("本轮垫款未留下借据残留（消费端若在同库建据则已随之删除）", "0", psql_one(
    "SELECT count(*) FROM loans WHERE advance_id IN (%s)" % ",".join(str(i) for i in ADVANCE_IDS)))
print("说明：本轮建的经销商/额度/车辆/垫款保留在库里（与基线冒烟同法，都是时间戳唯一号，不参与")
print("      任何唯一键冲突）；真正会撞键的 outbox / loans 已按上面两条自证清空。")

sys.exit(summary("smoke_w2_core(%s)" % TAG))
