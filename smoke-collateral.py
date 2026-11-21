"""担保物台账端到端验证（V10）。

覆盖：
  1. 放款确认 → 台账建档（已放款）
  2. 批次登记：一次法务局登记覆盖一批车（登记编号 + 担保合同 + 证明书）
  3. 售出 → 自动扣减担保物清单 + 回款义务
  4. 结清 → 担保实体权利消灭 + 抹消待办（三段式）
  5. 登记到期（5 年期限不可续展）
  6. 证据链缺口检查
"""
import hashlib
import hmac
import json
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080"
WEBHOOK_SECRET = "dev-webhook-secret-change-me"
FAILS = []
TS = str(int(time.time()))[-6:]


def ok(cond, msg):
    print(("  [PASS] " if cond else "  [FAIL] ") + msg)
    if not cond:
        FAILS.append(msg)


def call(method, path, body=None, token=None, headers=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


def login(u, p):
    st, res = call("POST", "/api/auth/login", {"username": u, "password": p})
    assert st == 200, (u, res)
    return res["data"]["token"]


def bank_confirm(advance_no, ref):
    ts = str(int(time.time()))
    # 签名串格式必须与 PaymentWebhookVerifier.verify 一致：advanceNo.timestamp.ref（点号分隔）
    msg = f"{advance_no}.{ts}.{ref}"
    sig = hmac.new(WEBHOOK_SECRET.encode(), msg.encode(), hashlib.sha256).hexdigest()
    return call("POST", "/api/advances/%s/confirm" % advance_no, {"paymentRef": ref},
                headers={"X-Payment-Signature": sig, "X-Payment-Timestamp": ts})


admin = login("admin", "admin123")
review = login("review_op", "review123")
operator = login("operator", "operator123")
cashier = login("cashier", "cashier123")
settlement = login("settlement", "settle123")
collateral = login("collateral", "collateral123")   # 担保管理岗
post_lending = login("post_lending", "postlend123")  # 贷后管理岗

print("=" * 68)
print("担保物台账端到端验证")
print("=" * 68)

# ---------- 1. 基础数据 ----------
print("\n① 建经销商与额度")
st, res = call("POST", "/api/dealers",
               {"dealerCode": "DL-COL-" + TS, "name": "担保台账验证经销商",
                "riskGrade": "B", "businessYears": 10}, token=admin)
ok(st == 201, "创建经销商 -> %s" % st)
dealer_id = res["data"]["id"]
st, res = call("POST", "/api/credit-lines",
               {"dealerId": dealer_id, "approvedLimit": 500000000}, token=admin)
ok(st == 201, "核定额度 -> %s" % st)
credit_line_id = res["data"]["id"]

# ---------- 2. 建账 + 放款 ----------
print("\n② 建账并放款确认（台账建档）")
vins = ["COLA" + TS, "COLB" + TS]
st, res = call("POST", "/api/vehicles/batch-ingest", {
    "dealerId": dealer_id, "creditLineId": credit_line_id,
    "purchaseChannel": "OEM", "supplierName": "验证厂家",
    "vehicles": [{"vin": v, "brand": "TOYOTA", "model": "COL", "modelYear": 2026,
                  "invoiceAmount": 1850000, "advanceRatio": 0.90, "color": "WHITE"}
                 for v in vins],
    "idempotencyKey": "ingest-col-" + TS}, token=review)
ok(st == 201 and res["data"]["accepted"] == 2, "建账 2 台 -> %s" % st)

advance_nos = []
for v in vins:
    st, res = call("POST", "/api/advances",
                   {"creditLineId": credit_line_id, "vin": v}, token=operator)
    ok(st == 201 and res["data"]["status"] == "PENDING", "申请垫款 %s -> %s" % (v, st))
    advance_nos.append(res["data"]["advanceId"])
    st, res = call("POST", "/api/advances/%s/disburse" % res["data"]["advanceId"], {}, token=cashier)
    ok(st == 200 and res["data"]["status"] == "PROCESSING", "出纳放款 -> %s" % st)
    st, res = bank_confirm(advance_nos[-1], "BANK-COL-" + TS)
    ok(st == 200 and res["data"]["status"] == "PAID", "银行回调确认 -> %s（PAID）" % st)

st, res = call("GET", "/api/collateral/ledger", token=admin)
rows = [r for r in res["data"] if r["vin"] in vins]
ok(len(rows) == 2, "台账已建档 2 行 -> %d" % len(rows))
ok(all(r["financed"] for r in rows), "两台均标记「已放款」")
ok(all(r["securityStatus"] == "PENDING" for r in rows),
   "担保状态=PENDING（已放款但尚未登记）")

# ---------- 3. 批次登记 ----------
print("\n③ 批次登记：一次法务局登记覆盖一批车")
reg_no = "R-2026-" + TS
st, res = call("POST", "/api/collateral/registration-batches", {
    "vins": vins,
    "registrationNo": reg_no,
    "registrationType": "ASSIGNMENT",
    "perfectionMethod": "REGISTRATION",
    "registryOffice": "東京法務局 動産登記課",
    "registeredAt": "2026-07-01T10:00:00",
    "contractAttachmentId": None,
    "contractNo": "CT-" + TS,
    "contractSignedAt": "2026-06-28T10:00:00",
    "certificateAttachmentId": None,
    "certificateNo": "CERT-" + TS,
}, token=collateral)
ok(st == 200, "批次登记 -> %s" % st)
batch = res["data"]
ok(batch["registrationNo"] == reg_no, "登记编号为字符串且原样保存 -> %r" % batch["registrationNo"])
ok(batch["vehicleCount"] == 2, "本批次覆盖 2 台车 -> %s" % batch["vehicleCount"])
batch_id = batch["batchId"]

# 失效日 = 登记日 + 5 年（法定不可续展）
import datetime
exp = datetime.datetime.fromisoformat(batch["expiresAt"])
reg = datetime.datetime.fromisoformat(batch["registeredAt"])
ok((exp.year - reg.year) == 5, "失效日 = 登记日 + 5 年 -> %s" % batch["expiresAt"])

# 登记编号唯一
st, res = call("POST", "/api/collateral/registration-batches", {
    "vins": vins, "registrationNo": reg_no, "registeredAt": "2026-07-01T10:00:00",
}, token=collateral)
ok(st == 409, "登记编号重复被拒 -> %s/%s" % (st, res.get("code")))

# 未放款的车不能纳入批次
st, res = call("POST", "/api/collateral/registration-batches", {
    "vins": ["NOTFIN" + TS], "registrationNo": "R-EMPTY-" + TS,
    "registeredAt": "2026-07-01T10:00:00",
}, token=collateral)
ok(st == 200 and res["data"]["skippedVins"] == ["NOTFIN" + TS],
   "未放款的车被跳过并说明原因 -> %s" % res.get("data", {}).get("skippedVins"))

# 两台车的台账都推进到已登记
st, res = call("GET", "/api/collateral/ledger", token=admin)
rows = {r["vin"]: r for r in res["data"] if r["vin"] in vins}
ok(all(r["securityStatus"] == "REGISTERED" for r in rows.values()),
   "两台车担保状态推进到 REGISTERED")

# 详情能看到批次
st, res = call("GET", "/api/collateral/ledger/%s" % vins[0], token=admin)
ok(st == 200 and len(res["data"]["registrations"]) == 1, "台账详情可见所属批次")
ok(res["data"]["registrations"][0]["registrationNo"] == reg_no, "详情显示登记编号")

# ---------- 4. 库存释放规则 ----------
print("\n④ 库存释放规则：售出扣减清单 + 回款落点")
st, res = call("POST", "/api/vehicles/%s/sold" % vins[0], {"soldAt": "2026-08-01"}, token=settlement)
ok(st == 200, "标记售出 -> %s" % st)
st, res = call("GET", "/api/collateral/ledger/%s" % vins[0], token=admin)
led = res["data"]
ok(led["sold"] is True, "台账标记「已售出」")
ok(led["released"] is False, "售出**不**等于解除担保（担保权益仍在）")
ok(led["soldUnpaid"] is True, "识别为「已售未回款」敞口")

st, res = call("GET", "/api/collateral/ledger/sold-unpaid", token=admin)
# 注意：any() 的参数必须是可迭代对象，不能直接传布尔。
# 写成 any(vins[0] in (...)) 会报 "'bool' object is not iterable"。
ok(any(vins[0] == r.get("vin") for r in res["data"]), "敞口清单含该车")

# ---------- 5. 结清 → 抹消待办 ----------
print("\n⑤ 结清 → 抹消登记待办（三段式）")
st, res = call("GET", "/api/vehicles/%s" % vins[0], token=admin)
unit = res["data"]
st, res = call("POST", "/api/repayments",
               {"vin": vins[0], "settlementAmount": unit["principalBalance"] + unit["accruedInterest"]},
               token=settlement)
ok(st == 201, "还款结清 -> %s" % st)

st, res = call("GET", "/api/collateral/ledger/%s" % vins[0], token=admin)
led = res["data"]
ok(led["repaid"] is True, "台账标记「已回款」")
ok(led["released"] is True, "台账标记「已解除担保」（实体权利消灭）")
ok(led["registrationDue"] is True, "产生登记待办")
ok("抹消" in (led["registrationNote"] or ""), "待办说明含抹消事项 -> %s" % led["registrationNote"])

st, res = call("GET", "/api/collateral/registration-batches/cancellation-pending", token=admin)
pending = [r for r in res["data"] if r["batchId"] == batch_id]
ok(len(pending) == 1, "抹消待办清单含该批次")
ok(pending[0]["cancellationStatus"] == "REQUESTED", "抹消状态=待申请（双方共同申请制）")

# 三段式推进
st, res = call("POST", "/api/collateral/registration-batches/%d/cancellation/apply" % batch_id,
                {}, token=collateral)
ok(st == 200 and res["data"]["cancellationStatus"] == "APPLIED", "提交抹消申请 -> %s" % st)
st, res = call("POST", "/api/collateral/registration-batches/%d/cancellation/complete" % batch_id,
                {"cancellationNo": "CANCEL-" + TS}, token=collateral)
ok(st == 200 and res["data"]["cancellationStatus"] == "DONE", "抹消完成 -> %s" % st)

st, res = call("GET", "/api/collateral/ledger/%s" % vins[0], token=admin)
led = res["data"]
ok(led["registrationDue"] is False, "抹消完成后待办消除")

# ---------- 6. 证据链 ----------
print("\n⑥ 证据链")
st, res = call("GET", "/api/collateral/ledger/%s" % vins[0], token=admin)
chain = res["data"]["evidenceChain"]
types = [e["evidenceType"] for e in chain]
ok("ADVANCE" in types, "证据链含放款凭证 -> %s" % types)
ok("REPAYMENT" in types, "证据链含回款凭证")
ok("CONTRACT" in types, "证据链含担保合同")
ok("REGISTRATION_CERT" in types, "证据链含登记事项证明书")
ok("CANCELLATION_CERT" in types, "证据链含抹消凭证")

st, res = call("GET", "/api/collateral/evidence-gaps", token=admin)
gaps = res["data"]
ok(isinstance(gaps, list), "证据链缺口检查可调用（缺口 %d 条）" % len(gaps))

# ---------- 7. 权限边界 ----------
print("\n⑦ 权限边界")
st, res = call("POST", "/api/collateral/registration-batches",
               {"vins": vins, "registrationNo": "R-NOPERM-" + TS,
                "registeredAt": "2026-07-01T10:00:00"}, token=operator)
ok(st == 403 and res["code"] == "AUTH_002", "业务经办岗调批次登记被拒 -> %s/%s" % (status if False else st, res.get("code")))

st, res = call("POST", "/api/collateral/ledger/%s/required-account" % vins[1],
               {"accountRef": "ACC-NOT-EXIST"}, token=post_lending)
ok(st == 404, "贷后岗指定不存在的账户被拒 -> %s" % st)

print("\n" + "=" * 68)
if FAILS:
    print("失败 %d 项：" % len(FAILS))
    for f in FAILS:
        print("  - " + f)
    raise SystemExit(1)
print("担保物台账端到端验证全部通过 ✅")
print("=" * 68)
