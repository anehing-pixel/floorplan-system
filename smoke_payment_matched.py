#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PAYMENT_MATCHED 端到端冒烟 + 四条丢失的 LOAN_CREATED 断言重建。

跑法（服务已在跑，经网关）：
    SMOKE_BASE=http://127.0.0.1:18080 PYTHONIOENCODING=utf-8 python smoke_payment_matched.py

覆盖：
  A. 四条因并行套件删除而丢失的断言（PROGRESS §5.4）：
     A1 LOAN_CREATED 的 aggregate_key = advanceId，不是 vin；
     A2 重复银行回调仍只发一条事件（终态幂等 + 发布端去重）；
     A3 缺 dueDate 的事件建出借据，但不生成还款计划；
     A4 坏载荷（缺 dealerId）重试 3 次置 DEAD，且不留下半条借据。
  B. PAYMENT_MATCHED 生产者（bank-service）：
     B1 自动匹配（VIN 命中）→ 匹配结论落 V17 + outbox 恰一行，聚合键 = paymentRef；
     B2 事件载荷 settled/restoreAmount/creditLineId 口径正确；
     B3 解押只留痕（lien_releases RELEASED + 抹消 REQUESTED），回补只登记 PENDING；
     B4 重复调匹配接口幂等，不重复发事件。
  C. PAYMENT_MATCHED 消费者（credit-service）：
     C1 结清事件真实回补：credit_lines.used_amount 下降、available 上升、台账 applied_amount 正确；
     C2 同一 paymentRef 被重新发布成新事件 → L2 唯一键挡住，不重复回补；
     C3 部分回款（settled=false）不解押、不回补；
     C4 人工绑定（MANUAL_MATCHED）同样走真实回补。

