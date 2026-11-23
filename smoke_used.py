#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
二手车库存垫资（USED）· 端到端冒烟
跑法（服务已在跑，默认打 core 单体 8080）：
    PYTHON=python PYTHONIOENCODING=utf-8 python smoke_used.py
走网关验切流：
    SMOKE_BASE=http://127.0.0.1:18080 PYTHON=python PYTHONIOENCODING=utf-8 python smoke_used.py

分段（与 docs/二手车库存垫资改造-06 的 R 编号对齐，随切片推进追加）：
  U-R1 车商资质准入：四道闸门 G-A1〜G-A4 的判定顺序、二人签认红线、幂等与换发降级
  U-R2 车辆主数据：分流键必填、里程按来源可信序（禁止静默覆盖）、阈值读库、
       一车一证 + 跨车重号 + 二人换发成链、修復歴取严、票先于建账
  后续切片（U-R3 估值分档、U-R4 落札、U-R5 放款闸门、U-R6 WMS 账实、
  U-R7 放车放证、U-R8 回款回补）在此文件里按同样的 ok() 断言追加。

为什么单开一个文件而不是改 smoke-test.py：
  USED 的数据链与 LEGACY 七环节互不相干（不同经销商、不同 VIN 前缀、不同表），
  混在一个 main() 里任何一段前置失败都会连带后面的断言全红，定位成本比省下的一个文件高。
  收口时把两个文件的 ok() 计数并起来判 EXIT，不要求物理合成一个脚本。

编码提醒：本文件含中文与 → 等字符，输出重定向到文件时必须带 PYTHONIOENCODING=utf-8，
否则 Windows 上 stdout 回落 cp936 会在打印那一行崩栈（AGENTS.md §5 的实测坑）。
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import date, timedelta

BASE = os.environ.get("SMOKE_BASE", "http://localhost:8080")
FAILS = []
TS = str(int(time.time()))

# 岗位账号与 smoke-test.py 同一套（登录名 → 密码, 期望角色）；USED 只用得到的那几个
ROLE_ACCOUNTS = {
    "admin":       ("admin123",      "SYSTEM_ADMIN"),        # 持 DEALER_* 全套（含 DEALER_COMPLIANCE_CHECK）
    "credit":      ("credit123",     "CREDIT_REVIEWER"),     # 持资质维护，但刻意不持合规核查
    "operator":    ("operator123",   "BUSINESS_OPERATOR"),   # 既无资质写权也无读权之外的部分
    "review_op":   ("review123",     "PURCHASE_REVIEWER"),   # USED 只读：有 READ 无 MAINTAIN（P-9 录入/审核分岗）
    "post_lending": ("postlend123",  "POST_LENDING_MANAGER"),  # 持 CERTIFICATE_REISSUE_APPROVE（审批位 ≠ 执行位）
    "collateral":  ("collateral123", "COLLATERAL_OFFICER"),  # 持 CERTIFICATE_RECORD（收证执行位）
}
TOK = {}


def call(method, path, body=None, token="", idem_key=None):
    """发请求。token 为空串表示故意不带 Authorization 头（用来验 401）。"""
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
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"raw": raw}


def login(username):
    if username in TOK:
        return TOK[username]
    pwd, _ = ROLE_ACCOUNTS[username]
    st, res = call("POST", "/api/auth/login", {"username": username, "password": pwd})
    assert st == 200 and res.get("data", {}).get("token"), \
        "预检失败：%s 登录未通过 → %s" % (username, json.dumps(res, ensure_ascii=False))
    TOK[username] = res["data"]["token"]
    return TOK[username]


def ok(cond, msg):
    print(("  [PASS] " if cond else "  [FAIL] ") + msg)
    if not cond:
        FAILS.append(msg)


def psql(sql):
    """查库（断言"落库了没有"，不看 HTTP 码）。本机无 psql 时走容器。"""
    env = dict(os.environ, PGPASSWORD="floorplan")
    for argv in (["psql", "-h", "localhost", "-p", "5434", "-U", "floorplan", "-d", "floorplan", "-tAc", sql],
                 ["docker", "exec", "floorplan-db2", "psql", "-p", "5434", "-U", "floorplan",
                  "-d", "floorplan", "-tAc", sql]):
        try:
            r = subprocess.run(argv, env=env, capture_output=True, timeout=30)
            if r.returncode == 0:
                return r.stdout.decode("utf-8").strip()
        except Exception:
            pass
    return None


