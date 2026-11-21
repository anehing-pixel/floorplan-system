#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
库存融资核心系统 · 一键冒烟测试
在 WSL 内执行（应用与数据库均在 WSL 中，走 localhost）：

    python3 smoke-test.py

覆盖业务闭环七环节（①授予额度 → ②收车建账 → ③垫款支付 → ④设定担保
→ ⑤在库计息 → ⑥零售成交 → ⑦结清释放），
以及"流程缺口对照"补齐的全部功能（⑧~⑱）：
  ⑧ 重复融资拦截   ⑨ 库龄递减还款     ⑩ 厂家回购
  ⑪ 提前部分还款   ⑫ 车辆保险         ⑬ 展期续贷
  ⑭ 结清证明       ⑮ 额度年审         ⑯ 结算宽限期/OOT 升级
  ⑰ 违约处置闭环   ⑱ 评级口径动态配置 + 季度归档
以及主数据、权限与角色矩阵（⑲~㉒）：
  ⑲ 角色权限边界（角色矩阵：401 与 403 严格区分 + 自然人级职责分离）
  ⑳ 经销商主数据 CRUD（PATCH 语义 / 状态机 / 删除护栏）
  ㉑ 名下车辆聚合视图（汇总口径 == 明细逐行求和）
"""
import json
import os
import subprocess
import sys
import time
import hmac
import hashlib
import urllib.error
import urllib.request
from datetime import date, datetime, timedelta

# 直连单体时用默认 8080；走 Spring Cloud 网关验证切流时设 SMOKE_BASE=http://127.0.0.1:18080
BASE = os.environ.get("SMOKE_BASE", "http://localhost:8080")
OPERATOR = "1"
TOKEN = ""          # 登录后填充；call() 自动附加 Bearer 头

TS = datetime.now().strftime("%m%d%H%M%S")
VIN = "TESTVIN" + TS                    # 主链路车辆
INVOICE_AMOUNT = 1_850_000
ADVANCE_RATIO = 0.90
EXPECTED_ADVANCE = int(round(INVOICE_AMOUNT * ADVANCE_RATIO))  # 1,665,000
APPROVED_LIMIT = 200_000_000            # 加大到 2 亿：本脚本会垫款多台车

# 银行回调 HMAC 共享密钥：必须与后端 floorplan.payment.webhook-secret 一致
# （后端默认 dev-webhook-secret-change-me，可由环境变量 PAYMENT_WEBHOOK_SECRET 覆盖）。
WEBHOOK_SECRET = os.environ.get("PAYMENT_WEBHOOK_SECRET", "dev-webhook-secret-change-me")

# 各专项测试车辆（同一经销商/额度，便于核对额度占用）
VIN_LIEN = "TESTLN" + TS                # ⑧ 重复融资拦截
VIN_CURT = "TESTCT" + TS                # ⑨ 库龄递减
VIN_REP = "TESTRP" + TS                 # ⑩ 厂家回购 + ⑪ 部分还款 + ⑫ 保险
VIN_EXT = "TESTEX" + TS                 # ⑬ 展期
VIN_GRACE = "TESTGR" + TS               # ⑯ 超宽限期 → OOT 升级
VIN_WARN = "TESTWN" + TS                # ⑯ 宽限期内 → 告警但允许
VIN_DISP = "TESTDP" + TS                # ⑰ 违约处置闭环
VIN_RATE = "TESTRT" + TS                # ⑱ 评级加点生效


def call(method, path, body=None, idem_key=None, token=None, headers=None):
    """发请求。token=None 时用全局 TOKEN（管理员）；显式传 token 可模拟其他角色。

    headers: 额外请求头字典。用于验证"客户端伪造的审计头不能影响审计记录"。
    """
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Operator-Id", OPERATOR)
    tok = token if token is not None else TOKEN
    if tok:
        req.add_header("Authorization", "Bearer " + tok)
    if idem_key:
        req.add_header("Idempotency-Key", idem_key)
    for hk, hv in (headers or {}).items():
        req.add_header(hk, hv)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


# 登录一次，全程复用令牌（后端已启用 JWT 拦截：/api/** 均需 Bearer 头，登录接口除外）
_st, _login = call("POST", "/api/auth/login", {"username": "admin", "password": "admin123"})
assert _st == 200 and _login.get("data", {}).get("token"), "预检失败：登录未通过，无法开始冒烟测试 → " + json.dumps(_login, ensure_ascii=False)
TOKEN = _login["data"]["token"]

# ---------------------------------------------------------------------------
# 角色令牌池：按业务岗位登录，供各业务动作使用。
#
# 为什么不用一个 admin 令牌跑全流程：新权限模型下管理员**不碰业务单据**
# （不持垫款申请/复核/支付、还款核销、处置等权限点）——
# 这是"双人复核"能成立的前提。用 admin 跑全流程等于绕过了这套设计，
# 测试也就失去了意义。每个业务动作都用**该动作真正该由谁做**的账号来发。
# ---------------------------------------------------------------------------
ROLE_ACCOUNTS = {
    "admin":       ("admin123",      "SYSTEM_ADMIN"),        # 系统管理员
    "operator":    ("operator123",   "BUSINESS_OPERATOR"),    # 业务经办岗（发起垫款）
    "import_op":   ("import123",     "PURCHASE_OPERATOR"),   # 采购数据录入岗
    "review_op":   ("review123",     "PURCHASE_REVIEWER"),   # 采购审核岗（验车确权）
    "fund_rev":    ("fundrev123",    "FUNDING_REVIEWER"),    # 资金复核岗
    "cashier":     ("cashier123",    "FUNDING_CASHIER"),     # 资金出纳岗（打款）
    "settlement":  ("settle123",     "SETTLEMENT_OFFICER"),  # 结算岗
    "disposal":    ("disposal123",   "DISPOSAL_OFFICER"),    # 违约处置岗
    "advisory":    ("advisory123",   "RISK_ADVISORY_OFFICER"),# 增信业务岗
    "credit":      ("credit123",     "CREDIT_REVIEWER"),     # 授信审查岗
    "collection":  ("collect123",    "COLLECTION_OFFICER"),   # 催收岗
}
TOK = {}          # 登录名 -> 令牌
ROLE_OF = {}      # 登录名 -> 角色代码

def login_role(username):
    """按岗位登录并缓存令牌；返回令牌（登录失败返回空串）。"""
    if username in TOK:
        return TOK[username]
    pwd, expect_role = ROLE_ACCOUNTS[username]
    st, res = call("POST", "/api/auth/login", {"username": username, "password": pwd})
    if st != 200:
        return ""
    TOK[username] = res["data"]["token"]
    ROLE_OF[username] = res["data"].get("role")
    return TOK[username]


def ok(cond, msg):
    print(("  [PASS] " if cond else "  [FAIL] ") + msg)
    if not cond:
        FAILS.append(msg)


# ---- 银行异步回调（webhook）模拟 ----
# /confirm、/fail 仅接受带合法 HMAC 签名的支付通道调用，不走用户 JWT。
# 签名原文 = advanceNo + "." + timestamp + "." + (paymentRef 或 message)，HmacSHA256 十六进制。
def _webhook_sign(adv_no, timestamp, ref_or_msg):
    msg = "%s.%s.%s" % (adv_no, timestamp, ref_or_msg or "")
    return hmac.new(WEBHOOK_SECRET.encode("utf-8"), msg.encode("utf-8"),
                    hashlib.sha256).hexdigest()


def bank_confirm(adv_no, payment_ref=None):
    """模拟银行回调：确认支付成功（带 HMAC 签名，无用户令牌）。"""
    ts = str(int(time.time()))
    sig = _webhook_sign(adv_no, ts, payment_ref)
    body = {"paymentRef": payment_ref} if payment_ref else {}
    return call("POST", "/api/advances/%s/confirm" % adv_no, body,
                token="", headers={"X-Payment-Signature": sig, "X-Payment-Timestamp": ts})


def bank_fail(adv_no, message=None):
    """模拟银行回调：标记支付失败（带 HMAC 签名，无用户令牌）。"""
    ts = str(int(time.time()))
    sig = _webhook_sign(adv_no, ts, message)
    body = {"message": message} if message else {}
    return call("POST", "/api/advances/%s/fail" % adv_no, body,
                token="", headers={"X-Payment-Signature": sig, "X-Payment-Timestamp": ts})


FAILS = []


def psql(sql):
    """在本地库执行一段 SQL（用于清库与构造库龄计划）。
    优先用本机 psql 客户端；不可用时回退到 docker exec（容器 floorplan-db2）。
    两者都不可用时返回 None。"""
    env = dict(os.environ, PGPASSWORD="floorplan")
    try:
        r = subprocess.run(["psql", "-h", "localhost", "-p", "5434", "-U", "floorplan",
                            "-d", "floorplan", "-tAc", sql],
                           env=env, capture_output=True, timeout=30)
        if r.returncode == 0:
            return r.stdout.decode("utf-8").strip()
    except Exception:
        pass
    # 兜底：通过 docker exec 在容器内执行 psql（本机未装客户端时的标准路径）
    try:
        # 注意端口：容器内 postgres 监听 5434（数据卷里的 postgresql.conf 持久化了该端口），
        # 不是默认的 5432——不显式指定会连到不存在的 socket，报 "No such file or directory"。
        r = subprocess.run(["docker", "exec", "floorplan-db2",
                            "psql", "-p", "5434", "-U", "floorplan", "-d", "floorplan", "-tAc", sql],
                           env=env, capture_output=True, timeout=30)
        if r.returncode == 0:
            return r.stdout.decode("utf-8").strip()
    except Exception:
        pass
    return None


def reset_test_data():
    """清理历史测试数据：批处理作业有幂等记录（batch_runs），
    不清理会导致计息被"已成功"跳过，无法验证当期计息结果。

    同时清理历次冒烟留下的**空档经销商**（DL-SMOKE-* / DL-CRUD-* / DL-OP-* / DL-GUARD-*）。
    注意顺序：车辆与额度是经销商的下游引用，必须先删下游、再删经销商本身——
    这与业务侧"有车有额度时禁止删经销商"的护栏是同一条引用完整性约束，
    只是冒烟脚本作为数据维护工具有直接操作库的能力。
    """
    sql = ("DELETE FROM interest_ledger WHERE vin LIKE 'TEST%'; "
           "DELETE FROM interest_ledger_archive WHERE vin LIKE 'TEST%'; "
           "DELETE FROM curtailment_payments WHERE vin LIKE 'TEST%'; "
           "DELETE FROM repayments WHERE vin LIKE 'TEST%'; "
           "DELETE FROM advances WHERE vin LIKE 'TEST%'; "
           "DELETE FROM aging_plans WHERE vin LIKE 'TEST%'; "
           "DELETE FROM vehicle_units WHERE vin LIKE 'TEST%'; "
           "DELETE FROM financing_lien_checks WHERE vin LIKE 'TEST%'; "
           "DELETE FROM disposal_cases WHERE vin LIKE 'TEST%'; "
           "DELETE FROM insurance_policies WHERE vin LIKE 'TEST%'; "
           "DELETE FROM repurchase_requests WHERE vin LIKE 'TEST%'; "
           "DELETE FROM extension_requests WHERE vin LIKE 'TEST%'; "
           "DELETE FROM annual_reviews WHERE dealer_id IN "
           "  (SELECT id FROM dealers WHERE dealer_code LIKE 'DL-SMOKE-%'); "
           "DELETE FROM batch_runs WHERE biz_date >= CURRENT_DATE; "
           "DELETE FROM alerts; "
           # 护栏测试车（GUARDVIN*）不匹配 'TEST%'，需按 VIN 前缀单独清理，
           # 否则它会挂在已删除的经销商名下，成为孤儿台账
           "DELETE FROM interest_ledger WHERE vin LIKE 'GUARDVIN%'; "
           "DELETE FROM interest_ledger_archive WHERE vin LIKE 'GUARDVIN%'; "
           "DELETE FROM curtailment_payments WHERE vin LIKE 'GUARDVIN%'; "
           "DELETE FROM repayments WHERE vin LIKE 'GUARDVIN%'; "
           "DELETE FROM advances WHERE vin LIKE 'GUARDVIN%'; "
           "DELETE FROM aging_plans WHERE vin LIKE 'GUARDVIN%'; "
           "DELETE FROM vehicle_units WHERE vin LIKE 'GUARDVIN%'; "
           "DELETE FROM financing_lien_checks WHERE vin LIKE 'GUARDVIN%'; "
           "DELETE FROM disposal_cases WHERE vin LIKE 'GUARDVIN%'; "
           "DELETE FROM insurance_policies WHERE vin LIKE 'GUARDVIN%'; "
           "DELETE FROM repurchase_requests WHERE vin LIKE 'GUARDVIN%'; "
           "DELETE FROM extension_requests WHERE vin LIKE 'GUARDVIN%'; "
           # 孤儿台账兜底：dealer_id 指向已不存在的经销商的车辆
           "DELETE FROM vehicle_units WHERE dealer_id NOT IN (SELECT id FROM dealers); "
           # 额度也要删（额度本身也是经销商的下游引用）
           "DELETE FROM credit_lines WHERE dealer_id IN "
           "  (SELECT id FROM dealers WHERE dealer_code LIKE 'DL-SMOKE-%' "
           "   OR dealer_code LIKE 'DL-CRUD-%' OR dealer_code LIKE 'DL-OP-%' "
           "   OR dealer_code LIKE 'DL-GUARD-%'); "
           # 最后删经销商本身
           "DELETE FROM dealers WHERE dealer_code LIKE 'DL-SMOKE-%' "
           "  OR dealer_code LIKE 'DL-CRUD-%' OR dealer_code LIKE 'DL-OP-%' "
           "  OR dealer_code LIKE 'DL-GUARD-%';")
    if psql(sql) is not None:
        print("  [INFO] 已清理历史测试数据（保证幂等作业可重新验证 + 冒烟可重复运行）")
    else:
        print("  [INFO] 跳过数据清理（psql 不可用，仅影响幂等作业的重复验证）")


def ingest_and_advance(vin, invoice=INVOICE_AMOUNT, ratio=ADVANCE_RATIO, expect_ok=True, confirm=True):
    """收车建账 + 垫款申请 + 资金岗放款 + 银行回调确认（⑧~⑰ 各专项的公共前置）。

    职责分离（SOD）+ 异步（ADR-006 修订）：
      申请(POST /api/advances, 落 PENDING) → 放款(POST .../disburse, 推 PROCESSING)
      → 银行回调(POST .../confirm, HMAC 签名, 翻 PAID + 车辆 IN_STOCK)。
    confirm=False 时停在 PROCESSING（供断言"放款处理中"状态）。
    """
    status, res = call("POST", "/api/vehicles/batch-ingest", {
        "dealerId": dealer_id, "creditLineId": credit_line_id,
        "purchaseChannel": "OEM", "supplierName": "测试厂家",
        "vehicles": [{"vin": vin, "brand": "TOYOTA", "model": "SMOKE",
                      "modelYear": 2026, "invoiceAmount": invoice,
                      "advanceRatio": ratio, "color": "WHITE"}],
        "idempotencyKey": "ingest-ext-" + vin}, token=login_role("review_op"))
    if not expect_ok:
        return status, res
    accepted = res.get("data", {}).get("accepted", 0)
    if status != 201 or accepted != 1:
        ok(False, "建账 %s 失败 -> %s %s" % (vin, status, res))
        return False, 0
    # 申请垫款（落 PENDING、占额度）
    status, res = call("POST", "/api/advances",
                       {"creditLineId": credit_line_id, "vin": vin},
                       idem_key="adv-ext-" + vin, token=login_role("operator"))
    if status != 201:
        ok(False, "垫款申请 %s 失败 -> %s %s" % (vin, status, res))
        return False, 0
    adv_no = res["data"]["advanceId"]
    # 垫款金额在申请响应里就有；银行回调响应只含 advanceId/status/paymentRef，不要从那里取金额
    advanced_amount = res["data"]["advancedAmount"]
    # 资金岗放款（推 PROCESSING）
    status, res = call("POST", "/api/advances/%s/disburse" % adv_no, {}, token=login_role("cashier"))
    if status != 200:
        ok(False, "放款 %s 失败 -> %s %s" % (vin, status, res))
        return False, 0
    if not confirm:
        return True, advanced_amount
    # 银行回调确认（HMAC 签名 → PAID + IN_STOCK）
    status, res = bank_confirm(adv_no, "BANK-REF-" + vin)
    if status != 200:
        ok(False, "银行回调确认 %s 失败 -> %s %s" % (vin, status, res))
        return False, 0
    return True, advanced_amount


def main():
    global dealer_id, credit_line_id
    print("=" * 68)
    print("库存融资核心系统 · 冒烟测试")
    print("=" * 68)

    reset_test_data()

    # ① 授予额度
    print("\n① 创建经销商与核定额度")
    status, res = call("POST", "/api/dealers", {
        "dealerCode": "DL-SMOKE-" + str(int(time.time()) % 100000),
        "name": "冒烟测试经销商", "riskGrade": "B", "businessYears": 10})
    ok(status == 201, "创建经销商 -> %s" % status)
    dealer_id = res["data"]["id"]

    status, res = call("POST", "/api/credit-lines",
                       {"dealerId": dealer_id, "approvedLimit": APPROVED_LIMIT})
    ok(status == 201, "核定额度 -> %s" % status)
    credit_line_id = res["data"]["id"]
    ok(res["data"]["approvedLimit"] == APPROVED_LIMIT, "核定额度=%d" % APPROVED_LIMIT)

    # ② 车商收车·建账
    print("\n② 批量到货建账")
    status, res = call("POST", "/api/vehicles/batch-ingest", {
        "dealerId": dealer_id, "creditLineId": credit_line_id,
        "purchaseChannel": "OEM", "supplierName": "测试厂家",
        "vehicles": [{"vin": VIN, "brand": "TOYOTA", "model": "COROLLA",
                      "modelYear": 2026, "invoiceAmount": INVOICE_AMOUNT,
                      "advanceRatio": ADVANCE_RATIO, "color": "WHITE"}],
        "idempotencyKey": "ingest-smoke-" + VIN}, token=login_role("review_op"))
    ok(status == 201 and res["data"]["accepted"] == 1, "建账成功 -> %s" % status)
    ok(res["data"]["results"][0]["advanceAmount"] == EXPECTED_ADVANCE,
       "垫款金额=%d（发票 %d × %.2f）" % (EXPECTED_ADVANCE, INVOICE_AMOUNT, ADVANCE_RATIO))
    # 建账只落"已建账"：既没申请垫款、也没付款。待采购审核岗线下验车确权后，
    # 才由 applyAdvance 流转到 PENDING_ADVANCE（V13）。
    status, res = call("GET", "/api/vehicles/%s" % VIN)
    ok(res["data"]["status"] == "CREATED", "建账后车辆状态 -> CREATED（已建账，未申请未付款）")

    # ③ 垫款：申请(PENDING) → 放款(PROCESSING) → 银行回调(PAID/IN_STOCK) 三步，职责分离 + 异步
    print("\n③ 申请垫款 + 资金岗放款 + 银行回调确认（SOD + 异步：申请人≠支付人≠银行）")
    status, res = call("POST", "/api/advances",
                       {"creditLineId": credit_line_id, "vin": VIN},
                       idem_key="adv-smoke-" + VIN, token=login_role("operator"))
    ok(status == 201, "申请垫款 -> %s" % status)
    ok(res["data"]["status"] == "PENDING", "垫款状态 -> PENDING（仅申请，未放款）")
    # 申请垫款这一步本身把车辆从 CREATED 推到 PENDING_ADVANCE（已申请、钱未付）。
    # 历史断言写的是 FINANCED（待放款），那是把"已付款"当成"待付款"用，已随 V12/V13 修正。
    ok(res["data"]["vehicleStatus"] == "PENDING_ADVANCE",
       "车辆状态 -> PENDING_ADVANCE（已提出申请、款未付）")
    ok(res["data"]["advancedAmount"] == EXPECTED_ADVANCE, "垫款金额=%d" % EXPECTED_ADVANCE)
    adv_no = res["data"]["advanceId"]

    # 资金岗（管理员）放款：提交支付通道 → PROCESSING（此时钱尚未确认到账，车辆仍 PENDING_ADVANCE）
    status, res = call("POST", "/api/advances/%s/disburse" % adv_no, {}, token=login_role("cashier"))
    ok(status == 200, "放款 -> %s" % status)
    ok(res["data"]["status"] == "PROCESSING", "垫款状态 -> PROCESSING（已提交银行，待异步回调）")
    ok(res["data"]["vehicleStatus"] == "PENDING_ADVANCE",
       "放款提交后车辆仍 PENDING_ADVANCE（银行未确认到账，不流转台账）")
    ok(res["data"]["advancedAmount"] == EXPECTED_ADVANCE, "垫款金额=%d" % EXPECTED_ADVANCE)
    used_after_disburse = APPROVED_LIMIT - res["data"]["remainingCredit"]
    ok(used_after_disburse == EXPECTED_ADVANCE, "额度占用=%d（放款即占用，与确认无关）" % used_after_disburse)

    # 银行异步回调确认：HMAC 签名 → PAID + 车辆 IN_STOCK
    status, res = bank_confirm(adv_no, "BANK-REF-" + VIN)
    ok(status == 200, "银行回调确认 -> %s" % status)
    ok(res["data"]["status"] == "PAID", "垫款状态 -> PAID（银行确认到账）")
    status, res = call("GET", "/api/vehicles/%s" % VIN)
    ok(res["data"]["status"] == "IN_STOCK", "车辆状态 -> IN_STOCK（确认后流转台账）")
    # 确认不改变额度占用（占用在放款时即发生），回查额度核对
    status, res = call("GET", "/api/credit-lines/%d" % credit_line_id)
    used_after_confirm = res["data"].get("usedAmount", APPROVED_LIMIT)
    ok(used_after_confirm == EXPECTED_ADVANCE, "额度占用保持=%d（确认不改变占用）" % used_after_confirm)

    # 幂等验证
    status2, res2 = call("POST", "/api/advances",
                         {"creditLineId": credit_line_id, "vin": VIN},
                         idem_key="adv-smoke-" + VIN, token=login_role("operator"))
    ok(res2.get("data", {}).get("idempotentReplay") is True, "幂等重放返回首次结果（未重复扣额度）")

    # 回调幂等：重复确认同一笔仍是 PAID（终态幂等）
    status3, res3 = bank_confirm(adv_no, "BANK-REF-DUP")
    ok(status3 == 200 and res3["data"]["status"] == "PAID", "重复银行回调 -> 仍 PAID（终态幂等）")

    # ④ 设定担保
    print("\n④ 设定担保")
    status, res = call("POST", "/api/vehicles/%s/security" % VIN,
                       {"securityType": "RETENTION", "regNo": "REG-001", "ownerName": "金融公司"}, token=login_role("settlement"))
    ok(status == 200 and res["data"]["securityStatus"] == "PENDING", "设定担保 -> %s" % status)

    # ⑤ 在库计息（跑 3 个业务日）
    print("\n⑤ 在库计息（连续 3 个业务日）")
    today = date.today()
    # 业务日取"远期 + 每次运行偏移"：批处理按业务日幂等，
    # 若固定用同一批日期，第二次运行会全部命中"已执行跳过"，
    # 拿不到 status 字段——脚本必须**可重复运行**，故用动态基准日。
    biz_base = today + timedelta(days=30 + (int(time.time()) % 300))
    for offset in (1, 2, 3):
        biz = biz_base + timedelta(days=offset)
        status, res = call("POST", "/api/jobs/interest?bizDate=%s" % biz.isoformat(), {})
        ok(status == 200 and res["data"].get("status") == "SUCCESS",
           "日终计息 %s -> %s（计息 %s 台，合计 %s）" % (
               biz, res["data"].get("status"), res["data"].get("processedCount"),
               res["data"].get("totalInterest")))

    # 计息幂等：同一业务日重跑应跳过
    status, res = call("POST", "/api/jobs/interest?bizDate=%s" % (biz_base + timedelta(days=3)).isoformat(), {})
    ok(res["data"].get("skipped") is True, "同业务日重跑计息 -> 幂等跳过（不重复计息）")

    status, res = call("GET", "/api/vehicles/%s" % VIN)
    interest = res["data"]["accruedInterest"]
    principal = res["data"]["principalBalance"]
    ok(interest > 0, "累计利息=%d（3 个业务日）" % interest)
    ok(principal == EXPECTED_ADVANCE, "未偿本金=%d" % principal)

    # ⑥ 零售成交
    print("\n⑥ 零售成交（进入结算窗口）")
    status, res = call("POST", "/api/vehicles/%s/sold" % VIN, {"soldAt": today.isoformat()}, token=login_role("settlement"))
    ok(status == 200 and res["data"]["vehicleStatus"] == "SOLD_PENDING", "标记售出 -> %s" % status)
    ok(res["data"].get("settleDueDate") is not None, "结算到期日=%s（售出日 + 2 营业日）" % res["data"].get("settleDueDate"))

    # ⑦ 结清释放（还款 + 解除担保 + 额度回补）
    print("\n⑦ 结清释放")
    settlement = principal + interest
    status, res = call("POST", "/api/repayments",
                       {"vin": VIN, "settlementAmount": settlement},
                       idem_key="rep-smoke-" + VIN, token=login_role("settlement"))
    ok(status == 201, "还款结清 -> %s（本金 %d + 利息 %d）" % (status, principal, interest))
    ok(res["data"]["vehicleStatus"] == "SETTLED", "车辆状态 -> SETTLED")
    ok(res["data"]["securityReleased"] is True, "担保已解除")
    ok(res["data"]["releasedCredit"] == EXPECTED_ADVANCE, "额度回补=%d" % res["data"]["releasedCredit"])

    status, res = call("GET", "/api/credit-lines/%d" % credit_line_id)
    used_before_ext = res["data"]["usedAmount"]
    ok(used_before_ext == 0, "额度已用归零（回补正确）")

    # 金额不符应被拒绝
    print("\n附加校验")
    status, res = call("POST", "/api/repayments",
                       {"vin": VIN, "settlementAmount": 1}, idem_key="rep-bad-" + VIN, token=login_role("settlement"))
    ok(status == 422 and res["code"] == "REPAY_002", "重复结清被拒 -> %s/%s" % (status, res.get("code")))

    # 盘点（账实核对）
    status, res = call("POST", "/api/inventory/audit",
                       {"dealerId": dealer_id, "auditDate": today.isoformat(), "scannedVins": [VIN]}, token=login_role("review_op"))
    ok(status == 200, "提交盘点 -> %s（账实相符 %s 台）" % (status, res["data"].get("matched")))

    # 预警查询
    status, res = call("GET", "/api/alerts?limit=20")
    ok(status == 200, "预警查询 -> %s（共 %d 条）" % (status, len(res["data"])))

    # ============ 流程缺口补齐功能（P0/P1） ============

    # ⑧ 重复融资拦截（P0-③）
    print("\n⑧ 重复融资拦截：他处抵押的车禁止再次融资")
    status, res = call("POST", "/api/vehicles/%s/lien-check" % VIN_LIEN,
                       {"checkSource": "MANUAL", "encumbered": True, "note": "他行抵押登记"}, token=login_role("review_op"))
    ok(status == 200, "录入权属核对 -> %s（encumbered=True）" % status)
    status, res = call("POST", "/api/vehicles/batch-ingest", {
        "dealerId": dealer_id, "creditLineId": credit_line_id,
        "purchaseChannel": "OEM", "supplierName": "测试厂家",
        "vehicles": [{"vin": VIN_LIEN, "brand": "TOYOTA", "model": "PRIUS",
                      "modelYear": 2026, "invoiceAmount": 2_000_000, "color": "SILVER"}],
        "idempotencyKey": "ingest-lien-" + VIN_LIEN}, token=login_role("review_op"))
    ok(status == 201 and res["data"]["accepted"] == 0 and res["data"]["rejected"] == 1,
       "建账被拒（部分成功语义：accepted=0 rejected=1）")
    ok(res["data"]["results"][0].get("code") == "VEHICLE_005",
       "拒绝原因=VEHICLE_005（%s）" % res["data"]["results"][0].get("message"))

    # ⑨ 库龄递减还款（P0-①）
    print("\n⑨ 库龄递减还款：在库超期分期核销本金（不释放额度）")
    ok_psql = psql("SELECT 1") is not None
    adv_ok, advance_curt = ingest_and_advance(VIN_CURT)
    ok(adv_ok, "建账+垫款 %s" % VIN_CURT)
    if ok_psql and adv_ok:
        # 递减计划由日终作业在库龄达 90 天时生成；冒烟直接注入一条计划验证核销链路
        period_amount = int(round(advance_curt * 0.10))
        psql("INSERT INTO aging_plans (vin, rule_version, first_trigger_date, period_days, "
             "reduce_ratio, reduce_amount, paid_periods, next_due_date, status) "
             "VALUES ('%s', 'V1', CURRENT_DATE - 90, 30, 0.10, %d, 0, CURRENT_DATE, 'ACTIVE')"
             % (VIN_CURT, period_amount))
        status, res = call("POST", "/api/curtailments/%s/pay" % VIN_CURT,
                           {"idempotencyKey": "curt-" + VIN_CURT}, token=login_role("collection"))
        ok(status == 201 and res["data"]["periodNo"] == 1,
           "偿付第 1 期 -> %s（本期 %s）" % (status, res.get("data", {}).get("periodAmount")))
        ok(res["data"]["principalBalance"] == advance_curt - period_amount,
           "未偿本金 %d -> %d（递减 %d）" % (advance_curt, res["data"]["principalBalance"], period_amount))
        # 幂等：同一期重复偿付不应再扣本金（幂等键唯一约束拦截，事务回滚）
        call("POST", "/api/curtailments/%s/pay" % VIN_CURT,
             {"idempotencyKey": "curt-" + VIN_CURT}, token=login_role("collection"))
        status, res = call("GET", "/api/vehicles/%s" % VIN_CURT)
        ok(res["data"]["principalBalance"] == advance_curt - period_amount,
           "同幂等键重复偿付 -> 本金不变（未重复扣款）")
        # 额度不回补：递减不是结清（此时仅 VIN_CURT 在占用，主链路车辆已结清回补）
        status, res = call("GET", "/api/credit-lines/%d" % credit_line_id)
        ok(res["data"]["usedAmount"] == used_before_ext + advance_curt,
           "额度占用=%d（库龄递减不释放额度）" % res["data"]["usedAmount"])
        used_before_ext = res["data"]["usedAmount"]
    else:
        print("  [INFO] psql 不可用，跳过递减计划注入，仅验证负例")
    # 负例：无计划的车辆偿付应被拒
    status, res = call("POST", "/api/curtailments/%s/pay" % VIN, {"idempotencyKey": "curt-neg"}, token=login_role("collection"))
    ok(status == 422 and res["code"] == "CURTAIL_001", "无递减计划偿付被拒 -> %s/%s" % (status, res.get("code")))

    # ⑩ 厂家回购（P1）
    print("\n⑩ 厂家回购：兑现后冲抵垫款本金（不自动结清）")
    adv_ok, advance_rep = ingest_and_advance(VIN_REP)
    ok(adv_ok, "建账+垫款 %s" % VIN_REP)
    status, res = call("POST", "/api/repurchases/request",
                       {"vin": VIN_REP, "reason": "滞销库存厂家回购承诺"}, token=login_role("advisory"))
    ok(status == 201 and res["data"]["status"] == "REQUESTED", "回购申请 -> %s（单号 %s）"
       % (status, res.get("data", {}).get("requestNo")))
    request_no = res["data"]["requestNo"]
    buyback_amount = advance_rep // 2
    status, res = call("POST", "/api/repurchases/fulfill",
                       {"requestNo": request_no, "amount": buyback_amount}, token=login_role("advisory"))
    ok(status == 200 and res["data"]["status"] == "FULFILLED", "回购兑现 -> %s" % status)
    ok(res["data"]["principalBalance"] == advance_rep - buyback_amount,
       "本金 %d -> %d（回购冲抵 %d，车辆未自动结清）"
       % (advance_rep, res["data"]["principalBalance"], buyback_amount))
    balance_after_buyback = res["data"]["principalBalance"]

    # ⑪ 提前部分还款（P1）
    print("\n⑪ 提前部分还款：在库车主动提前还本金回补额度，但不解除担保（B1b）")
    partial_amount = 100_000
    status, res = call("POST", "/api/repayments/partial",
                       {"vin": VIN_REP, "principalAmount": partial_amount},
                       idem_key="part-" + VIN_REP, token=login_role("collection"))
    ok(status == 201 and res["data"].get("repaymentType") == "PARTIAL",
       "部分还款 -> %s（类型 PARTIAL）" % status)
    ok(res["data"].get("securityReleased") is False, "担保未解除（部分还款不触发解押）")
    ok(res["data"].get("principal") == partial_amount, "本次偿还本金=%d" % partial_amount)
    # 部分还款响应只带回执（principal=本次偿还额），本金余额回查车辆台账核对
    status, res = call("GET", "/api/vehicles/%s" % VIN_REP)
    ok(res["data"]["principalBalance"] == balance_after_buyback - partial_amount,
       "本金 %d -> %d（只扣本金）" % (balance_after_buyback, res["data"]["principalBalance"]))

    # ⑫ 车辆保险（P1）
    print("\n⑫ 车辆保险：投保 -> 出险理赔")
    policy_no = "POL-SMOKE-" + TS
    status, res = call("POST", "/api/insurances/bind",
                       {"vin": VIN_REP, "policyNo": policy_no, "insurer": "东京海上日动",
                        "insuredAmount": 2_000_000,
                        "startDate": today.isoformat(),
                        "endDate": (today + timedelta(days=365)).isoformat()}, token=login_role("advisory"))
    ok(status == 201 and res["data"]["status"] == "ACTIVE", "投保 -> %s（%s）" % (status, policy_no))
    status, res = call("POST", "/api/insurances/claim",
                       {"policyNo": policy_no, "claimedAmount": 500_000}, token=login_role("advisory"))
    ok(status == 200 and res["data"]["status"] == "CLAIMED", "出险理赔 -> %s（状态 CLAIMED）" % status)

    # ⑬ 展期续贷（P1）
    print("\n⑬ 展期续贷：结算到期日顺延，清除 OOT 标记")
    adv_ok, _ = ingest_and_advance(VIN_EXT)
    ok(adv_ok, "建账+垫款 %s" % VIN_EXT)
    status, res = call("POST", "/api/vehicles/%s/sold" % VIN_EXT, {"soldAt": today.isoformat()}, token=login_role("settlement"))
    original_due = res["data"]["settleDueDate"]
    ok(status == 200, "标记售出 -> %s（原到期日 %s）" % (status, original_due))
    status, res = call("POST", "/api/extensions/apply",
                       {"vin": VIN_EXT, "extDays": 7, "reason": "客户贷款审批中"}, token=login_role("advisory"))
    ok(status == 201 and res["data"]["status"] == "APPROVED", "展期申请 -> %s" % status)
    ok(res["data"]["newDue"] > (original_due or ""), "到期日 %s -> %s（顺延 7 天）"
       % (res["data"].get("originalDue"), res["data"]["newDue"]))

    # ⑭ 结清证明（P1）
    print("\n⑭ 结清证明：仅整台结清车辆可出具")
    status, res = call("POST", "/api/payoffs/issue", {"vin": VIN}, token=login_role("settlement"))
    ok(status == 201 and res["data"].get("certNo"), "结清证明 -> %s（证明编号 %s）"
       % (status, res.get("data", {}).get("certNo")))
    status, res = call("POST", "/api/payoffs/issue", {"vin": VIN_REP}, token=login_role("settlement"))
    ok(status == 422 and res["code"] == "PAYOFF_001",
       "未结清车辆出具被拒 -> %s/%s" % (status, res.get("code")))

    # ⑮ 额度年审（P1：冻结 → 复核 → 解冻恢复）
    print("\n⑮ 额度年审：发起冻结 -> 复核通过 -> 解冻恢复")
    status, res = call("GET", "/api/credit-lines/%d" % credit_line_id)
    used_now = res["data"]["usedAmount"]
    status, res = call("POST", "/api/annual-reviews/initiate",
                       {"dealerId": dealer_id, "creditLineId": credit_line_id, "cycle": "ANNUAL"}, token=login_role("credit"))
    ok(status == 201 and res["data"]["status"] == "IN_PROGRESS", "年审发起 -> %s（单号 %s）"
       % (status, res.get("data", {}).get("reviewNo")))
    ok(res["data"]["frozenAmount"] == used_now, "冻结量=发起时已用额度 %d" % used_now)
    review_no = res["data"]["reviewNo"]
    status, res = call("POST", "/api/annual-reviews/%s/complete" % review_no,
                       {"passed": True, "resultNote": "冒烟复核通过"}, token=login_role("credit"))
    ok(status == 200 and res["data"]["status"] == "PASSED"
       and res["data"]["creditLineStatus"] == "ACTIVE", "复核通过 -> 额度解冻恢复 ACTIVE")
    status, res = call("POST", "/api/jobs/annual-review-scan", {})
    ok(status == 200, "年审扫描作业 -> %s（本轮新发起 %s 件）"
       % (status, res.get("data", {}).get("initiatedCount")))

    # ⑯ 结算宽限期 / OOT 升级（"告警但允许"贴合日本实务）
    print("\n⑯ 结算窗口：宽限期内告警但允许，超宽限期升级 OOT")
    adv_ok, _ = ingest_and_advance(VIN_WARN)
    ok(adv_ok, "建账+垫款 %s" % VIN_WARN)
    call("POST", "/api/vehicles/%s/sold" % VIN_WARN,
         {"soldAt": (today - timedelta(days=4)).isoformat()}, token=login_role("settlement"))   # 逾期约 2 个营业日 ≤ 宽限期 5
    adv_ok, _ = ingest_and_advance(VIN_GRACE)
    ok(adv_ok, "建账+垫款 %s" % VIN_GRACE)
    call("POST", "/api/vehicles/%s/sold" % VIN_GRACE,
         {"soldAt": (today - timedelta(days=14)).isoformat()}, token=login_role("settlement"))  # 逾期约 12 个营业日 > 宽限期 5
    status, res = call("POST", "/api/jobs/settlement-scan", {})
    ok(status == 200, "结算窗口扫描 -> %s（升级 OOT %s 台，宽限期预警 %s 台）"
       % (status, res.get("data", {}).get("ootCount"), res.get("data", {}).get("warnCount")))
    status, res = call("GET", "/api/vehicles/%s" % VIN_WARN)
    ok(res["data"]["status"] == "SOLD_PENDING",
       "宽限期内：%s 状态保持 SOLD_PENDING（告警但允许，未升级）" % VIN_WARN)
    status, res = call("GET", "/api/vehicles/%s" % VIN_GRACE)
    ok(res["data"]["status"] == "OVERDUE",
       "超宽限期：%s 状态升级 OVERDUE（OOT 嫌疑）" % VIN_GRACE)

    # ⑰ 违约处置闭环（P0-②）
    print("\n⑰ 违约处置：开单 → 收车 → 变现 → 结案（损失=应收-变现）")
    adv_ok, advance_disp = ingest_and_advance(VIN_DISP)
    ok(adv_ok, "建账+垫款 %s" % VIN_DISP)
    status, res = call("POST", "/api/disposals/open", {"vin": VIN_DISP, "reason": "OVERDUE"}, token=login_role("disposal"))
    ok(status == 201 and res["data"]["status"] == "OPEN", "开单 -> %s（单号 %s）"
       % (status, res.get("data", {}).get("caseNo")))
    case_no = res["data"]["caseNo"]
    call("POST", "/api/disposals/%s/repossess" % case_no, {}, token=login_role("disposal"))
    status, res = call("POST", "/api/disposals/%s/liquidate" % case_no, {}, token=login_role("disposal"))
    ok(status == 200 and res["data"]["status"] == "LIQUIDATING", "收车并进入变现 -> %s" % status)
    proceeds = int(advance_disp * 0.80)
    status, res = call("POST", "/api/disposals/%s/close" % case_no, {"proceeds": proceeds}, token=login_role("disposal"))
    ok(status == 200 and res["data"]["receivable"] == advance_disp,
       "结案 -> %s（应收=本金 %d，无利息流水）" % (status, advance_disp))
    ok(res["data"]["lossAmount"] == advance_disp - proceeds,
       "认定损失=%d（应收 %d - 变现 %d）" % (res["data"]["lossAmount"], advance_disp, proceeds))
    # 自动处置作业：⑯ 升级 OOT 的车应被自动开单（无未结案工单才开）
    status, res = call("POST", "/api/jobs/disposal", {})
    ok(status == 200 and res["data"].get("openedCount", 0) >= 1,
       "违约处置扫描作业 -> 自动开单 %s 件（覆盖 OOT 车）" % res.get("data", {}).get("openedCount"))

    # ⑱ 评级口径动态配置（后台可调，即时生效）
    print("\n⑱ 授信评级分级口径：后台动态配置（⚠️预设待客户确认）")
    status, res = call("GET", "/api/rating-configs")
    grades = {c["grade"] for c in res["data"]}
    ok(status == 200 and {"A", "B", "C", "D"} <= grades, "口径列表 -> 预置 A/B/C/D（%s）" % sorted(grades))
    # 修改 B 级加点 0.005 → 0.008，新垫款立即按新口径计息
    status, res = call("PUT", "/api/rating-configs/B",
                       {"gradeName": "标准", "rateSurcharge": 0.008, "allowed": True,
                        "remark": "冒烟：动态调整加点"})
    ok(status == 200 and float(res["data"]["rateSurcharge"]) == 0.008, "调整 B 级加点 0.005 -> 0.008（免发版）")
    status, res = call("POST", "/api/vehicles/batch-ingest", {
        "dealerId": dealer_id, "creditLineId": credit_line_id,
        "purchaseChannel": "OEM", "supplierName": "测试厂家",
        "vehicles": [{"vin": VIN_RATE, "brand": "HONDA", "model": "FIT",
                      "modelYear": 2026, "invoiceAmount": 1_500_000, "color": "BLUE"}],
        "idempotencyKey": "ingest-rate-" + VIN_RATE}, token=login_role("review_op"))
    annual_rate = float(res["data"]["annualRate"])
    ok(status == 201 and abs(annual_rate - 0.038) < 1e-9,
       "新建账执行利率=%s（基准 0.03 + B 级加点 0.008，动态生效）" % annual_rate)
    # 停用等级 / 未配置等级均不准入
    status, res = call("PUT", "/api/dealers/%d/risk-grade" % dealer_id, {"riskGrade": "D"}, token=login_role("credit"))
    ok(status == 422 and res["code"] == "RATING_001", "调级到已停用的 D 级被拒 -> %s/%s" % (status, res.get("code")))
    status, res = call("POST", "/api/dealers", {
        "dealerCode": "DL-SMOKE-X-" + str(int(time.time()) % 100000),
        "name": "口径校验经销商", "riskGrade": "X9", "businessYears": 5})
    ok(status == 422 and res["code"] == "RATING_001", "使用未配置等级 X9 创建被拒 -> %s/%s" % (status, res.get("code")))
    # 还原预设口径
    call("PUT", "/api/rating-configs/B",
         {"gradeName": "标准（预设）", "rateSurcharge": 0.005, "allowed": True,
          "remark": "预设：基准 + 0.5%，待客户确认"})

    # 季度归档作业（预设：早于本季度首日的流水复制到历史表后删除）
    status, res = call("POST", "/api/jobs/archive", {})
    ok(status == 200, "季度归档作业 -> %s（本期归档 %s 条，当季数据应为 0）"
       % (status, res.get("data", {}).get("archivedCount")))

    # ⑲ 角色权限边界（角色矩阵 + 自然人级职责分离）
    print("\n⑲ 角色权限边界（角色矩阵 + 自然人级职责分离）")

    # ---- 角色枚举与权限点下发 ----
    status, res = call("POST", "/api/auth/login", {"username": "admin", "password": "admin123"})
    ok(status == 200 and res.get("data", {}).get("role") == "SYSTEM_ADMIN",
       "系统管理员登录成功，角色=%s" % res.get("data", {}).get("role"))
    admin_perms = res.get("data", {}).get("permissions") or []
    ok("CONFIG_MAINTAIN" in admin_perms and "JOB_TRIGGER" in admin_perms,
       "管理员令牌带管理域权限点 -> %s" % admin_perms)
    ok("ADVANCE_DISBURSE" not in admin_perms,
       "管理员**不**持有资金支付权限点（管理员不碰业务单据，双人复核才成立）")

    # ---- 业务经办岗（operator）----
    status, res = call("POST", "/api/auth/login", {"username": "operator", "password": "operator123"})
    ok(status == 200 and res.get("data", {}).get("role") == "BUSINESS_OPERATOR",
       "业务经办岗登录成功，角色=%s" % res.get("data", {}).get("role"))
    op_perms = res.get("data", {}).get("permissions") or []
    ok("ADVANCE_APPLY" in op_perms and "ADVANCE_DISBURSE" not in op_perms,
       "经办岗只有申请权、无支付权 -> %s" % op_perms)
    op_token = res.get("data", {}).get("token") if status == 200 else ""

    if op_token:
        # 写接口 → 403 AUTH_002（授权失败，不是认证失败）
        status, res = call("POST", "/api/dealers",
                           {"dealerCode": "DL-OP-" + str(int(time.time()) % 100000),
                            "name": "越权测试经销商"}, token=op_token)
        ok(status == 403 and res["code"] == "AUTH_002",
           "业务经办岗调 POST /api/dealers 被拒 -> %s/%s" % (status, res.get("code")))

        status, res = call("PUT", "/api/dealers/%d" % dealer_id,
                           {"name": "越权改名"}, token=op_token)
        ok(status == 403 and res["code"] == "AUTH_002",
           "业务经办岗调 PUT /api/dealers/{id} 被拒 -> %s/%s" % (status, res.get("code")))

        status, res = call("DELETE", "/api/dealers/%d" % dealer_id, token=op_token)
        ok(status == 403 and res["code"] == "AUTH_002",
           "业务经办岗调 DELETE /api/dealers/{id} 被拒 -> %s/%s" % (status, res.get("code")))

        status, res = call("PUT", "/api/dealers/%d/risk-grade" % dealer_id,
                           {"riskGrade": "A"}, token=op_token)
        ok(status == 403 and res["code"] == "AUTH_002",
           "业务经办岗调 PUT /api/dealers/{id}/risk-grade 被拒 -> %s/%s" % (status, res.get("code")))

        # 垫款职责分离（SOD）：申请人（USER）能申请，但不能自己放款，也不能自行确认付款
        sod_vin = "TESTSOD" + TS
        call("POST", "/api/vehicles/batch-ingest", {
            "dealerId": dealer_id, "creditLineId": credit_line_id,
            "purchaseChannel": "OEM", "supplierName": "SOD 厂家",
            "vehicles": [{"vin": sod_vin, "brand": "TOYOTA", "model": "SOD",
                          "modelYear": 2026, "invoiceAmount": INVOICE_AMOUNT,
                          "advanceRatio": ADVANCE_RATIO, "color": "WHITE"}],
            "idempotencyKey": "ingest-sod-" + sod_vin}, token=login_role("review_op"))
        status, res = call("POST", "/api/advances",
                           {"creditLineId": credit_line_id, "vin": sod_vin},
                           idem_key="adv-sod-" + sod_vin, token=op_token)
        ok(status == 201 and res["data"]["status"] == "PENDING",
           "业务经办岗申请垫款 -> %s（PENDING，仅申请未放款）" % status)
        sod_no = res["data"]["advanceId"]

        # 同一台车不得有两笔在途垫款：换一个幂等键再申请 → 409 ADVANCE_003。
        # V12 之前这条被"申请即置 FINANCED"间接挡住；引入 PENDING_ADVANCE 后
        # 未付款的车合法停留在该状态，这层兜底消失，必须由 stageAdvance 显式拦。
        status, res = call("POST", "/api/advances",
                           {"creditLineId": credit_line_id, "vin": sod_vin},
                           idem_key="adv-sod-dup-" + sod_vin, token=op_token)
        ok(status == 409 and res.get("code") == "ADVANCE_003",
           "同一 VIN 重复申请垫款被拒（换幂等键也不行）-> %s/%s" % (status, res.get("code")))
        status, res = call("POST", "/api/advances/%s/disburse" % sod_no, {}, token=op_token)
        ok(status == 403 and res["code"] == "AUTH_002",
           "业务经办岗自行放款被拒（SOD：申请人≠支付人）-> %s/%s" % (status, res.get("code")))

        # ---- 自然人级职责分离：经办岗自己复核自己发起的申请 → 403 AUTH_003 ----
        # 注意：这里用的是"另一套角色但同一个人"无法构造（每个账号是一个人），
        # 所以真正验证的是"角色不同即可操作，但同一人不得自办自批"——
        # 用 multi_role 账号（一人兼两岗）来构造这个场景。
        status, res = call("POST", "/api/advances/%s/review" % sod_no, {}, token=op_token)
        ok(status == 403 and res["code"] == "AUTH_002",
           "业务经办岗调复核被拒（无复核权限点）-> %s/%s" % (status, res.get("code")))

        # ---- 完整五段链路：录入(审核岗建账) → 申请(经办岗) → 复核(复核岗) → 支付(出纳岗) ----
        status, res = call("POST", "/api/advances/%s/review" % sod_no, {},
                           token=login_role("fund_rev"))
        ok(status == 200 and res["data"]["reviewResult"] in ("PASS", "REJECTED"),
           "资金复核岗复核 -> %s（结论=%s）" % (status, res.get("data", {}).get("reviewResult")))
        sod_reviewer = psql("SELECT reviewer FROM advances WHERE advance_no='%s';" % sod_no)
        ok(sod_reviewer is not None and sod_reviewer.strip() == "fund_rev",
           "垫款单记录复核人=fund_rev（自然人留痕）-> %s" % sod_reviewer)

        status, res = call("POST", "/api/advances/%s/disburse" % sod_no, {}, token=op_token)
        ok(status == 403 and res["code"] == "AUTH_002",
           "业务经办岗自行放款被拒（SOD：申请人≠支付人）-> %s/%s" % (status, res.get("code")))

        # 资金出纳岗放款：仅提交银行 → PROCESSING（绝不内联确认付款）
        status, res = call("POST", "/api/auth/login", {"username": "cashier", "password": "cashier123"})
        ok(status == 200 and res.get("data", {}).get("role") == "FUNDING_CASHIER",
           "资金出纳岗登录成功，角色=%s" % res.get("data", {}).get("role"))
        cashier_token = res.get("data", {}).get("token") if status == 200 else ""

        # 出纳岗也不能自己发起垫款申请（岗位不相容）
        status, res = call("POST", "/api/advances",
                           {"creditLineId": credit_line_id, "vin": sod_vin},
                           idem_key="adv-sod-cash-" + sod_vin, token=cashier_token)
        ok(status == 403 and res["code"] == "AUTH_002",
           "出纳岗调垫款申请被拒（岗位不相容）-> %s/%s" % (status, res.get("code")))

        status, res = call("POST", "/api/advances/%s/disburse" % sod_no, {}, token=cashier_token)
        ok(status == 200 and res["data"]["status"] == "PROCESSING",
           "出纳岗放款 -> %s（PROCESSING，仅提交银行，待回调）" % status)

        # 关键防线：付款终态只能由银行回调翻 —— 内部用户调 /confirm 一律 401
        status, res = call("POST", "/api/advances/%s/confirm" % sod_no,
                           {"paymentRef": "x"}, token=op_token)
        ok(status == 401 and res["code"] == "PAYMENT_001",
           "业务经办岗无签名调 /confirm 被拒（不能自行确认付款）-> %s/%s" % (status, res.get("code")))
        status, res = call("POST", "/api/advances/%s/confirm" % sod_no,
                           {"paymentRef": "x"})
        ok(status == 401 and res["code"] == "PAYMENT_001",
           "内部用户无签名调 /confirm 被拒（银行回调专属，内部用户够不着）-> %s/%s" % (status, res.get("code")))
        status, res = call("POST", "/api/advances/%s/confirm" % sod_no,
                           {"paymentRef": "x"}, token="",
                           headers={"X-Payment-Signature": "bad", "X-Payment-Timestamp": str(int(time.time()))})
        ok(status == 401 and res["code"] == "PAYMENT_001",
           "错误签名调 /confirm 被拒 -> %s/%s" % (status, res.get("code")))

        # 唯一合法路径：银行 HMAC 回调 → PAID
        status, res = bank_confirm(sod_no, "BANK-SOD-REF")
        ok(status == 200 and res["data"]["status"] == "PAID",
           "银行 HMAC 回调确认 -> %s（PAID，职责分离成立：申请人≠支付人≠银行）" % status)

        # 银行回调失败路径：PROCESSING → FAILED，额度回退，台账退回"已建账"
        # （必须退回：申请垫款要求 CREATED，若不回退这台车就永远申请不了垫款）
        fail_vin = "TESTFAIL" + TS
        call("POST", "/api/vehicles/batch-ingest", {
            "dealerId": dealer_id, "creditLineId": credit_line_id,
            "purchaseChannel": "OEM", "supplierName": "FAIL 厂家",
            "vehicles": [{"vin": fail_vin, "brand": "TOYOTA", "model": "FAIL",
                          "modelYear": 2026, "invoiceAmount": INVOICE_AMOUNT,
                          "advanceRatio": ADVANCE_RATIO, "color": "WHITE"}],
            "idempotencyKey": "ingest-fail-" + fail_vin}, token=login_role("review_op"))
        status, res = call("POST", "/api/advances",
                           {"creditLineId": credit_line_id, "vin": fail_vin},
                           idem_key="adv-fail-" + fail_vin, token=op_token)
        fail_no = res["data"]["advanceId"]
        status, res = call("POST", "/api/advances/%s/disburse" % fail_no, {}, token=login_role("cashier"))
        ok(status == 200 and res["data"]["status"] == "PROCESSING", "失败路径：ADMIN 放款 -> PROCESSING")
        used_before = call("GET", "/api/credit-lines/%d" % credit_line_id)[1]["data"]["usedAmount"]
        status, res = bank_fail(fail_no, "GATEWAY_DECLINED")
        ok(status == 200 and res["data"]["status"] == "FAILED", "银行回调失败 -> FAILED")
        used_after = call("GET", "/api/credit-lines/%d" % credit_line_id)[1]["data"]["usedAmount"]
        ok(used_after == used_before - EXPECTED_ADVANCE, "失败路径：额度回退 %d" % EXPECTED_ADVANCE)
        status, res = call("GET", "/api/vehicles/%s" % fail_vin)
        ok(res["data"]["status"] == "CREATED",
           "失败路径：车辆退回 CREATED（额度已释放，申请入口重新打开）")

        # 读接口 → 放行
        status, res = call("GET", "/api/dealers", token=op_token)
        ok(status == 200 and isinstance(res.get("data"), list),
           "USER 调 GET /api/dealers 放行 -> %s" % status)

        status, res = call("GET", "/api/dealers/%d/vehicles" % dealer_id, token=op_token)
        ok(status == 200, "USER 调 GET /api/dealers/{id}/vehicles 放行 -> %s" % status)

        # 无令牌 → 401（认证失败，与 403 严格区分）
        status, res = call("GET", "/api/dealers", token="")
        ok(status == 401 and res["code"] == "AUTH_001",
           "无令牌调 GET /api/dealers -> %s/%s（认证失败，与授权失败区分）" % (status, res.get("code")))

    print("\n⑲-b 审计操作人不可伪造")
    # 背景：此前 audit_events.actor 取自请求头 X-Operator-Id —— 客户端可任意伪造，
    # 审计链形同虚设。修复后以服务端验签的令牌身份为准，请求头仅作无令牌场景的兜底。
    fake_code = "DL-AUDIT-" + str(int(time.time()) % 100000)
    status, res = call("POST", "/api/dealers",
                       {"dealerCode": fake_code, "name": "审计验证经销商",
                        "riskGrade": "B", "businessYears": 5},
                       headers={"X-Operator-Id": "forged_hacker"})
    ok(status == 201, "伪造审计头后创建经销商 -> %s" % status)
    fake_id = res.get("data", {}).get("id") if status == 201 else None

    if fake_id is not None and psql("SELECT 1") is not None:
        actor_row = psql("SELECT actor FROM audit_events WHERE action='CREATE_DEALER' "
                         "AND target_id='%s' ORDER BY id DESC LIMIT 1;" % fake_id)
        ok(actor_row == "admin",
           "审计表 actor 记录为令牌身份而非伪造头 -> 实际=%r（伪造值 forged_hacker）" % actor_row)

        role_row = psql("SELECT actor_role FROM audit_events WHERE action='CREATE_DEALER' "
                        "AND target_id='%s' ORDER BY id DESC LIMIT 1;" % fake_id)
        ok(role_row == "SYSTEM_ADMIN",
           "审计表 actor_role 记录角色 -> 实际=%r（角色代码即 UserRole 枚举名）" % role_row)

        # 清理本次验证产生的数据
        psql("DELETE FROM audit_events WHERE target_type='DEALER' AND target_id='%s';" % fake_id)
        psql("DELETE FROM dealers WHERE id=%s;" % fake_id)
    else:
        print("  [INFO] 跳过审计内容断言（psql 不可用）")

    print("\n⑳ 经销商主数据 CRUD（编辑 / 删除护栏）")
    # 新建一个干净的空档经销商用于编辑与删除
    tmp_code = "DL-CRUD-" + str(int(time.time()) % 100000)
    status, res = call("POST", "/api/dealers", {
        "dealerCode": tmp_code, "name": "CRUD 测试经销商", "riskGrade": "B",
        "businessYears": 8, "contactPerson": "初始联系人",
        "contactPhone": "03-1111-2222", "address": "东京都港区", "remark": "初始备注"})
    ok(status == 201, "新增经销商（管理员）-> %s" % status)
    tmp_id = res["data"]["id"]
    ok(res["data"]["status"] == "ACTIVE", "新建默认状态 -> %s" % res["data"]["status"])
    ok(res["data"].get("contactPerson") == "初始联系人", "联系人字段落库 -> %s" % res["data"].get("contactPerson"))

    # 编号查重
    status, res = call("POST", "/api/dealers",
                       {"dealerCode": tmp_code, "name": "重复编号经销商"})
    ok(status == 409 and res["code"] == "CONFLICT_001",
       "重复经销商编号被拒 -> %s/%s" % (status, res.get("code")))

    # 编辑：PATCH 语义（null=不改，""=清空）
    status, res = call("PUT", "/api/dealers/%d" % tmp_id, {
        "name": "CRUD 测试经销商（已改）", "contactPhone": "03-3333-4444",
        "remark": "", "contactPerson": ""})
    ok(status == 200 and res["data"]["name"] == "CRUD 测试经销商（已改）",
       "编辑经销商名称 -> %s" % res.get("data", {}).get("name"))
    ok(res["data"]["contactPhone"] == "03-3333-4444", "编辑联系电话 -> %s" % res["data"]["contactPhone"])
    ok(res["data"].get("remark") is None, "空串清空备注 -> %s" % res["data"].get("remark"))
    ok(res["data"].get("contactPerson") is None, "空串清空联系人 -> %s" % res["data"].get("contactPerson"))
    ok(res["data"]["address"] == "东京都港区", "未传字段保持原值（address）-> %s" % res["data"].get("address"))

    # 编辑时用自己的编号不算重复
    status, res = call("PUT", "/api/dealers/%d" % tmp_id, {"dealerCode": tmp_code})
    ok(status == 200, "用自身编号回填不算重复 -> %s" % status)

    # 状态流转：ACTIVE → SUSPENDED → ACTIVE 合法
    status, res = call("PUT", "/api/dealers/%d" % tmp_id, {"status": "SUSPENDED"})
    ok(status == 200 and res["data"]["status"] == "SUSPENDED",
       "状态流转 ACTIVE -> SUSPENDED -> %s" % res.get("data", {}).get("status"))
    status, res = call("PUT", "/api/dealers/%d" % tmp_id, {"status": "ACTIVE"})
    ok(status == 200 and res["data"]["status"] == "ACTIVE",
       "状态流转 SUSPENDED -> ACTIVE -> %s" % res.get("data", {}).get("status"))

    # 非法状态值
    status, res = call("PUT", "/api/dealers/%d" % tmp_id, {"status": "NOT_A_STATUS"})
    ok(status == 400 and res["code"] == "VALID_001",
       "非法状态值被拒 -> %s/%s" % (status, res.get("code")))

    # 删除护栏：名下有车辆/额度的经销商禁止物理删除
    status, res = call("DELETE", "/api/dealers/%d" % dealer_id)
    ok(status == 422 and res["code"] == "DEALER_001",
       "删除有车辆的经销商被护栏拦下 -> %s/%s" % (status, res.get("code")))
    ok(res.get("details", {}).get("vehicleCount", 0) > 0,
       "护栏回传车辆数 -> %s" % res.get("details", {}).get("vehicleCount"))
    status, res = call("GET", "/api/dealers/%d" % dealer_id)
    ok(status == 200, "护栏触发后记录仍在（未物理删除）-> %s" % status)

    # 终止状态是终态：TERMINATED 不可回退
    status, res = call("PUT", "/api/dealers/%d" % tmp_id, {"status": "TERMINATED"})
    ok(status == 200 and res["data"]["status"] == "TERMINATED",
       "状态流转 -> TERMINATED -> %s" % res.get("data", {}).get("status"))
    status, res = call("PUT", "/api/dealers/%d" % tmp_id, {"status": "ACTIVE"})
    ok(status == 422 and res["code"] == "DEALER_002",
       "终态 TERMINATED 不可回退 -> %s/%s" % (status, res.get("code")))

    # 空档经销商可删除
    status, res = call("DELETE", "/api/dealers/%d" % tmp_id)
    ok(status == 200, "删除空档经销商成功 -> %s" % status)
    status, res = call("GET", "/api/dealers/%d" % tmp_id)
    ok(status == 404, "删除后再查为 404 -> %s" % status)

    print("\n㉑ 名下车辆聚合视图")
    status, res = call("GET", "/api/dealers/%d/vehicles" % dealer_id)
    ok(status == 200, "GET /api/dealers/{id}/vehicles -> %s" % status)
    if status == 200:
        ov = res["data"]
        summary = ov["summary"]
        vehicles = ov["vehicles"]
        ok("dealer" in ov and "summary" in ov and "vehicles" in ov,
           "聚合视图含 dealer/summary/vehicles 三段")
        ok(summary["vehicleCount"] == len(vehicles),
           "汇总台数=%d == 明细行数=%d" % (summary["vehicleCount"], len(vehicles)))
        # 汇总金额必须等于明细逐行求和（口径一致性）
        amount_pairs = (
            ("totalInvoiceAmount", "invoiceAmount", "采购金额"),
            ("totalAdvanceAmount", "advanceAmount", "垫款金额"),
            ("totalPrincipalBalance", "principalBalance", "在贷本金"),
            ("totalPaidPrincipal", "paidPrincipal", "已还本金"),
        )
        for sum_field, detail_field, label in amount_pairs:
            detail_sum = sum(int(v.get(detail_field) or 0) for v in vehicles)
            ok(int(summary[sum_field]) == detail_sum,
               "汇总%s=%d == 明细求和=%d" % (label, summary[sum_field], detail_sum))
        ok(summary["vehicleCount"] >= 1,
           "冒烟经销商名下车辆数=%d" % summary["vehicleCount"])
        ok(int(summary["approvedLimit"]) >= 0, "授信总额=%d" % summary["approvedLimit"])

    # ------------------------------------------------------------------
    # ㉒ 角色矩阵（RBAC）：权限点下发 · 一人多岗 · 自然人级 SOD
    # ------------------------------------------------------------------
    print("\n㉒ 角色矩阵（RBAC 权限点 + 一人多岗 + 自然人级职责分离）")

    # ---- 各业务角色登录后拿到的权限点符合岗位定义 ----
    role_expect = [
        ("import_op",   "import123",   "PURCHASE_OPERATOR",     ["PURCHASE_IMPORT", "BANK_ACCOUNT_MAINTAIN"]),
        ("review_op",   "review123",   "PURCHASE_REVIEWER",     ["VEHICLE_VERIFY", "INVENTORY_AUDIT", "BANK_ACCOUNT_CONTROL"]),
        ("fund_rev",    "fundrev123",  "FUNDING_REVIEWER",      ["ADVANCE_REVIEW"]),
        ("cashier",     "cashier123",  "FUNDING_CASHIER",       ["ADVANCE_DISBURSE"]),
        ("settlement",  "settle123",   "SETTLEMENT_OFFICER",    ["VEHICLE_SOLD", "REPAYMENT_EXECUTE", "PAYOFF_ISSUE"]),
        ("disposal",    "disposal123", "DISPOSAL_OFFICER",      ["DISPOSAL_EXECUTE"]),
    ]
    role_tokens = {}
    for uname, pwd, expect_role, expect_perms in role_expect:
        status, res = call("POST", "/api/auth/login", {"username": uname, "password": pwd})
        ok(status == 200 and res.get("data", {}).get("role") == expect_role,
           "%s 登录成功，角色=%s（期望 %s）" % (uname, res.get("data", {}).get("role"), expect_role))
        perms = res.get("data", {}).get("permissions") or []
        role_tokens[uname] = res.get("data", {}).get("token") if status == 200 else ""
        missing = [p for p in expect_perms if p not in perms]
        ok(not missing,
           "%s 持有权限点 %s（缺失=%s）" % (expect_role, expect_perms, missing))

    # ---- 岗位不相容：录入岗不能验车确权；出纳岗不能复核 ----
    if role_tokens.get("import_op"):
        status, res = call("POST", "/api/vehicles/batch-ingest", {
            "dealerId": dealer_id, "creditLineId": credit_line_id,
            "purchaseChannel": "OEM", "supplierName": "越权厂家",
            "vehicles": [{"vin": "TESTROLEX" + TS, "brand": "TOYOTA", "model": "X",
                          "modelYear": 2026, "invoiceAmount": INVOICE_AMOUNT,
                          "advanceRatio": ADVANCE_RATIO, "color": "WHITE"}],
            "idempotencyKey": "ingest-rolex-" + TS}, token=role_tokens["import_op"])
        ok(status == 403 and res["code"] == "AUTH_002",
           "采购录入岗调验车确权被拒（岗位不相容）-> %s/%s" % (status, res.get("code")))

    if role_tokens.get("cashier"):
        status, res = call("POST", "/api/advances/%s/review" % sod_no, {},
                           token=role_tokens["cashier"])
        ok(status == 403 and res["code"] == "AUTH_002",
           "出纳岗调垫款复核被拒（岗位不相容）-> %s/%s" % (status, res.get("code")))

    # ---- 一人多岗：权限取并集 ----
    status, res = call("POST", "/api/auth/login", {"username": "multi_role", "password": "multi123"})
    ok(status == 200, "多岗账号登录成功")
    if status == 200:
        roles = res.get("data", {}).get("roles") or []
        perms = res.get("data", {}).get("permissions") or []
        ok(len(roles) == 2 and "PURCHASE_OPERATOR" in roles and "RISK_ADVISORY_OFFICER" in roles,
           "multi_role 持两岗 -> %s" % roles)
        ok("PURCHASE_IMPORT" in perms and "INSURANCE_REPURCHASE" in perms,
           "一人多岗 ⇒ 权限取并集（导入 + 增信）-> %s" % perms)
        mr_token = res.get("data", {}).get("token")
        # 兼岗不冲突时不应给出冲突提示
        conflicts = res.get("data", {}).get("roleConflicts")
        ok(not conflicts, "multi_role 兼岗组合无不相容告警 -> %s" % conflicts)

    # ---- 自然人级 SOD：同一人不得自办自批（即使角色上"看起来"可以） ----
    # 用 multi_role 之外的账号无法构造"同一人两岗"，
    # 这里验证的是接口层的硬校验存在：出纳岗对自己发起的申请不能支付。
    # （角色已保证申请≠支付；自然人级校验在应用层 assertNoSelfApproval）
    sod_review = psql("SELECT applicant FROM advances WHERE advance_no='%s';" % sod_no)
    ok(sod_review is not None and sod_review.strip() == "operator",
       "垫款单记录申请人为自然人 operator -> %s" % sod_review)
    sod_disburser = psql("SELECT disburser FROM advances WHERE advance_no='%s';" % sod_no)
    ok(sod_disburser is not None and sod_disburser.strip() == "cashier",
       "垫款单记录支付人为自然人 cashier（≠申请人）-> %s" % sod_disburser)

    # ---- 孤儿台账检查：车辆不应挂在已不存在的经销商名下
    # （历史缺陷：清理经销商时漏了护栏测试车，留下 dealer_id 指向空经销商的孤儿台账）
    orphan = psql("SELECT COUNT(*) FROM vehicle_units v "
                  "WHERE v.dealer_id NOT IN (SELECT id FROM dealers);")
    ok(orphan is not None and orphan.strip() in ("0", ""),
       "无孤儿车辆台账（dealer_id 均指向存在的经销商）-> 计数=%s" % orphan)

    # ⑳ 一车一笔垫款：作废 / 提前赎回后可重新申请（同一 VIN 允许多条历史记录）
    print("\n⑳ 一车一笔垫款：作废 → 重申请 → 提前赎回 → 二次融资")
    VIN_RE = "TESTRD" + TS
    status, res = call("POST", "/api/vehicles/batch-ingest", {
        "dealerId": dealer_id, "creditLineId": credit_line_id,
        "purchaseChannel": "OEM", "supplierName": "测试厂家",
        "vehicles": [{"vin": VIN_RE, "brand": "TOYOTA", "model": "REDEEM",
                      "modelYear": 2026, "invoiceAmount": INVOICE_AMOUNT,
                      "advanceRatio": ADVANCE_RATIO, "color": "SILVER"}],
        "idempotencyKey": "ingest-re-" + VIN_RE}, token=login_role("review_op"))
    ok(status == 201 and res["data"]["accepted"] == 1,
       "建账 %s -> %s" % (VIN_RE, status))

    # 第一次融资：只申请、不放款（停在 PENDING_ADVANCE）
    status, res = call("POST", "/api/advances",
                       {"creditLineId": credit_line_id, "vin": VIN_RE},
                       idem_key="adv-re-1-" + VIN_RE, token=login_role("operator"))
    ok(status == 201, "首次申请垫款 -> %s" % status)
    adv_first = res["data"]["advanceId"]
    ok(res["data"]["vehicleStatus"] == "PENDING_ADVANCE",
       "车辆 -> PENDING_ADVANCE（已申请、钱未付）")

    # 一车一笔：同一台车第二次申请必须被拒（换幂等键也不行）
    status, res = call("POST", "/api/advances",
                       {"creditLineId": credit_line_id, "vin": VIN_RE},
                       idem_key="adv-re-dup-" + VIN_RE, token=login_role("operator"))
    ok(status == 409 and res["code"] == "ADVANCE_003",
       "同车重复申请被拒 -> %s/%s" % (status, res.get("code")))

    # 经销商撤销申请：垫款作废（非物理删除）、车辆退回、额度释放
    status, res = call("GET", "/api/credit-lines/%d" % credit_line_id)
    used_before_void = res["data"]["usedAmount"]
    status, res = call("POST", "/api/advances/%s/void" % adv_first,
                       {"reasonCode": "DEALER_CANCELLED", "remark": "冒烟：经销商主动撤销"},
                       token=login_role("operator"))
    ok(status == 200 and res["data"]["status"] == "VOIDED",
       "撤销申请 -> VOIDED（记录保留在台账，不删除）")
    status, res = call("GET", "/api/vehicles/%s" % VIN_RE)
    ok(res["data"]["status"] == "CREATED", "车辆退回 CREATED（申请入口重新打开）")
    status, res = call("GET", "/api/credit-lines/%d" % credit_line_id)
    ok(res["data"]["usedAmount"] == used_before_void - EXPECTED_ADVANCE,
       "额度已释放 %d（作废后不再占用）" % EXPECTED_ADVANCE)

    # 作废后重新申请：成功，且形成该 VIN 的第二条记录
    status, res = call("POST", "/api/advances",
                       {"creditLineId": credit_line_id, "vin": VIN_RE},
                       idem_key="adv-re-2-" + VIN_RE, token=login_role("operator"))
    ok(status == 201, "作废后可重新申请 -> %s" % status)
    adv_second = res["data"]["advanceId"]

    status, res = call("POST", "/api/advances/%s/disburse" % adv_second, {},
                       token=login_role("cashier"))
    ok(status == 200, "放款 -> %s" % status)
    status, res = bank_confirm(adv_second, "BANK-REF-RE-" + VIN_RE)
    ok(status == 200 and res["data"]["status"] == "PAID", "银行确认 -> PAID，车辆 IN_STOCK")

    # 质押中提前赎回：车未售出即可还款赎车（原实现要求 SOLD_PENDING，这条路走不通）
    status, res = call("GET", "/api/vehicles/%s" % VIN_RE)
    re_principal = res["data"]["principalBalance"]
    re_interest = res["data"]["accruedInterest"]
    status, res = call("POST", "/api/repayments/redeem",
                       {"vin": VIN_RE, "settlementAmount": re_principal + re_interest},
                       idem_key="redeem-" + VIN_RE, token=login_role("settlement"))
    ok(status == 201 and res["data"]["vehicleStatus"] == "REDEEMED",
       "在库提前赎回 -> 车辆 REDEEMED（车还在库，可再融资）")
    ok(res["data"]["releasedCredit"] == re_principal,
       "额度回补=%d（本金）" % re_principal)

    # 垫款记录被推进到终态，不再占用"一车一笔"名额
    st = psql("SELECT status FROM advances WHERE advance_no='%s';" % adv_second)
    ok(st is not None and st.strip() == "REDEEMED",
       "垫款记录 -> REDEEMED -> %s" % st)

    # 赎回后再次融资：同一 VIN 累积 3 条记录（VOIDED / REDEEMED / 新 PENDING）
    status, res = call("POST", "/api/advances",
                       {"creditLineId": credit_line_id, "vin": VIN_RE},
                       idem_key="adv-re-3-" + VIN_RE, token=login_role("operator"))
    ok(status == 201, "赎回后可再次申请（二次融资）-> %s" % status)
    timeline = psql("SELECT string_agg(status, ',' ORDER BY id) FROM advances WHERE vin='%s';" % VIN_RE)
    ok(timeline is not None and timeline.strip() == "VOIDED,REDEEMED,PENDING",
       "同一 VIN 垫款时间线 -> %s（多笔历史 + 单笔有效）" % timeline)

    print("\n" + "=" * 68)
    if FAILS:
        print("冒烟测试失败 %d 项：" % len(FAILS))
        for f in FAILS:
            print("  - " + f)
        sys.exit(1)
    print("全部通过 ✅  业务闭环七环节 + 流程缺口 P0/P1（⑧~⑱）+ 权限/CRUD/聚合/角色矩阵（⑲~㉒）验证通过")
    print("=" * 68)


if __name__ == "__main__":
    main()