编码提醒：本文件含中文与 →；重定向输出时必须带 PYTHONIOENCODING=utf-8（AGENTS §5）。
"""
import atexit
import hashlib
import hmac
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import date

BASE = os.environ.get("SMOKE_BASE", "http://localhost:8080")
WEBHOOK_SECRET = os.environ.get("PAYMENT_WEBHOOK_SECRET", "dev-webhook-secret-change-me")

TS = str(int(time.time()))
TAG = TS[-6:]
RUN = "%s%s" % (TAG, str(int(time.time() * 1000) % 1000))

VIN_FULL = "PMF" + TAG          # 结清自动匹配
VIN_PARTIAL = "PMP" + TAG       # 部分回款
VIN_MANUAL = "PMM" + TAG        # 人工绑定
VIN_BAD = "PMB" + TAG           # 坏载荷（不建账）
ACCOUNT = "PMACC" + TAG         # payment_transactions.account_ref（<=32）
DEALER_CODE = "DL-PM-" + TAG

INVOICE = 1_850_000
RATIO = 0.90
EXPECTED = int(round(INVOICE * RATIO))
LIMIT = 80_000_000

ROLE_ACCOUNTS = {
    "admin":      ("admin123",     "SYSTEM_ADMIN"),
    "operator":   ("operator123",  "BUSINESS_OPERATOR"),
    "review_op":  ("review123",    "PURCHASE_REVIEWER"),
    "cashier":    ("cashier123",   "FUNDING_CASHIER"),
    "settlement": ("settle123",    "SETTLEMENT_OFFICER"),
    "credit":     ("credit123",    "CREDIT_REVIEWER"),
}
TOK = {}
FAILS = []
DEALER_ID = None
CREDIT_LINE_ID = None
CREATED_ADV_INVOICE = {}
EVENT_IDS = []


def call(method, path, body=None, token="", idem_key=None):
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Operator-Id", "admin")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if idem_key:
        req.add_header("Idempotency-Key", idem_key)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"raw": raw}


def login(name):
    if name in TOK:
        return TOK[name]
    pwd, _ = ROLE_ACCOUNTS[name]
    st, res = call("POST", "/api/auth/login", {"username": name, "password": pwd})
    assert st == 200 and res.get("data", {}).get("token"), \
        "预检失败：%s 登录未通过 → %s" % (name, json.dumps(res, ensure_ascii=False))
    TOK[name] = res["data"]["token"]
    return TOK[name]


def ok(cond, msg):
    print(("  [PASS] " if cond else "  [FAIL] ") + msg)
    if not cond:
        FAILS.append(msg)


def D(res):
    return res.get("data") or {}


def psql(sql):
    env = dict(os.environ, PGPASSWORD="floorplan")
    for argv in (["psql", "-h", "localhost", "-p", "5434", "-U", "floorplan",
                  "-d", "floorplan", "-tAc", sql],
                 ["docker", "exec", "floorplan-db2", "psql", "-p", "5434", "-U", "floorplan",
                  "-d", "floorplan", "-tAc", sql]):
        try:
            r = subprocess.run(argv, env=env, capture_output=True, timeout=30)
            if r.returncode == 0:
                return r.stdout.decode("utf-8").strip()
        except Exception:
            pass
    return None


def one(sql):
    """取单值查询的第一行。

    psql 的 INSERT/UPDATE ... RETURNING 会额外输出一行命令状态（如 "INSERT 0 1"），
    只取第一行才能拿到真正的标量；脚本里所有查询都是单行（聚合或 LIMIT 1）。
    """
    v = psql(sql)
    if not v:
        return None
    first = v.splitlines()[0].strip()
    return first if first else None


def as_int(sql):
    v = one(sql)
    return int(v) if v is not None else None


def wait_until(desc, fn, timeout=30):
    """轮询到 fn() 返回真值；超时打印一次当前读到的东西，避免只报一句失败。"""
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            last = fn()
        except Exception as e:  # psql 偶发失败不应直接打断轮询
            last = None
            _ = e
        if last:
            return last
        time.sleep(0.5)
    print("    [WAIT] %s 超时；最后读到：%r" % (desc, last))
    return None


def sign(adv_no, ts, ref):
    msg = "%s.%s.%s" % (adv_no, ts, ref or "")
    return hmac.new(WEBHOOK_SECRET.encode("utf-8"), msg.encode("utf-8"), hashlib.sha256).hexdigest()


def bank_confirm(adv_no, payment_ref=None):
    return _confirm(adv_no, str(int(time.time())), payment_ref)


def _confirm(adv_no, ts, payment_ref):
    url = BASE + "/api/advances/%s/confirm" % adv_no
    body = json.dumps({"paymentRef": payment_ref} if payment_ref else {}).encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Payment-Signature", sign(adv_no, ts, payment_ref))
    req.add_header("X-Payment-Timestamp", ts)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except ValueError:
            return e.code, {"raw": "unparseable"}


def create_dealer_and_limit():
    global DEALER_ID, CREDIT_LINE_ID
    st, res = call("POST", "/api/dealers",
                   {"dealerCode": DEALER_CODE, "name": "PAYMENT_MATCHED 冒烟经销商",
                    "riskGrade": "B", "businessYears": 10}, token=login("admin"))
    ok(st == 201, "创建经销商 -> %s" % st)
    DEALER_ID = D(res).get("id")
    st, res = call("POST", "/api/credit-lines",
                   {"dealerId": DEALER_ID, "approvedLimit": LIMIT}, token=login("admin"))
    ok(st == 201, "核定额度 -> %s" % st)
    CREDIT_LINE_ID = D(res).get("id")


def finance_vehicle(vin):
    """建账 → 申请垫款 → 放款 → 银行回调，等借据被 loan-service 建出来。"""
    st, res = call("POST", "/api/vehicles/batch-ingest", {
        "dealerId": DEALER_ID, "creditLineId": CREDIT_LINE_ID,
        "purchaseChannel": "OEM", "supplierName": "PAYMENT_MATCHED 测试厂家",
        "vehicles": [{"vin": vin, "brand": "TOYOTA", "model": "PM",
                      "modelYear": 2026, "invoiceAmount": INVOICE,
                      "advanceRatio": RATIO, "color": "WHITE"}],
        "idempotencyKey": "pm-ingest-" + vin}, token=login("review_op"))
    assert st == 201 and D(res).get("accepted") == 1, "建账失败 vin=%s -> %s %s" % (vin, st, res)

    st, res = call("POST", "/api/advances",
                   {"creditLineId": CREDIT_LINE_ID, "vin": vin},
                   idem_key="pm-adv-" + vin, token=login("operator"))
    assert st == 201, "垫款申请失败 vin=%s -> %s %s" % (vin, st, res)
    adv_no = D(res)["advanceId"]
    amount = D(res)["advancedAmount"]
    CREATED_ADV_INVOICE[vin] = amount

    st, res = call("POST", "/api/advances/%s/disburse" % adv_no, {}, token=login("cashier"))
    assert st == 200, "放款失败 vin=%s -> %s %s" % (vin, st, res)

    st, res = bank_confirm(adv_no, "PM-BANK-" + vin)
    assert st == 200, "银行回调失败 vin=%s -> %s %s" % (vin, st, res)

    loan = wait_until("借据建出 vin=" + vin, lambda: one(
        "SELECT id || '|' || advance_id || '|' || principal || '|' || credit_line_id "
        "FROM loans WHERE vin='%s' AND status IN ('DRAFT','ACTIVE','OVERDUE','SETTLING') "
        "ORDER BY id DESC LIMIT 1" % vin), timeout=40)
    assert loan, "LOAN_CREATED 未在 40s 内建出借据 vin=%s" % vin
    loan_id, advance_id, principal, credit_line_id = [int(x) for x in loan.split("|")]
    return {"adv_no": adv_no, "loan_id": loan_id, "advance_id": advance_id,
            "principal": principal, "credit_line_id": credit_line_id, "amount": amount}


def insert_outbox(event_type, aggregate_key, payload, status="PENDING"):
    sql = ("INSERT INTO outbox_events (event_type, aggregate_key, payload, status) "
           "VALUES ('%s','%s','%s','%s') RETURNING id"
           % (event_type, aggregate_key,
              json.dumps(payload, ensure_ascii=False).replace("'", "''"), status))
    out = one(sql)
    assert out, "合成事件插入失败 aggregate_key=%s" % aggregate_key
    eid = int(out)
    EVENT_IDS.append(eid)
    return eid


def insert_payment_tx(tx_id, amount, reference):
    sql = ("INSERT INTO payment_transactions "
           "(transaction_id, account_ref, value_date, amount, reference, match_status, source) "
           "VALUES ('%s','%s',CURRENT_DATE,%d,'%s','UNMATCHED','MANUAL_UPLOAD') RETURNING id"
           % (tx_id, ACCOUNT, amount, reference.replace("'", "''")))
    out = one(sql)
    assert out, "流水插入失败 tx=%s" % tx_id
    return int(out)


def match_tx(tx_id):
    return call("POST", "/api/payments/transactions/%s/match?accountRef=%s" % (tx_id, ACCOUNT),
                token=login("settlement"))


def manual_match_tx(tx_id, vin):
    return call("POST", "/api/payments/transactions/%s/manual-match?accountRef=%s" % (tx_id, ACCOUNT),
                {"vin": vin}, token=login("settlement"))


def wait_restore(payment_ref, expected_applied, timeout=30):
    def matched():
        v = one("SELECT applied_amount FROM credit_line_restorations WHERE payment_ref='%s'" % payment_ref)
        return v if v == str(expected_applied) else None
    return wait_until("credit-service 回补 paymentRef=" + payment_ref, matched, timeout=timeout) is not None


def cleanup():
    """只删本脚本构造的数据（VIN 前缀唯一），不动其他冒烟的数据。"""
    vins = "('%s','%s','%s','%s')" % (VIN_FULL, VIN_PARTIAL, VIN_MANUAL, VIN_BAD)
    tx_like = "transaction_id LIKE 'PMTX%%%s%%'" % TAG
    sql = (
        "DELETE FROM event_consumptions WHERE event_id IN "
        "(SELECT id FROM outbox_events WHERE payload LIKE '%%%s%%' OR payload LIKE '%%%s%%' "
        " OR payload LIKE '%%%s%%' OR payload LIKE '%%%s%%'); "
        "DELETE FROM outbox_events WHERE payload LIKE '%%%s%%' OR payload LIKE '%%%s%%' "
        " OR payload LIKE '%%%s%%' OR payload LIKE '%%%s%%'; "
        "DELETE FROM credit_line_restorations WHERE payment_ref LIKE 'PMTX%%%s%%'; "
        "DELETE FROM lien_releases WHERE %s; "
        "DELETE FROM credit_restores WHERE %s; "
        "DELETE FROM payment_matches WHERE %s; "
        "DELETE FROM payment_transactions WHERE %s; "
        "DELETE FROM repayment_plans WHERE loan_id IN (SELECT id FROM loans WHERE vin IN %s); "
        "DELETE FROM loan_status_history WHERE loan_id IN (SELECT id FROM loans WHERE vin IN %s); "
        "DELETE FROM loans WHERE vin IN %s; "
        "DELETE FROM advances WHERE vin IN %s; "
        "DELETE FROM collateral_evidence_links WHERE vin IN %s; "
        "DELETE FROM collateral_ledger WHERE vin IN %s; "
        "DELETE FROM vehicle_units WHERE vin IN %s; "
        "DELETE FROM credit_lines WHERE dealer_id = %s; "
        "DELETE FROM dealers WHERE id = %s;"
    ) % (VIN_FULL, VIN_PARTIAL, VIN_MANUAL, VIN_BAD,
         VIN_FULL, VIN_PARTIAL, VIN_MANUAL, VIN_BAD,
         TAG, tx_like, tx_like, tx_like, tx_like,
         vins, vins, vins, vins, vins, vins, vins,
         DEALER_ID, DEALER_ID)
    if DEALER_ID is not None and psql(sql) is None:
        print("  [INFO] 清理未执行（psql 不可用或语句失败；不影响断言结论）")


def main():
    print("=" * 68)
    print("PAYMENT_MATCHED 端到端冒烟 + LOAN_CREATED 四条丢失断言")
    print("=" * 68)

    login("admin")
    create_dealer_and_limit()

    # ==================================================================
    # A. LOAN_CREATED 四条丢失断言
    # ==================================================================
    print("\nA. LOAN_CREATED 断言重建（发布端 + 消费端）")
    full = finance_vehicle(VIN_FULL)
    advance_id = full["advance_id"]
    loan_id = full["loan_id"]
    principal = full["principal"]

    ok(as_int("SELECT count(*) FROM outbox_events WHERE event_type='LOAN_CREATED' "
              "AND aggregate_key='%d'" % advance_id) == 1,
       "A1 aggregate_key=advanceId（%d）有且只有 1 条 LOAN_CREATED" % advance_id)
    ok(as_int("SELECT count(*) FROM outbox_events WHERE event_type='LOAN_CREATED' "
              "AND aggregate_key='%s'" % VIN_FULL) == 0,
       "A1 aggregate_key 不是 vin（用 vin 会把同车第二笔垫款永久吞掉）")

    payload = one("SELECT payload FROM outbox_events WHERE event_type='LOAN_CREATED' "
                  "AND aggregate_key='%d' ORDER BY id DESC LIMIT 1" % advance_id) or ""
    ok('"advanceId": %d' % advance_id in payload or '"advanceId":%d' % advance_id in payload,
       "A1 载荷带 advanceId=%d" % advance_id)

    st, res = bank_confirm(full["adv_no"], "PM-DUP-" + VIN_FULL)
    ok(st == 200 and D(res).get("status") == "PAID", "A2 重复银行回调 -> 200/PAID（终态幂等）")
    time.sleep(2)
    ok(as_int("SELECT count(*) FROM outbox_events WHERE event_type='LOAN_CREATED' "
              "AND aggregate_key='%d'" % advance_id) == 1,
       "A2 重复回调后 LOAN_CREATED 仍只有 1 条（不重复发事件）")

    ok(one("SELECT due_date IS NULL FROM loans WHERE id=%d" % loan_id) == "t",
       "A3 本期事件不带 dueDate -> loans.due_date IS NULL（未自造到期日）")
    ok(as_int("SELECT count(*) FROM repayment_plans WHERE loan_id=%d" % loan_id) == 0,
       "A3 缺到期日 -> 不生成还款计划（不是消费路径坏了）")

    fake_advance = 9_300_000_000 + int(time.time())
    bad_event = insert_outbox("LOAN_CREATED", "PM-BAD-%s" % TAG, {
        "vin": VIN_BAD, "traceId": "pm-bad-" + TAG, "advanceId": fake_advance,
        "creditLineId": CREDIT_LINE_ID, "principal": 100
        # 故意缺 dealerId：loans.dealer_id NOT NULL，消费者必须失败而不是静默 return
    })
    def bad_terminal():
        v = one("SELECT status FROM event_consumptions WHERE event_id=%d AND consumer='loan-service'"
                % bad_event)
        # FAILED 是中间态；只有 DEAD（或 DONE，说明消费者没按预期拒绝）才是终态
        return v if v in ("DEAD", "DONE") else None
    status = wait_until("坏载荷置 DEAD", bad_terminal, timeout=30)
    ok(status == "DEAD", "A4 坏载荷（缺 dealerId）最终置 DEAD -> %s" % status)
    ok(one("SELECT attempts FROM event_consumptions WHERE event_id=%d AND consumer='loan-service'"
           % bad_event) == "3", "A4 重试 3 次后才 DEAD")
    ok(as_int("SELECT count(*) FROM loans WHERE vin='%s' OR advance_id=%d"
              % (VIN_BAD, fake_advance)) == 0,
       "A4 坏载荷不留下半条借据")

    # ==================================================================
    # B. PAYMENT_MATCHED 生产者
    # ==================================================================
    print("\nB. PAYMENT_MATCHED 生产者（bank-service）")
    tx_full = "PMTX-FULL-" + TAG
    insert_payment_tx(tx_full, principal, "FULL SETTLEMENT FOR " + VIN_FULL)
    st, res = match_tx(tx_full)
    ok(st == 200, "B1 自动匹配接口 -> %s" % st)
    m = D(res).get("match") or {}
    ok(m.get("matchStatus") == "AUTO_MATCHED" and m.get("vin") == VIN_FULL,
       "B1 VIN 命中自动匹配 -> %s/%s" % (m.get("matchStatus"), m.get("vin")))
    ok(D(res).get("released") is True, "B1 结清匹配触发解押留痕（released=true）")

    match_id = as_int("SELECT id FROM payment_matches WHERE account_ref='%s' AND transaction_id='%s'"
                      % (ACCOUNT, tx_full))
    ok(match_id is not None, "B1 payment_matches 落库（matchId=%s）" % match_id)
    ok(one("SELECT match_status FROM payment_matches WHERE id=%d" % match_id) == "AUTO_MATCHED",
       "B1 匹配结论 AUTO_MATCHED 已持久化（内存存储已替换）")

    event_id = as_int("SELECT id FROM outbox_events WHERE event_type='PAYMENT_MATCHED' "
                      "AND aggregate_key='%s' ORDER BY id DESC LIMIT 1" % tx_full)
    ok(event_id is not None, "B1 outbox 有 PAYMENT_MATCHED 事件")
    ok(one("SELECT aggregate_key FROM outbox_events WHERE id=%d" % event_id) == tx_full,
       "B1 aggregate_key = paymentRef（本次匹配的 transaction_id）")
    ok(one("SELECT payload::jsonb->>'settled' FROM outbox_events WHERE id=%d" % event_id) == "true",
       "B2 载荷 settled=true")
    ok(one("SELECT payload::jsonb->>'restoreAmount' FROM outbox_events WHERE id=%d" % event_id)
       == str(principal), "B2 载荷 restoreAmount=借据本金 %d" % principal)
    ok(one("SELECT payload::jsonb->>'creditLineId' FROM outbox_events WHERE id=%d" % event_id)
       == str(CREDIT_LINE_ID), "B2 载荷 creditLineId=%d（回补目标额度线）" % CREDIT_LINE_ID)
    ok(one("SELECT payload::jsonb->>'paymentRef' FROM outbox_events WHERE id=%d" % event_id) == tx_full,
       "B2 载荷 paymentRef 与聚合键一致")

    ok(one("SELECT status || '|' || cancellation_status FROM lien_releases WHERE match_id=%d"
           % match_id) == "RELEASED|REQUESTED",
       "B3 解押留痕 RELEASED + 抹消待办 REQUESTED（不冒充抹消完成）")
    ok(one("SELECT status || '|' || coalesce(available_before::text,'NULL') || '|' "
           "|| coalesce(available_after::text,'NULL') FROM credit_restores WHERE match_id=%d"
           % match_id) == "PENDING|NULL|NULL",
       "B3 回补只登记 PENDING，available_after 不再写 before 冒充已回补")

    st2, res2 = match_tx(tx_full)
    ok(st2 == 200 and D(res2).get("released") is False,
       "B4 重复调匹配接口 -> 200 且走既有结论（幂等）")
    time.sleep(1)
    ok(as_int("SELECT count(*) FROM outbox_events WHERE event_type='PAYMENT_MATCHED' "
              "AND aggregate_key='%s'" % tx_full) == 1,
       "B4 重复调匹配接口不重复发事件")

    # ==================================================================
    # C. PAYMENT_MATCHED 消费者（credit-service）
    # ==================================================================
    print("\nC. PAYMENT_MATCHED 消费者（credit-service 真实回补）")
    restored = wait_restore(tx_full, principal, timeout=40)
    ok(restored, "C1 credit_line_restorations 落库且 applied_amount=%d" % principal)
    ok(as_int("SELECT used_amount FROM credit_lines WHERE id=%d" % CREDIT_LINE_ID) == 0,
       "C1 credit_lines.used_amount 归零（真实回补，不是快照）")
    avail = as_int("SELECT approved_limit - used_amount - frozen_amount FROM credit_lines WHERE id=%d"
                   % CREDIT_LINE_ID)
    ok(avail == LIMIT, "C1 可用额度回到核定额 %d" % LIMIT)

    st, res = call("GET", "/api/credit-lines/%d/available" % CREDIT_LINE_ID, token=login("credit"))
    ok(st == 200 and D(res) == LIMIT,
       "C1 冻结契约 GET /api/credit-lines/{id}/available -> %s/%s" % (st, D(res)))
    ok(as_int("SELECT count(*) FROM audit_events WHERE action='CREDIT_LINE_RESTORED' "
              "AND after_snapshot LIKE '%%paymentRef=%s%%'" % tx_full) >= 1,
       "C1 回补写审计（带 paymentRef 与 traceId）")

    # B5：发布端 NOT EXISTS 去重（PAYMENT_MATCHED 那一半）。
    # PROGRESS §3 明确：重复匹配会先被 uk_payment_match_txn 挡住，要单独钉发布端机制，
    # 必须先删 payment_matches 行、保留已 SENT 的 outbox 行，再从 HTTP 入口重放匹配。
    print("\nB5 发布端 NOT EXISTS 去重（删除匹配行 + 保留 SENT 事件后重放）")
    ok(one("SELECT status FROM outbox_events WHERE id=%d" % event_id) == "SENT",
       "B5 原 PAYMENT_MATCHED 事件已投递为 SENT")
    psql("DELETE FROM payment_matches WHERE id=%d" % match_id)
    st, res = match_tx(tx_full)
    ok(st == 200 and D(res).get("matched") is True,
       "B5 删掉匹配结论后重放 -> 重新匹配成功（%s）" % D(res).get("matched"))
    time.sleep(2)
    ok(as_int("SELECT count(*) FROM outbox_events WHERE event_type='PAYMENT_MATCHED' "
              "AND aggregate_key='%s'" % tx_full) == 1,
       "B5 发布端 WHERE NOT EXISTS 命中：同聚合键的 SENT 行在那里，第二条事件不插入")
    ok(one("SELECT status FROM outbox_events WHERE id=%d" % event_id) == "SENT",
       "B5 原事件状态未被第二条发布改回 PENDING")

    # C2：同一 paymentRef 被重新发布成新 outbox 行（新 event_id）——L2 唯一键必须挡住
    replay_payload = one("SELECT payload FROM outbox_events WHERE id=%d" % event_id)
    replay_event = insert_outbox("PAYMENT_MATCHED", "PM-REPLAY-%s" % TAG,
                                 json.loads(replay_payload))
    def replay_terminal():
        v = one("SELECT status FROM event_consumptions WHERE event_id=%d AND consumer='credit-service'"
                % replay_event)
        return v if v in ("DONE", "DEAD") else None
    replay_done = wait_until("credit-service 消费重放事件", replay_terminal, timeout=30)
    ok(replay_done == "DONE", "C2 重放事件被消费并标 DONE（不是死信）")
    time.sleep(1)
    ok(as_int("SELECT count(*) FROM credit_line_restorations WHERE payment_ref='%s'" % tx_full) == 1,
       "C2 同一 paymentRef 只回补一次（V80 唯一约束是 L2）")
    ok(as_int("SELECT used_amount FROM credit_lines WHERE id=%d" % CREDIT_LINE_ID) == 0,
       "C2 重放未把 used_amount 扣成负数")

    # C3：部分回款不解押、不回补
    part = finance_vehicle(VIN_PARTIAL)
    tx_partial = "PMTX-PART-" + TAG
    insert_payment_tx(tx_partial, part["principal"] // 2, "PARTIAL FOR " + VIN_PARTIAL)
    st, res = match_tx(tx_partial)
    ok(st == 200 and D(res).get("matched") is True and D(res).get("released") is False,
       "C3 部分回款匹配成功但不触发解押（matched=true/released=false）")
    part_match_id = as_int("SELECT id FROM payment_matches WHERE account_ref='%s' AND transaction_id='%s'"
                           % (ACCOUNT, tx_partial))
    ok(as_int("SELECT count(*) FROM lien_releases WHERE match_id=%d" % part_match_id) == 0,
       "C3 部分回款不生成解押留痕")
    ok(as_int("SELECT count(*) FROM credit_restores WHERE match_id=%d" % part_match_id) == 0,
       "C3 部分回款不登记回补请求")
    part_event = as_int("SELECT id FROM outbox_events WHERE event_type='PAYMENT_MATCHED' "
                        "AND aggregate_key='%s'" % tx_partial)
    ok(one("SELECT payload::jsonb->>'settled' FROM outbox_events WHERE id=%d" % part_event) == "false",
       "C3 事件载荷 settled=false")
    ok(one("SELECT payload::jsonb->>'restoreAmount' FROM outbox_events WHERE id=%d" % part_event) is None,
       "C3 事件载荷不带 restoreAmount（消费方不许按比例推算）")
    time.sleep(2)
    ok(as_int("SELECT count(*) FROM credit_line_restorations WHERE payment_ref='%s'" % tx_partial) == 0,
       "C3 消费方未回补部分回款")
    ok(as_int("SELECT used_amount FROM credit_lines WHERE id=%d" % CREDIT_LINE_ID) == part["principal"],
       "C3 部分回款后 used_amount 保持=%d" % part["principal"])

    # C4：人工绑定也走真实回补
    manual = finance_vehicle(VIN_MANUAL)
    tx_manual = "PMTX-MAN-" + TAG
    insert_payment_tx(tx_manual, manual["principal"], "NO VIN IN THIS REFERENCE")
    st, res = match_tx(tx_manual)
    ok(st == 200 and D(res).get("matched") is False,
       "C4 摘要无 VIN -> 不能自动匹配（挂起待人工）")
    st, res = manual_match_tx(tx_manual, VIN_MANUAL)
    ok(st == 200 and D(res).get("matchStatus") == "MANUAL_MATCHED",
       "C4 人工绑定 -> %s/%s" % (st, D(res).get("matchStatus")))
    ok(wait_restore(tx_manual, manual["principal"], timeout=40),
       "C4 人工绑定的结清同样真实回补 %d" % manual["principal"])
    # 部分回款那台车仍占着额度，所以这里应等于它的本金，而不是 0
    ok(as_int("SELECT used_amount FROM credit_lines WHERE id=%d" % CREDIT_LINE_ID) == part["principal"],
       "C4 人工绑定回补后 used_amount = 部分回款车仍占用的 %d" % part["principal"])

    print("\n" + "=" * 68)
    if FAILS:
        print("FAIL=%d" % len(FAILS))
        for f in FAILS:
            print("  - " + f)
        return 1
    print("全部断言通过")
    return 0


atexit.register(cleanup)

if __name__ == "__main__":
    sys.exit(main())