def r1():
    """U-R1 车商资质：判定顺序 = 许可 → 合规 → 会员 → 临期（D03 §2.1）。"""
    print("\n" + "=" * 68)
    print("U-R1 车商资质准入（G-A1 许可 / G-A3 合规 / G-A2 会员 / G-A4 双人评分）")
    print("=" * 68)
    admin = login("admin")
    today = date.today()
    d = lambda n: (today + timedelta(days=n)).isoformat()

    # ---- 建一个 USED 专用经销商（与 LEGACY 冒烟的 DL-SMOKE-* 分开，互不干扰）----
    st, res = call("POST", "/api/dealers", {
        "dealerCode": "DL-USED-" + TS, "name": "USED冒烟车商", "riskGrade": "B", "businessYears": 8},
        token=admin)
    ok(st == 201, "创建 USED 车商 -> %s" % st)
    if st != 201:
        return
    did = res["data"]["id"]
    API = "/api/dealer-qualifications/%d" % did

    # ---- 认证与授权 ----
    st, res = call("GET", API + "/admission")
    ok(st == 401 and res.get("code") == "AUTH_001", "无令牌读准入 → 401 AUTH_001（实际 %s/%s）" % (st, res.get("code")))
    st, res = call("POST", API + "/compliance-checks", {
        "checkType": "AML", "checkedOn": d(0), "validUntil": d(180), "result": "PASS",
        "source": "MANUAL", "requestNo": "R-" + TS, "coSignedBy": "reviewer2"}, token=login("operator"))
    ok(st == 403 and res.get("code") == "AUTH_002",
       "业务经办岗录合规核查 → 403 AUTH_002（实际 %s/%s）" % (st, res.get("code")))
    st, res = call("POST", API + "/compliance-checks", {
        "checkType": "AML", "checkedOn": d(0), "validUntil": d(180), "result": "PASS",
        "source": "MANUAL", "requestNo": "R2-" + TS, "coSignedBy": "reviewer2"}, token=login("credit"))
    ok(st == 403 and res.get("code") == "AUTH_002",
       "授信审查岗（无 DEALER_COMPLIANCE_CHECK）录合规 → 403（实际 %s/%s）" % (st, res.get("code")))

    # ---- 闸门 0：什么档案都没有 ----
    st, res = call("GET", API + "/admission", token=admin)
    a = res.get("data", {})
    ok(st == 200 and a.get("admissionState") == "BLOCKED_LICENSE",
       "裸车商准入 = BLOCKED_LICENSE -> %s" % a.get("admissionState"))
    ok(a.get("blocked") is True and a.get("errorCode") == "QUAL_001",
       "阻断且错误码 QUAL_001 -> blocked=%s code=%s" % (a.get("blocked"), a.get("errorCode")))

    # ---- G-A1：过期许可不算证 ----
    st, res = call("POST", API + "/licenses", {
        "licenseNo": "OLD-" + TS, "competentAuthority": "東京都公安委員会",
        "issuedOn": d(-2000), "expiresOn": d(-10),
        "evidencePath": "oss://lic/old.pdf", "evidenceDigest": "sha256:old"}, token=admin)
    ok(st == 201, "登记一张已过期许可 -> %s" % st)
    st, res = call("GET", API + "/admission", token=admin)
    ok(res["data"].get("admissionState") == "BLOCKED_LICENSE",
       "过期许可仍 = BLOCKED_LICENSE（expires_on 用 >= 判，末日当天有效、过了一天就拦）")

    # ---- G-A1：有效许可 ----
    st, res = call("POST", API + "/licenses", {
        "licenseNo": "LIC-" + TS, "competentAuthority": "東京都公安委員会",
        "issuedOn": d(-30), "expiresOn": d(5 * 365),
        "evidencePath": "oss://lic/new.pdf", "evidenceDigest": "sha256:new"}, token=admin)
    ok(st == 201, "登记有效古物商許可 -> %s" % st)
    st, res = call("GET", API + "/admission", token=admin)
    a = res["data"]
    ok(a.get("admissionState") == "BLOCKED_COMPLIANCE",
       "过了 G-A1 后卡在 G-A3 -> %s" % a.get("admissionState"))
    ok(sorted(a.get("missingComplianceTypes") or []) == ["AML", "ANTI_SOCIAL", "CREDIT_BUREAU", "IDENTITY"],
       "四类合规全缺 -> %s" % a.get("missingComplianceTypes"))

    # ---- 幂等：同一证号重推不产生第二行 ----
    st, res = call("POST", API + "/licenses", {
        "licenseNo": "LIC-" + TS, "competentAuthority": "東京都公安委員会",
        "issuedOn": d(-30), "expiresOn": d(5 * 365),
        "evidencePath": "oss://lic/new.pdf", "evidenceDigest": "sha256:new"}, token=admin)
    ok(st == 200 and res["data"].get("idempotent") is True,
       "同 (车商,类型,证号) 重推 → 200 + idempotent（实际 %s/%s）" % (st, res["data"].get("idempotent")))
    ok(psql("SELECT count(*) FROM dealer_licenses WHERE dealer_id=%d AND status='ACTIVE'" % did) == "1",
       "库内 ACTIVE 许可仍只有 1 行（幂等键 DLIC:* 与 uk_dlic_dealer_type_no 同时兜住）")

    # ---- G-A3 的三道拒绝：缺证据 / 缺第二人 / 同人自证 ----
    st, res = call("POST", API + "/compliance-checks", {
        "checkType": "AML", "checkedOn": d(0), "validUntil": d(180), "result": "PASS",
        "source": "MANUAL", "requestNo": "AMLX-" + TS, "coSignedBy": "reviewer2"}, token=admin)
    ok(st == 400 and res.get("code") == "VALID_001",
       "人工录入无证据文件 → 400 VALID_001（实际 %s/%s）" % (st, res.get("code")))
    st, res = call("POST", API + "/compliance-checks", {
        "checkType": "AML", "checkedOn": d(0), "validUntil": d(180), "result": "PASS",
        "source": "MANUAL", "requestNo": "AMLA-" + TS, "evidencePath": "oss://chk/aml.pdf"}, token=admin)
    ok(st == 403 and res.get("code") == "AUTH_003",
       "人工录入缺第二签认人 → 403 AUTH_003（实际 %s/%s）" % (st, res.get("code")))
    st, res = call("POST", API + "/compliance-checks", {
        "checkType": "AML", "checkedOn": d(0), "validUntil": d(180), "result": "PASS",
        "source": "MANUAL", "requestNo": "AMLB-" + TS, "evidencePath": "oss://chk/aml.pdf",
        "coSignedBy": "admin"}, token=admin)
    ok(st == 403 and res.get("code") == "AUTH_003",
       "签认人=录入人 → 403 AUTH_003 同人自证（实际 %s/%s）" % (st, res.get("code")))

    # ---- G-A3：UNKNOWN 一律按不通过（fail-closed）----
    st, res = call("POST", API + "/compliance-checks", {
        "checkType": "ANTI_SOCIAL", "checkedOn": d(0), "validUntil": d(180), "result": "UNKNOWN",
        "source": "MANUAL", "requestNo": "AS-" + TS, "evidencePath": "oss://chk/as.pdf",
        "coSignedBy": "reviewer2"}, token=admin)
    ok(st == 201, "录入反社核查 UNKNOWN -> %s" % st)
    st, res = call("GET", API + "/admission", token=admin)
    ok("ANTI_SOCIAL" in (res["data"].get("missingComplianceTypes") or []),
       "UNKNOWN 计入未通过清单（不折叠成 true）-> %s" % res["data"].get("missingComplianceTypes"))

    # ---- 补齐四类 PASS ----
    for ct in ("AML", "ANTI_SOCIAL", "IDENTITY", "CREDIT_BUREAU"):
        st, res = call("POST", API + "/compliance-checks", {
            "checkType": ct, "checkedOn": d(0), "validUntil": d(180), "result": "PASS",
            "source": "MANUAL", "requestNo": "%s-%s" % (ct, TS),
            "evidencePath": "oss://chk/%s.pdf" % ct, "coSignedBy": "reviewer2"}, token=admin)
        ok(st in (200, 201), "%s 核查 PASS 录入 -> %s" % (ct, st))
    # ANTI_SOCIAL 之前那行是 UNKNOWN，同 requestNo 才幂等；换了键就是第二行，
    # 判定按「该类型当前有没有一条在有效期内且 PASS」，所以能盖过去。
    st, res = call("GET", API + "/admission", token=admin)
    ok(res["data"].get("admissionState") == "BLOCKED_MEMBERSHIP",
       "过了 G-A3 后卡在 G-A2 -> %s" % res["data"].get("admissionState"))

    # ---- G-A2：拍卖场会员 ----
    st, res = call("POST", API + "/auction-memberships", {
        "auctionHouseCode": "USS-KANSAI", "memberNo": "MEM-" + TS, "memberGrade": "A",
        "joinedOn": d(-200), "expiresOn": d(300), "status": "ACTIVE",
        "evidencePath": "oss://mem/%s.pdf" % TS}, token=admin)
    ok(st in (200, 201), "登记拍卖场会员 -> %s" % st)
    st, res = call("GET", API + "/admission", token=admin)
    a = res["data"]
    ok(a.get("admissionState") in ("ADMITTED", "EXPIRING"),
       "四闸门齐 → %s（ADMITTED 或临期放行）" % a.get("admissionState"))
    ok(a.get("blocked") is False, "准入不再阻断放款 -> blocked=%s" % a.get("blocked"))

    # ---- 库内硬约束：MANUAL 无双签在库里也进不去（绕过服务直写会被 CHECK 挡）----
    bypass = psql("INSERT INTO dealer_compliance_checks(dealer_id,check_type,checked_on,result,source,checked_by)"
                  " VALUES (%d,'IDENTITY',CURRENT_DATE,'PASS','MANUAL','solo') RETURNING id" % did)
    ok(bypass in ("" , None) or "ERROR" in (bypass or ""),
       "直写 MANUAL 无 co_signed_by 被 V76 的 CHECK 拒绝 -> %s" % (bypass or "空（约束生效）"))

    # ---- G-A4：双人评分 ----
    st, res = call("POST", API + "/scores", {
        "operatingScore": 80, "stockQualityScore": 70, "runTs": TS + "a",
        "coSignedBy": "admin"}, token=admin)
    ok(st == 403 and res.get("code") == "AUTH_003",
       "评分同人自证 → 403 AUTH_003（实际 %s/%s）" % (st, res.get("code")))
    st, res = call("POST", API + "/scores", {
        "operatingScore": 180, "stockQualityScore": 70, "runTs": TS + "b",
        "coSignedBy": "reviewer2"}, token=admin)
    ok(st == 400 and res.get("code") == "VALID_001", "评分越界 0–100 → 400（实际 %s/%s）" % (st, res.get("code")))
    st, res = call("POST", API + "/scores", {
        "operatingScore": 80, "stockQualityScore": 70, "runTs": TS + "c",
        "coSignedBy": "reviewer2"}, token=admin)
    ok(st == 200 and res["data"].get("twoPersonConfirmed") is True,
       "双人确认后 twoPersonConfirmed=true -> %s/%s" % (st, res["data"].get("twoPersonConfirmed")))
    st, res2 = call("POST", API + "/scores", {
        "operatingScore": 80, "stockQualityScore": 70, "runTs": TS + "c",
        "coSignedBy": "reviewer2"}, token=admin)
    ok(res2["data"].get("idempotent") is True,
       "同 runTs 重推评分 → idempotent（幂等键 DSCR:{id}:{ts}）-> %s" % res2["data"].get("idempotent"))
    # dealers 上的列名带 score_ 前缀（V77），co_signed_by 是 dealer_compliance_checks 的列名（V76），两者别混
    cosign = psql("SELECT score_co_signed_by FROM dealers WHERE id=%d" % did)
    ok(cosign == "reviewer2", "第二签认人落库 -> %r" % cosign)

    # ---- 临期：换发到 20 天后到期 → EXPIRING 放行 + 告警 ----
    st, res = call("POST", API + "/licenses", {
        "licenseNo": "LIC-EXPIRING-" + TS, "competentAuthority": "東京都公安委員会",
        "issuedOn": d(-345), "expiresOn": d(20),
        "evidencePath": "oss://lic/expiring.pdf", "evidenceDigest": "sha256:exp"}, token=admin)
    ok(st == 201, "换发到 20 天后到期 -> %s" % st)
    ok(res["data"].get("supersededLicenseId"), "旧 ACTIVE 证自动降级 -> %s" % res["data"].get("supersededLicenseId"))
    ok(psql("SELECT count(*) FROM dealer_licenses WHERE dealer_id=%d AND status='SUPERSEDED'" % did) == "2",
       "两张旧证都在 SUPERSEDED（换发顺序由 uk_dealer_license_active 强制）")
    st, res = call("GET", API + "/admission", token=admin)
    ok(res["data"].get("admissionState") == "EXPIRING" and res["data"].get("blocked") is False,
       "临期 = EXPIRING 且放行（预警不阻断）-> %s" % res["data"].get("admissionState"))
    ok(int(psql("SELECT count(*) FROM alerts WHERE dealer_id=%d AND alert_type='DEALER_QUALIFICATION_EXPIRING'" % did) or 0) >= 1,
       "临期告警写进 alerts（work_orders 尚无 Java，这是等价替身）")

    # ---- 配置键真被读到：把告警窗口改成 0 后临期不再报 EXPIRING ----
    # 判据为什么必须是「改库里的值 ⟹ 判定跟着变」：如果 alertDays 是 Java 常量，
    # 这条断言就永远测不出来，收口时也就没人发现口径其实钉死在代码里了。
    # 必须排在「过期回落」之前：那张证一换成过期证，准入就变成阻断态，
    # 窗口改不改都是 BLOCKED_LICENSE，这条断言就失去分辨力了。
    KEY = "floorplan.used.license-expiry-alert-days"
    saved = psql("SELECT param_value FROM used_config_params WHERE param_key='%s' AND status='ACTIVE'" % KEY)
    psql("UPDATE used_config_params SET param_value='0' WHERE param_key='%s' AND status='ACTIVE'" % KEY)
    st, res = call("GET", API + "/admission", token=admin)
    ok(res["data"].get("admissionState") == "ADMITTED",
       "窗口调成 0 → 同一张证从 EXPIRING 变 ADMITTED（值来自 used_config_params，不是 Java 常量）-> %s"
       % res["data"].get("admissionState"))
    psql("UPDATE used_config_params SET param_value='%s' WHERE param_key='%s' AND status='ACTIVE'" % (saved or "30", KEY))
    st, res = call("GET", API + "/admission", token=admin)
    ok(res["data"].get("admissionState") == "EXPIRING",
       "窗口改回 %s → 又变回 EXPIRING（读的是同一行，没有缓存）-> %s" % (saved, res["data"].get("admissionState")))

    # ---- 过期回落 ----
    st, res = call("POST", API + "/licenses", {
        "licenseNo": "LIC-LAPSED-" + TS, "competentAuthority": "東京都公安委員会",
        "issuedOn": d(-400), "expiresOn": d(-1),
        "evidencePath": "oss://lic/lapsed.pdf", "evidenceDigest": "sha256:lap"}, token=admin)
    st, res = call("POST", API + "/admission/recalculate", token=admin)
    ok(res["data"].get("admissionState") == "BLOCKED_LICENSE",
       "证过期后重算 → 回到 BLOCKED_LICENSE（不存在白名单绕过）-> %s" % res["data"].get("admissionState"))
    # 快照与实时值分开报：闸门读实时，对账看快照有没有过期（这条线是 GET /admission 的三态设计）
    st, res = call("GET", API + "/admission", token=admin)
    ok(res["data"].get("snapshotStale") is False,
       "刚重算过 → snapshotStale=false（stored=%s live=%s）"
       % (res["data"].get("storedAdmissionState"), res["data"].get("admissionState")))

    # ---- 404 与审计 ----
    st, res = call("GET", "/api/dealer-qualifications/999999999/admission", token=admin)
    ok(st == 404 and res.get("code") == "NOT_FOUND_001", "不存在的车商 → 404（实际 %s/%s）" % (st, res.get("code")))
    audited = psql("SELECT count(*) FROM audit_events WHERE target_type='DEALER_QUALIFICATION'"
                   " AND target_id='%d'" % did)
    ok(int(audited or 0) >= 8, "资质写操作逐笔留痕 -> %s 条" % audited)


# ======================================================================
# U-R2 车辆主数据 / 车况票 / 車検証
# ======================================================================
KEY_MILEAGE = "floorplan.used.mileage-diff-threshold-km"


def r2():
    """U-R2 建账分流键、里程按来源可信序判定、一车一证与换证成链（D06 §2 验收点）。"""
    print("\n" + "=" * 68)
    print("U-R2 车辆主数据 / 車検証（UC-R2-1 建账 / R2-2 车况票 / R2-3·R2-6 里程 / R2-5 一车一证）")
    print("=" * 68)
    admin = login("admin")
    review = login("review_op")
    plender = login("post_lending")
    col = login("collateral")
    today = date.today()
    d = lambda n: (today + timedelta(days=n)).isoformat()

    st, res = call("POST", "/api/dealers", {
        "dealerCode": "DL-USEDV2-" + TS, "name": "USED车辆冒烟车商", "riskGrade": "B", "businessYears": 5},
        token=admin)
    ok(st == 201, "创建 USED 车辆段车商 -> %s" % st)
    if st != 201:
        return
    did = res["data"]["id"]

    UV = "/api/used-vehicles"
    RPT = lambda vin: "%s/%s/condition-reports" % (UV, vin)
    CERT = lambda vin: "%s/%s/certificates" % (UV, vin)
    VIN = "JT2USED" + TS[-10:]      # 主战场：建账 + 三张票 + 换证
    V_UNK = "JT3USED" + TS[-10:]    # 里程全未知：允许建账但阻断放款
    V_THR = "JT4USED" + TS[-10:]    # 阈值实验专用（差异按天+VIN+类型唯一，不能复用主战场）

    base = {  # 建账必填骨架，各断言在其上做单点改动
        "vin": VIN, "dealerId": did, "vehicleType": "USED", "purchaseChannel": "AUCTION",
        "brand": "TOYOTA", "model": "PRIUS", "modelYear": 2019, "color": "WHITE",
        "invoiceAmount": 2000000, "supplierName": "USS卡多店", "registrationPlate": "多摩300 あ 12-34",
        "firstRegisteredOn": d(-2200), "inspectionExpiryDate": d(400), "inspectionRemainingMonths": 13,
        "repairHistory": "NONE", "saleChannel": "RETAIL", "warehouseCustodianCode": "WH-SMK-" + TS[-6:],
        "binLocation": "A-01",
    }

    # ---- 认证与授权 ----
    st, res = call("GET", UV + "/" + VIN)
    ok(st == 401 and res.get("code") == "AUTH_001", "无令牌读车辆 → 401 AUTH_001（实际 %s/%s）" % (st, res.get("code")))
    st, res = call("POST", UV, dict(base), token=review)
    ok(st == 403 and res.get("code") == "AUTH_002",
       "采购审核岗（有 USED_VEHICLE_READ 无 MAINTAIN）建账 → 403（实际 %s/%s）" % (st, res.get("code")))

    # ---- 分流键与渠道：D06 §2 验收点「缺 vehicle_type / 非法 purchase_channel 被 400 拦」 ----
    st, res = call("POST", UV, {k: v for k, v in base.items() if k != "vehicleType"}, token=admin)
    ok(st == 400 and res.get("code") == "VALID_001", "缺 vehicle_type → 400（实际 %s/%s）" % (st, res.get("code")))
    st, res = call("POST", UV, dict(base, vehicleType="NEW_STOCK"), token=admin)
    ok(st == 400 and res.get("code") == "VALID_001",
       "vehicle_type=NEW_STOCK 走本端点 → 400（分流键不许混用）")
    st, res = call("POST", UV, dict(base, purchaseChannel="OEM"), token=admin)
    ok(st == 400 and res.get("code") == "VALID_001",
       "purchase_channel=OEM → 400（USED 只接受 AUCTION/TRADE_IN/WHOLESALE）-> %s"
       % (res.get("details") or {}).get("allowed"))
    st, res = call("POST", UV, dict(base, mileageSource="SOME_GARAGE", mileageKm=1000), token=admin)
    ok(st == 400 and res.get("code") == "VALID_001", "未知里程来源词 → 400 而不是撞库 CHECK 变 500")
    st, res = call("POST", UV, dict(base, mileageSource="UNKNOWN", mileageKm=5000), token=admin)
    ok(st == 400 and res.get("code") == "VALID_001",
       "UNKNOWN + 带里程值 → 400（V50 ck_vu_mileage_unknown 的正确表达是先拦后报）")

    # ---- 里程全未知：允许建账，但给出放款阻断标记 ----
    st, res = call("POST", UV, dict(base, vin=V_UNK, mileageSource="UNKNOWN", mileageKm=None), token=admin)
    ok(st == 201, "里程未知的车照样建账 -> %s" % st)
    ok(res["data"].get("mileageOutcome") == "UNDETERMINED" and res["data"].get("advanceBlockedByMileage") is True,
       "未知来源 → UNDETERMINED 且 advanceBlockedByMileage=true（建账放行、放款阻断）-> %s" % res["data"].get("mileageOutcome"))
    row = psql("SELECT coalesce(mileage::text,'NULL')||'/'||coalesce(mileage_source,'NULL') "
               "FROM vehicle_units WHERE vin='%s'" % V_UNK)
    ok(row == "NULL/UNKNOWN", "落库 mileage IS NULL + source=UNKNOWN（UNKNOWN 的正确写法）-> %s" % row)

    # ---- 正常建账（申报来源） ----
    st, res = call("POST", UV, dict(base, mileageSource="DEALER_DECLARED", mileageKm=50000,
                                    mileageCapturedOn=d(-30)), token=admin)
    ok(st == 201, "USED 建账 -> %s" % st)
    if st != 201:
        print("  [ABORT] 建账失败，后续 U-R2 断言无意义：%s" % json.dumps(res, ensure_ascii=False)[:300])
        return
    ok(res["data"].get("mileage") == 50000 and res["data"].get("mileageSource") == "DEALER_DECLARED",
       "首次建账即录入唯一观测值 → ADOPTED -> %s/%s"
       % (res["data"].get("mileage"), res["data"].get("mileageSource")))
    st, res = call("POST", UV, dict(base, mileageSource="DEALER_DECLARED", mileageKm=50000), token=admin)
    ok(st == 409 and res.get("code") == "VEHICLE_003",
       "同 VIN 重复建账 → 409 VEHICLE_003（不静默返回既有行）-> %s" % res.get("code"))
    ok((res.get("details") or {}).get("idempotencyKey") == "VEH:" + VIN,
       "409 里带出幂等键 VEH:{vin} -> %s" % (res.get("details") or {}).get("idempotencyKey"))

    # ---- 車検残存月数是录入值，服务端不推导（D06 §2.1 明写） ----
    row = psql("SELECT inspection_remaining_months||'/'||inspection_expiry_date FROM vehicle_units WHERE vin='%s'" % VIN)
    ok(row.startswith("13/"), "車検残存月数原样存 13（不因车検満了日=d(400) 被推导覆盖）-> %s" % row)

    # ---- UC-R2-2 车况票 + UC-R2-3/6 里程来源可信序 ----
    def report(vin, no, rtype, odo=None, **kw):
        body = {"reportNo": no, "reportType": rtype, "inspectedOn": d(-1), "inspector": "USS"}
        if odo is not None:
            body["odometerKm"] = odo
        body.update(kw)
        return call("POST", RPT(vin), body, token=admin)

    st, res = report(VIN, "AUC-" + TS, "AUCTION_INSPECTION", 49000, structureCondition="S", repairHistory="NONE")
    ok(st == 201 and res["data"].get("mileageSource") == "AUCTION_REPORT",
       "拍卖场票（49,000）优先级高于车商申报 → 覆盖主数据 -> %s"
       % (res["data"].get("mileageSource") + "/" + str(res["data"].get("mileage"))))
    ok(res["data"].get("mileageDiffKm") == 1000 and res["data"].get("mileageExceedsThreshold") is False,
       "差 1,000km ≤ 阈值 1,000 → 覆盖但不开差异（阈值是「超」不是「等」）-> diff=%s exceeds=%s"
       % (res["data"].get("mileageDiffKm"), res["data"].get("mileageExceedsThreshold")))

    st, res = report(VIN, "AUC-" + TS, "AUCTION_INSPECTION", 49000)
    ok(st == 200 and res["data"].get("idempotent") is True,
       "同 reportNo 重推 → 200 idempotent（票不重复入账）-> %s/%s" % (st, res["data"].get("idempotent")))
    n = psql("SELECT count(*) FROM vehicle_condition_reports WHERE vin='%s'" % VIN)
    ok(int(n or 0) == 1, "票表当天仍只有 1 行（幂等键 CRR:{reportNo} 生效）-> %s" % n)

    st, res = report(VIN, "WMS-" + TS, "WMS_INBOUND_RECHECK", 60000)
    ok(st == 201 and res["data"].get("mileage") == 60000 and res["data"].get("mileageSource") == "WMS_INBOUND",
       "入库复测（实物，最高优先）→ 覆盖 60,000/WMS_INBOUND -> %s" % res["data"].get("mileage"))
    ok(res["data"].get("mileageExceedsThreshold") is True, "差 11,000km > 1,000 → 超阈值")
    n = psql("SELECT count(*) FROM wms_reconcile_diffs WHERE vin='%s' AND diff_type='ODOMETER_DIFF'" % VIN)
    ok(int(n or 0) == 1, "超阈值开了一条 ODOMETER_DIFF（覆盖与开单是两件事，都留痕）-> %s 条" % n)

    st, res = report(VIN, "OWN-" + TS, "OWN_AUDIT", 59000)
    ok(res["data"].get("mileageOutcome") == "KEPT_MASTER",
       "自盘（OWN_AUDIT）低于入库实物（WMS_INBOUND）→ 不改主数据 -> %s" % res["data"].get("mileageOutcome"))
    row = psql("SELECT mileage||'/'||mileage_source FROM vehicle_units WHERE vin='%s'" % VIN)
    ok(row == "60000/WMS_INBOUND",
       "禁止静默覆盖：库里还是 60,000/WMS_INBOUND -> %s" % row)

    # ---- 修復歴取严（有记载优先于 UNKNOWN，且只往严的方向走） ----
    st, res = report(VIN, "OWN-B-" + TS, "OWN_AUDIT", None, repairHistory="MAJOR",
                     structureCondition="修復歴あり")
    row = psql("SELECT repair_history FROM vehicle_units WHERE vin='%s'" % VIN)
    ok(row == "MAJOR", "票面 MAJOR 比主数据 NONE 更严 → 取严回写 -> %s" % row)
    st, res = report(VIN, "OWN-C-" + TS, "OWN_AUDIT", None, repairHistory="NONE")
    row = psql("SELECT repair_history FROM vehicle_units WHERE vin='%s'" % VIN)
    ok(row == "MAJOR", "后续票写 NONE 也不把已认定的 MAJOR 改回去（取严不取宽）-> %s" % row)

    # ---- UC-R2-4 前置：票可以先于建账存在（V51 刻意不建 vin 的 FK） ----
    GHOST = "JT9USED" + TS[-10:]
    st, res = report(GHOST, "GHOST-" + TS, "AUCTION_INSPECTION", 12345)
    ok(st == 201 and res["data"].get("vehiclePending") is True,
       "VIN 不在账上的票照收（vehiclePending=true，落札当晚票先到）-> %s" % st)
    st, res = call("GET", UV + "/" + GHOST, token=admin)
    ok(st == 404 and res.get("code") == "VEHICLE_001", "但读车辆详情仍 404 -> %s" % res.get("code"))

    # ---- UC-R2-5 一车一证 / 跨车重号 / 换证成链 ----
    C1 = "CERT1-" + TS[-6:]
    C2 = "CERT2-" + TS[-6:]
    C3 = "CERT3-" + TS[-6:]
    cert = {
        "certificateNo": C1, "vin": VIN, "registrationPlate": "多摩300 あ 12-34",
        "ownerName": "株式会社USED", "userName": "株式会社USED",
        "inspectionExpiryDate": d(400), "issueDate": d(-200),
        "securityType": "INSPECTION_CERT_CUSTODY", "holderCustodianCode": "WH-SMK-" + TS[-6:],
        "holderType": "SELF", "custodyLocation": "金庫A", "checkSource": "MANUAL", "reason": "收证",
    }
    st, res = call("POST", CERT(V_UNK + "-NOPE"), dict(cert), token=col)
    ok(st == 404 and res.get("code") == "VEHICLE_001", "给不在账的车收证 → 404 -> %s" % res.get("code"))
    st, res = call("POST", CERT(VIN), {k: v for k, v in cert.items() if k != "inspectionExpiryDate"}, token=col)
    ok(st == 400 and res.get("code") == "VALID_001", "缺車検満了日 → 400（V52 NOT NULL）")
    st, res = call("POST", CERT(VIN), dict(cert, issueDate=d(500)), token=col)
    ok(st == 400 and res.get("code") == "VALID_001", "発行日晚于車検満了日 → 400")

    st, res = call("POST", CERT(VIN), cert, token=col)
    ok(st == 201 and res["data"].get("status") == "RECEIVED",
       "担保管理岗收证 → 201/RECEIVED -> %s/%s" % (st, res["data"].get("status")))
    ok(res["data"].get("encumbrance") == "UNKNOWN" and res["data"].get("pledgeBlocked") is True,
       "未给瑕疵结论 → 落 UNKNOWN 且 pledgeBlocked（缺字段不等于「查过且干净」）-> %s"
       % res["data"].get("encumbrance"))
    row = psql("SELECT inspection_certificate_id IS NOT NULL FROM vehicle_units WHERE vin='%s'" % VIN)
    ok(row == "t", "建账行的証書指针已绑上 -> %s" % row)
    st, res = call("POST", CERT(VIN), cert, token=col)
    ok(st == 200 and res["data"].get("idempotent") is True, "同证号同车重推 → 200 幂等 -> %s" % st)
    st, res = call("POST", CERT(VIN), dict(cert, certificateNo=C2), token=col)
    ok(st == 409 and res.get("code") == "CERT_002", "同车第二张在场证 → 409 CERT_002 -> %s" % res.get("code"))
    st, res = call("POST", CERT(V_UNK), dict(cert, vin=V_UNK), token=col)
    ok(st == 422 and res.get("code") == "CERT_003",
       "同证号挂另一台车 → 422 CERT_003（証書番号与 VIN 不一致，G-D1）-> %s" % res.get("code"))
    st, res = call("POST", CERT(VIN), dict(cert, vin="JT0OTHER" + TS[-7:]), token=col)
    ok(st == 422 and res.get("code") == "CERT_003", "路径 VIN 与报文 VIN 不一致 → 422 -> %s" % res.get("code"))

    # 换发：审批位（POST_LENDING_MANAGER）≠ 收证位（COLLATERAL_OFFICER），且必须二人
    CERTQ = "SELECT status||'/'||coalesce(predecessor_id::text,'NULL') FROM inspection_certificates " \
            "WHERE vin='%s' AND certificate_no='%%s'" % VIN
    rebind = dict(cert, reason="証書書換", coSignedBy="collateral")
    st, res = call("POST", CERT(VIN) + "/rebind", dict(rebind, certificateNo=C2), token=admin)
    ok(st == 403 and res.get("code") == "AUTH_002",
       "系统管理员换证 → 403（CERTIFICATE_REISSUE_APPROVE 不给 ADMIN，审批位≠执行位）-> %s" % res.get("code"))
    st, res = call("POST", CERT(VIN) + "/rebind", dict(rebind, certificateNo=C1), token=plender)
    ok(st == 400 and res.get("code") == "VALID_001",
       "同証書番号「换发」→ 400（記載変更是 UPDATE 原行，uk_cert_key 也只允许一行）-> %s" % res.get("code"))
    st, res = call("POST", CERT(VIN) + "/rebind", dict(rebind, certificateNo=C3, coSignedBy="post_lending"),
                   token=plender)
    ok(st == 403 and res.get("code") == "AUTH_003",
       "第二人＝操作人自己 → 403 AUTH_003（禁止单人换证）-> %s" % res.get("code"))
    st, res = call("POST", CERT(VIN) + "/rebind", dict(rebind, certificateNo=C3, reason="  "), token=plender)
    ok(st == 400 and res.get("code") == "VALID_001", "无事由换证 → 400（无原因的换证等同改数）")
    st, res = call("POST", CERT(VIN) + "/rebind", dict(rebind, certificateNo=C3), token=plender)
    ok(st == 200 and res["data"].get("predecessorStatus") == "RECEIVED",
       "贷后管理岗＋担保管理岗二人换发 → 200，旧证离开时是 RECEIVED -> %s/%s"
       % (st, res["data"].get("predecessorStatus")))
    old_row = psql(CERTQ % C1)
    new_row = psql(CERTQ % C3)
    ok(old_row == "REISSUED/NULL", "旧证落库 REISSUED 且不指向别人（链头）-> %s" % old_row)
    ok(new_row.startswith("RECEIVED/") and new_row.split("/")[-1].isdigit(),
       "新证 RECEIVED 且 predecessor_id 指向旧证（链的方向只有这一个，反了就成自环）-> %s" % new_row)
    st, res = call("GET", UV + "/" + VIN, token=admin)
    ok(res["data"].get("certificateChainCount") == 2, "详情里证链长度=2 -> %s" % res["data"].get("certificateChainCount"))
    ok(res["data"].get("certificate", {}).get("certificateNo") == C3,
       "在场证已是新证 -> %s" % res["data"].get("certificate", {}).get("certificateNo"))
    ev = psql("SELECT count(*) FROM certificate_custody_events e JOIN inspection_certificates c ON c.id=e.certificate_id "
              "WHERE c.vin='%s' AND e.event_type IN ('AMENDED','REISSUED','RECEIVED')" % VIN)
    ok(int(ev or 0) >= 3, "换证留下 AMENDED/REISSUED/RECEIVED 流水 -> %s 条" % ev)

    # ---- 里程阈值真的读库（不是 Java 常量） ----
    saved = psql("SELECT param_value FROM used_config_params WHERE param_key='%s' AND status='ACTIVE'" % KEY_MILEAGE)
    ok(saved is not None, "阈值键在 used_config_params 里（V78 已落库）-> %s" % saved)
    st, res = call("POST", UV, dict(base, vin=V_THR, mileageSource="DEALER_DECLARED", mileageKm=100000),
                   token=admin)
    ok(st == 201, "阈值实验车建账 -> %s" % st)
    # 两张票故意错开 inspected_on：同来源同天时「取最新」无从判定，断言会变成时绿时红
    st, res = report(V_THR, "THR-A-" + TS, "WMS_INBOUND_RECHECK", 100100, inspectedOn=d(-2))
    ok(res["data"].get("mileage") == 100100 and res["data"].get("mileageExceedsThreshold") is False,
       "阈值 1,000：差 100km → 覆盖但不开单 -> exceeds=%s" % res["data"].get("mileageExceedsThreshold"))
    n0 = int(psql("SELECT count(*) FROM wms_reconcile_diffs WHERE vin='%s'" % V_THR) or 0)
    psql("UPDATE used_config_params SET param_value='0' WHERE param_key='%s' AND status='ACTIVE'" % KEY_MILEAGE)
    st, res = report(V_THR, "THR-B-" + TS, "WMS_INBOUND_RECHECK", 100200, inspectedOn=d(-1))
    n1 = int(psql("SELECT count(*) FROM wms_reconcile_diffs WHERE vin='%s'" % V_THR) or 0)
    ok(res["data"].get("mileageExceedsThreshold") is True and n1 == n0 + 1,
       "阈值调成 0 → 同样差 100km 就开单（值来自库，运行时读，无缓存）-> 差异 %s→%s 条" % (n0, n1))
    ok(res["data"].get("mileageOutcome") == "KEPT_MASTER" and res["data"].get("mileage") == 100100,
       "同优先级的新观测不覆盖主数据（先比可信序，再比采集时点）-> %s/%s"
       % (res["data"].get("mileageOutcome"), res["data"].get("mileage")))
    psql("UPDATE used_config_params SET param_value='%s' WHERE param_key='%s' AND status='ACTIVE'"
         % (saved or "1000", KEY_MILEAGE))

    # ---- 手动重算 + 详情只读不写 ----
    st, res = call("POST", UV + "/" + V_UNK + "/mileage/reconcile", token=admin)
    ok(st == 200 and res["data"].get("advanceBlockedByMileage") is True,
       "未知里程的车手动重算仍是 UNDETERMINED / 阻断放款 -> %s" % res["data"].get("mileageOutcome"))
    st, before = call("GET", UV + "/" + VIN, token=admin)
    st, after = call("GET", UV + "/" + VIN, token=admin)
    ok(before.get("data") == after.get("data"), "GET 详情两次结果一致（读路径不顺手重算）")
    ok(after["data"].get("openDiffCount") >= 1, "详情带出未决差异数 -> %s" % after["data"].get("openDiffCount"))

    audited = psql("SELECT count(*) FROM audit_events WHERE target_type='USED_VEHICLE' AND target_id IN ('%s','%s','%s')"
                   % (VIN, V_UNK, V_THR))
    ok(int(audited or 0) >= 10, "车辆写操作逐笔留痕 -> %s 条" % audited)


def main():
    print("=" * 68)
    print("USED 二手车库存垫资 · 冒烟（BASE=%s）" % BASE)
    print("=" * 68)
    st, res = call("POST", "/api/auth/login", {"username": "admin", "password": "admin123"})
    if st != 200:
        print("预检失败：%s 不可达或未登录成功 → %s" % (BASE, st))
        sys.exit(1)
    TOK["admin"] = res["data"]["token"]

    r1()
    r2()

    print("\n" + "=" * 68)
    if FAILS:
        print("USED 冒烟失败 %d 项：" % len(FAILS))
        for f in FAILS:
            print("  - " + f)
        sys.exit(1)
    print("USED 冒烟全部通过 ✅")
    print("=" * 68)


if __name__ == "__main__":
    main()
