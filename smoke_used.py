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
  U-R3 估值与 LTV：单源/降级源/缺署名一律拒在写之前（库里零行）、同键重放不写第二行、
       审慎值可由明细复算、L1〜L5 的编辑期码与运行时码分开、覆盖二人＋115%＋同日防线、
       参数三键读库（含脏值 fail-closed）
  后续切片（U-R4 落札、U-R5 放款闸门、U-R6 WMS 账实、U-R7 放车放证、U-R8 回款回补）
  在此文件里按同样的 ok() 断言追加。

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
    "import_op":   ("import123",     "PURCHASE_OPERATOR"),    # 持 VALUATION_RECORD，刻意不持 OVERRIDE（D-4 分岗）
    "advisory":    ("advisory123",   "RISK_ADVISORY_OFFICER"),  # 持 VALUATION_OVERRIDE + LTV_POLICY_APPROVE
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


def D(res):
    """取响应的 data；缺失时回空 dict。

    冒烟脚本必须跑完：一次意外的 403 若让 res["data"] 抛 KeyError，
    后面的断言一条都不会执行，屏幕上的最后一条 PASS 就成了「看起来像测过了」。
    """
    return res.get("data") or {}


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


# USED R3 的三枚可调参数键（V79 种）：本段的「值来自库」断言都围绕它们。
KEY_VALID_DAYS = "floorplan.used.valuation.valid-days"
KEY_UPLIFT = "floorplan.used.valuation.manual-override-max-uplift"
KEY_RULE = "floorplan.used.valuation.default-prudent-rule"


def r3():
    """U-R3 多源审慎估值 + 分档 LTV 政策（D08 §3〜§5、D10 D-4／D-5、红线「无基准不放款」）。"""
    print("\n" + "=" * 68)
    print("U-R3 估值与 LTV（R3.1 录入 / R3.2 政策三代际 / R3.3 覆盖 / R3.4 失效 / R3.5 试算）")
    print("=" * 68)
    admin = login("admin")        # 持 LTV_POLICY_MANAGE，刻意不持 APPROVE / RECORD / OVERRIDE
    rec = login("import_op")      # 持 VALUATION_RECORD，刻意不持 OVERRIDE（D-4 分岗）
    ovr = login("advisory")       # 持 VALUATION_OVERRIDE + LTV_POLICY_APPROVE，刻意不持 RECORD
    today = date.today()
    d = lambda n: (today + timedelta(days=n)).isoformat()

    st, res = call("POST", "/api/dealers", {
        "dealerCode": "DL-USEDV3-" + TS, "name": "USED估值冒烟车商", "riskGrade": "B", "businessYears": 6},
        token=admin)
    ok(st == 201, "创建 USED 估值段车商 -> %s" % st)
    if st != 201:
        return
    did = D(res)["id"]

    VIN = "JT5USED" + TS[-10:]
    st, res = call("POST", "/api/used-vehicles", {
        "vin": VIN, "dealerId": did, "vehicleType": "USED", "purchaseChannel": "AUCTION",
        "brand": "TOYOTA", "model": "AXIO", "modelYear": 2021, "color": "SILVER",
        "invoiceAmount": 2000000, "supplierName": "USS卡多店", "registrationPlate": "多摩500 か 56-78",
        "firstRegisteredOn": d(-1000), "inspectionExpiryDate": d(400), "inspectionRemainingMonths": 13,
        "mileageSource": "AUCTION_REPORT", "mileageKm": 30000, "mileageCapturedOn": d(-3),
        "repairHistory": "NONE", "auctionGrade": 4.5, "saleChannel": "RETAIL",
        "warehouseCustodianCode": "WH-SMK3-" + TS[-6:], "binLocation": "B-02"}, token=admin)
    ok(st == 201, "R3 主战场建账（约 33 个月 / 3 万 km / 評価点 4.5）-> %s" % st)
    if st != 201:
        print("  [ABORT] 建账失败，后续 U-R3 断言无意义：%s" % json.dumps(res, ensure_ascii=False)[:300])
        return

    VAL = "/api/valuations"
    POL = "/api/ltv-policies"
    two_src = {"vin": VIN, "purpose": "ADVANCE_BASE", "sources": [
        {"source": "AUCTION_WON_PRICE", "value": 1800000, "provider": "USS", "ref": "LOT-" + TS},
        {"source": "THIRD_PARTY_APPRAISAL", "value": 1600000, "provider": "GOS", "ref": "APPR-" + TS}]}

    # ---- 认证与分岗（D10 §4 D-4：录入 ↔ 覆盖 两个权限点不并列）----
    st, res = call("POST", VAL, two_src)
    ok(st == 401 and res.get("code") == "AUTH_001", "无令牌录估值 → 401 AUTH_001（实际 %s/%s）" % (st, res.get("code")))
    st, res = call("POST", VAL, two_src, token=ovr)
    ok(st == 403 and res.get("code") == "AUTH_002",
       "增信岗（有 OVERRIDE 无 RECORD）录估值 → 403（实际 %s/%s）" % (st, res.get("code")))
    st, res = call("GET", VAL + "/active?vin=" + VIN, token=rec)
    ok(st == 404 and res.get("code") == "NOT_FOUND_001",
       "读口不返回空对象：无 ACTIVE 基准 → 404（「没做过估值」与「做了但字段空着」在闸门上是两种处置）-> %s"
       % res.get("code"))

    # ---- 不合格的形状全部拒在写之前，库里零行 ----
    st, res = call("POST", VAL, {"vin": VIN, "purpose": "ADVANCE_BASE", "sources": []}, token=rec)
    ok(st == 422 and res.get("code") == "VALU_001", "零输入源 → 422 VALU_001（NO_SOURCE）-> %s" % res.get("code"))
    st, res = call("POST", VAL, {"vin": VIN, "purpose": "ADVANCE_BASE", "sources": [
        {"source": "AUCTION_WON_PRICE", "value": 1800000, "provider": "USS"}]}, token=rec)
    ok(st == 422 and res.get("code") == "VALU_001"
       and "SINGLE_SOURCE_NOT_MANUAL" in json.dumps(res.get("details") or {}, ensure_ascii=False),
       "单源 ADVANCE_BASE → 422 且拒因区分得开「录了但只有一个源」与「一个源都没录」-> %s"
       % (res.get("details") or {}).get("rejects"))
    n = psql("SELECT count(*) FROM vehicle_valuations WHERE vin='%s'" % VIN)
    ok(n == "0", "不合格估值一行都不落（库里不存在「有行但不生效」的第三种态）-> %s" % n)

    st, res = call("POST", VAL, {**two_src, "sources": [
        {"source": "INTERNAL_DEGRADED", "value": 900000, "provider": "SYS"}]}, token=rec)
    ok(st == 400 and res.get("code") == "VALID_001",
       "INTERNAL_DEGRADED 不接受人工直录 → 400 而不是 422 VALU_003（要告诉调用方的是「这个源不许从这里进来」，"
       "不是「这台车估值偏低」）-> %s/%s" % (st, res.get("code")))
    st, res = call("POST", VAL, {"vin": VIN, "purpose": "ADVANCE_BASE", "sources": [
        {"source": "MARKET_MATRIX", "value": 1500000},
        {"source": "THIRD_PARTY_APPRAISAL", "value": 1600000, "provider": "GOS"}]}, token=rec)
    ok(st == 400 and res.get("code") == "VALID_001",
       "事实源缺 provider → 400（uk_val_input 对 NULL 彼此不等 ⟹ 两个未署名的行情价能并存，"
       "审慎值取哪条由写入次序决定）")
    st, res = call("POST", VAL, {"vin": VIN, "purpose": "ADVANCE_BASE", "sources": [
        {"source": "MARKET_MATRIX", "value": 1500000, "provider": "GAP"},
        {"source": "MARKET_MATRIX", "value": 1400000, "provider": "GAP"}]}, token=rec)
    ok(st == 400 and res.get("code") == "VALID_001",
       "同一请求内重复 (source, provider, as_of) → 400（放行则 ON CONFLICT DO NOTHING 静默丢第二条）")
    st, res = call("POST", VAL, dict(two_src, valuationDate=d(0), validUntil=d(-1)), token=rec)
    ok(st == 400 and res.get("code") == "VALID_001",
       "valid_until 早于 valuation_date → 400（一出生就过期的结论，闸门只会报 VALU_002，读者找不到原因）")
    st, res = call("POST", VAL, dict(two_src, coSignedBy="admin"), token=rec)
    ok(st == 400 and res.get("code") == "VALID_001",
       "无人工值却给 co_signed_by → 400（库里 ck_val_override_pair 会放过它，但那条「二人复核」痕迹是假的）")
    st, res = call("POST", VAL, {**two_src, "sources": [
        {"source": "AUCTION_WON_PRICE", "value": -1, "provider": "USS"},
        {"source": "THIRD_PARTY_APPRAISAL", "value": 1600000, "provider": "GOS"}]}, token=rec)
    ok(st == 400 and res.get("code") == "VALID_002", "源报价为负 → 400 VALID_002（金额非法，不是估值不生效）")
    st, res = call("POST", VAL, dict(two_src, vin="JT0NOWHERE" + TS[-7:]), token=rec)
    ok(st == 404 and res.get("code") == "VEHICLE_001", "VIN 不在账 → 404（估值必须挂在具体车台上）")

    # ---- 合格录入：两源取小 ----
    st, res = call("POST", VAL, two_src, token=rec)
    ok(st == 201, "两源（落札 180 万 / 查定 160 万）录入 → 201 -> %s" % st)
    if st != 201:
        print("  [ABORT] 合格估值录不进，后续 U-R3 断言无意义：%s" % json.dumps(res, ensure_ascii=False)[:300])
        return
    v1 = D(res)
    ok(v1.get("prudentValue") == 1600000 and v1.get("primarySource") == "THIRD_PARTY_APPRAISAL",
       "MIN_OF_SOURCES 取小、主源取胜者所属源 -> %s/%s" % (v1.get("prudentValue"), v1.get("primarySource")))
    ok(v1.get("prudentRule") == "MIN_OF_SOURCES", "算法取配置键，不接受请求指定 -> %s" % v1.get("prudentRule"))
    ok(v1.get("eligibleSourceCount") == 2 and v1.get("weightedApplied") is False,
       "响应如实说明本次没按加权算（weightedApplied=%s）" % v1.get("weightedApplied"))
    row = psql("SELECT status||'/'||prudent_value||'/'||primary_source||'/'||coalesce(prudent_rule,'NULL') "
               "FROM vehicle_valuations WHERE id=%d" % v1["valuationId"])
    ok(row == "ACTIVE/1600000/THIRD_PARTY_APPRAISAL/MIN_OF_SOURCES", "落库结论与响应一致 -> %s" % row)
    n = psql("SELECT count(*) FROM valuation_inputs WHERE valuation_id=%d" % v1["valuationId"])
    ok(n == "2", "两条输入明细同事务落库（否则 D11 A6 的「由明细复算审慎值」无从谈起）-> %s" % n)
    key1 = v1.get("valuationKey") or ""
    ok(key1.startswith("VAL-" + VIN), "业务键 VAL-{vin}-{yyyymmdd}-{seq} -> %s" % key1)

    st, res = call("GET", VAL + "/active?vin=" + VIN, token=rec)
    ok(st == 200 and D(res).get("prudentValue") == 1600000, "读口与闸门同源 -> %s" % st)
    ok(D(res).get("recalculatedPrudentValue") == 1600000
       and D(res).get("consistentWithInputs") is True,
       "主表审慎值 == 由明细复算值（两套并存不裂）-> %s/%s"
       % (D(res).get("prudentValue"), D(res).get("recalculatedPrudentValue")))
    ok(D(res).get("usableAsAdvanceBase") is True, "usableAsAdvanceBase=true（闸门读的同一个判定）")
    ok(D(res).get("sourceColumnCount") == 2, "来源列计数与明细一致 -> %s" % D(res).get("sourceColumnCount"))

    # ---- 幂等重放与键复用 ----
    st, res = call("POST", VAL, dict(two_src, valuationKey=key1), token=rec)
    ok(st == 200 and D(res).get("idempotent") is True and D(res).get("supersededCount") == 0,
       "同 valuation_key 重推 → 200 + idempotent + 不替代自己 -> %s" % st)
    ok(D(res).get("valuationId") == v1["valuationId"], "重放回显的就是那一条既有结论，不是新行")
    n = psql("SELECT count(*)||'/'||status FROM vehicle_valuations WHERE valuation_key='%s' GROUP BY status" % key1)
    ok(n == "1/ACTIVE", "重放一个字都没写（若放行则唯一索引报错会把同事务的 supersede 一起回滚，"
                        "调用方重试永远在「替代旧行→插新行失败」之间打转）-> %s" % n)
    st, res = call("POST", VAL, dict(two_src, valuationKey=key1, purpose="PERIODIC_RECHECK"), token=rec)
    ok(st == 409 and res.get("code") == "CONFLICT_001",
       "同一把键拿去描述另一种用途 → 409（键复用不是重放）-> %s/%s" % (st, res.get("code")))

    # ---- LTV 政策：起草 → 配带 → 换人批准 → 生效冻结（L5）----
    st, res = call("POST", POL, {"policyCode": "SMK-R3-" + TS, "policyName": "R3 冒烟政策",
                                 "effectiveFrom": d(-1), "defaultRatio": 0.60}, token=admin)
    ok(st == 201 and D(res).get("status") == "DRAFT" and D(res).get("bandCount") == 0,
       "起草恒为 DRAFT 且零带（不给「创建即生效」留入口）-> %s/%s/%s"
       % (st, D(res).get("status"), D(res).get("bandCount")))
    if st != 201:
        return
    pid = D(res)["policyId"]
    ok(D(res).get("createdBy") == "admin" and D(res).get("approvedBy") is None,
       "created_by 取令牌身份（admin），不由请求体自报 -> %s" % D(res).get("createdBy"))
    ok(psql("SELECT count(*) FROM ltv_policies WHERE status='ACTIVE' AND policy_code='SMK-R3-%s'" % TS) == "0",
       "起草不产生 ACTIVE：无政策运行＝全线拒付，不能靠草稿假装已上线（D08 §5.1）")
    st, res = call("POST", POL, {"policyCode": "SMK-R3-" + TS, "policyName": "R3 冒烟政策（越权尝试）",
                                 "effectiveFrom": d(-1)}, token=ovr)
    ok(st == 403 and res.get("code") == "AUTH_002", "增信岗无 LTV_POLICY_MANAGE → 403")
    st, res = call("POST", POL + "/%d/approve" % pid, None, token=admin)
    ok(st == 403 and res.get("code") == "AUTH_002",
       "起草人自己批准 → 403（D-5 先落在角色上：MANAGE 与 APPROVE 不并列）-> %s/%s" % (st, res.get("code")))
    st, res = call("POST", POL, {"policyCode": "SMK-R3-" + TS, "effectiveFrom": d(-1), "policyName": "x"},
                   token=admin)
    ok(st == 201 and D(res).get("policyVersion") == 2,
       "version 由服务端 max(version)+1 推（客户端挑版本会出现两版 v3 撞索引）-> %s"
       % D(res).get("policyVersion"))
    draft2 = D(res).get("policyId") or 0   # 起草失败时给 0：路径会被 requireId 拒成 400，断言如实变红而不是脚本崩掉

    ok(psql("SELECT count(*) FROM ltv_policy_bands WHERE policy_id=%d" % pid) == "0",
       "起草端点不接收带集合：建政策与配带是两个动作")
    st, res = call("PUT", POL + "/%d/bands" % pid, [{"priority": 1, "maxRatio": 0.70},
                                                    {"priority": 1, "maxRatio": 0.65}], token=admin)
    ok(st == 400 and res.get("code") == "VALID_001"
       and "L1" in json.dumps((res.get("details") or {}).get("violations") or []),
       "同政策内 priority 重复 → 400 L1（编辑期用 VALID_001：LTV_002 是运行时拒付码，"
       "政策没上线不该产生工单）-> %s" % (res.get("details") or {}).get("summary"))
    st, res = call("PUT", POL + "/%d/bands" % pid, [{"priority": 1, "maxRatio": 1.0}], token=admin)
    ok(st == 400 and "L4" in json.dumps((res.get("details") or {}).get("violations") or []),
       "max_ratio=1.0 全额融资档：V57 的 CHECK 允许、D08 §5.4 禁止，由校验层拦下 -> %s"
       % ((res.get("details") or {}).get("violations") or [{}])[0].get("detail"))
    st, res = call("PUT", POL + "/%d/bands" % pid, [{"priority": 1, "maxRatio": 0.55}], token=admin)
    ok(st == 400 and "L3" in json.dumps((res.get("details") or {}).get("violations") or []),
       "兜底 0.60 未严格小于最小带 0.55 → L3 拒（分档表写得越差、越多车落兜底、放得越多钱）")
    st, res = call("PUT", POL + "/%d/bands" % pid, [], token=admin)
    ok(st == 400 and "BANDS_EMPTY" in json.dumps((res.get("details") or {}).get("violations") or []),
       "空数组＝清空带集合，仍被校验拦下（空政策一旦 ACTIVE 就是「有生效政策」的空壳）")
    n = psql("SELECT count(*) FROM ltv_policy_bands WHERE policy_id=%d" % pid)
    ok(n == "0", "校验失败时整体不写（库里没有半套带集合）-> %s" % n)
    st, res = call("PUT", POL + "/%d/bands" % draft2, [{"priority": 1, "ageBandMinMonths": 36,
                                                        "ageBandMaxMonths": 48, "maxRatio": 0.70}], token=admin)
    ok(st == 400 and "L2" in json.dumps((res.get("details") or {}).get("violations") or []),
       "v2 无兜底成数 + 带只罩 36〜48 个月 → L2 零命中缺口（边界样本从带自身导出，含接缝两侧）-> %s"
       % ((res.get("details") or {}).get("uncoveredCount")))
    single_band = [{"priority": 1, "maxRatio": 0.70, "rateAddonBps": 20, "breachThreshold": 80,
                    "note": "R3 冒烟唯一带：七维全不设限＝任意合法输入全覆盖"}]
    st, res = call("PUT", POL + "/%d/bands" % pid, single_band, token=admin)
    ok(st == 200 and D(res).get("bandCount") == 1 and D(res).get("previousBandCount") == 0,
       "全量替换写 1 条带 -> %s/bandCount=%s" % (st, D(res).get("bandCount")))
    ok(D(res).get("validation", {}).get("valid") is True, "响应带回校验结论（评审不必再点一次）")
    n = psql("SELECT count(*) FROM ltv_policy_bands WHERE policy_id=%d" % pid)
    ok(n == "1", "库里带集合同步替换，无残留半套 -> %s" % n)

    st, res = call("POST", POL + "/%d/approve" % pid, None, token=ovr)
    ok(st == 200 and D(res).get("status") == "ACTIVE",
       "换人批准生效（起草人 admin 无 APPROVE，批准人 ovr 无 MANAGE）-> %s/%s" % (st, D(res).get("status")))
    ok(D(res).get("approvedBy") == "advisory" and psql(
        "SELECT approved_by FROM ltv_policies WHERE id=%d" % pid) == "advisory",
       "生效由批准人落名（V57 ck_policy_approved_by 的应用层对应）")
    ok(D(res).get("bandsFrozen") is True, "响应如实标出带集合已冻结")
    st, res = call("POST", POL + "/%d/approve" % pid, None, token=ovr)
    ok(st == 400 and res.get("code") == "VALID_001", "重复批准被状态机挡住（不是再写一行 ACTIVE）")
    st, res = call("PUT", POL + "/%d/bands" % pid, single_band, token=admin)
    ok(st == 422 and res.get("code") == "LTV_004",
       "生效中不得改带（L5）→ 422 LTV_004，正路是新建版本 -> %s/%s" % (st, res.get("code")))
    st, res = call("PUT", POL + "/%d/bands" % draft2, [{"priority": 1, "maxRatio": 0.70}], token=admin)
    ok(st == 200, "同码另一版（v2）仍是 DRAFT，改带不受 v1 生效影响 -> %s" % st)
    st, res = call("GET", POL + "?status=ACTIVE", token=rec)
    hit_row = [r for r in (res.get("data") or []) if r.get("policyId") == pid]
    ok(st == 200 and len(hit_row) == 1 and len(hit_row[0].get("bands") or []) == 1
       and (hit_row[0].get("validation") or {}).get("valid") is True,
       "列表内联带集合与校验结论（PUT 是全量替换，客户端必须能读到当前带集当底本；契约没有 GET /{id}）")

    # ---- 试算（E2E-0：1,600,000 × 0.70 = 1,120,000）----
    st, res = call("GET", POL + "/%d/simulation?vin=%s&requestedAmount=1200000" % (pid, VIN), token=rec)
    ok(st == 200, "试算只算不落 → 结论恒 200（未命中/超额以字段表达，422 等于只回答「能过的车」）-> %s" % st)
    sim = D(res)
    ok(sim.get("outcome") == "HIT" and sim.get("hitPriority") == 1 and abs((sim.get("maxRatio") or 0) - 0.70) < 1e-9
       and sim.get("rateAddonBps") == 20 and float(sim.get("breachThreshold") or 0) == 80,
       "定档命中 priority=%s、成数 %s，附加_bp 与突破阈值一起从带行读出 -> %s"
       % (sim.get("hitPriority"), sim.get("maxRatio"), sim.get("outcome")))
    ok(sim.get("policyStatus") == "ACTIVE" and sim.get("effectiveOnThisDate") is True,
       "政策状态与该日是否适用一并回显（评审要在生效前跑，此时应为 DRAFT/false）")
    ti = sim.get("tierInput") or {}
    ok(ti.get("grade") == 4.5 and ti.get("mileageKm") == 30000 and ti.get("repairHistory") == "NONE"
       and ti.get("inspectionRemainingMonths") == 13,
       "分档输入回显七维（没命中时才知道是数据缺还是政策缺）-> grade=%s km=%s insp=%s heat=%s"
       % (ti.get("grade"), ti.get("mileageKm"), ti.get("inspectionRemainingMonths"), ti.get("modelHeat")))
    ok(ti.get("modelHeat") is None and sim.get("dealerGradeMissing") is True,
       "model_heat（[待定源 D-06]）与 dealer_grade（U-11）不自己合成：填成 0 等于把「不知道」变成「知道且很糟」")
    ok(sim.get("prudentValue") == 1600000 and sim.get("maxAdvance") == 1120000,
       "E2E-0：审慎值 1,600,000 × 0.70 → FLOOR = 1,120,000 -> %s" % sim.get("maxAdvance"))
    ok(sim.get("valuationState") == "USABLE" and sim.get("valuationStatus") == "ACTIVE",
       "试算复用闸门的可用性判定 -> %s" % sim.get("valuationState"))
    ok(sim.get("ceilingExceeded") is True and sim.get("exceedAmount") == 80000
       and sim.get("wouldRejectCode") == "LTV_001",
       "申请 1,200,000 超上限 80,000 → 判 LTV_001（真正的拒付发生在 R6 闸门）-> %s" % sim.get("exceedAmount"))
    st, res = call("GET", POL + "/%d/simulation?vin=%s&requestedAmount=1120000" % (pid, VIN), token=rec)
    ok(D(res).get("ceilingExceeded") is False and D(res).get("exceedAmount") == 0
       and D(res).get("wouldRejectCode") is None,
       "边界：申请额恰好等于上限 → 通过（上限的定义就是「最多这么多」，四舍五入的界不许在这里漂）")
    st, res = call("GET", POL + "/%d/simulation?vin=%s&requestedAmount=1000000&asOf=%s"
                   % (pid, VIN, d(30)), token=rec)
    ok(st == 200 and D(res).get("asOf") == d(30),
       "asOf 可指定基准日（估值到期那一天的敞口也要能推演）-> %s" % D(res).get("asOf"))
    ok(D(res).get("legacyBasisAvailable") is True
       and str(D(res).get("legacyNote", "")).startswith("invoice_amount"),
       "旧口径对照列在场且明写禁止作放款基准 -> legacy=%s ratio=%s"
       % (D(res).get("legacyMaxAdvance"), D(res).get("advanceRatio")))
    n = psql("SELECT count(*) FROM vehicle_valuations WHERE vin='%s'" % VIN)
    ok(n == "1", "试算写了零行（GET 不是隐藏写点）-> %s" % n)

    # ---- 重录：新行 ACTIVE + 旧行 SUPERSEDED（同事务）----
    st, res = call("POST", VAL, {"vin": VIN, "purpose": "ADVANCE_BASE", "sources": [
        {"source": "AUCTION_WON_PRICE", "value": 1900000, "provider": "USS", "ref": "LOT2-" + TS},
        {"source": "MARKET_MATRIX", "value": 1750000, "provider": "GAP"}]}, token=rec)
    ok(st == 201 and D(res).get("supersededCount") == 1,
       "重录在同一事务里替代旧行 -> supersededCount=%s" % D(res).get("supersededCount"))
    v2 = D(res)
    ok(psql("SELECT count(*) FROM vehicle_valuations WHERE vin='%s' AND status='ACTIVE'" % VIN) == "1",
       "一 VIN 只有一行 ACTIVE（uk_val_active 未被绕过）")
    ok(psql("SELECT status FROM vehicle_valuations WHERE valuation_key='%s'" % key1) == "SUPERSEDED",
       "旧行留档 SUPERSEDED 且未被改写（重估不回溯；当时那笔的定价依据仍是 1,600,000）")
    ok(v2.get("prudentValue") == 1750000 and v2.get("primarySource") == "MARKET_MATRIX",
       "新结论独立算（不因旧行存在而继承）-> %s/%s" % (v2.get("prudentValue"), v2.get("primarySource")))

    # ---- 人工覆盖（分岗 + 二人 + 上限 + 同日防线）----
    vid = v2.get("valuationId") or 0
    ok_reason = "同车近30日成约价复核后判定估值偏高"
    st, res = call("POST", VAL + "/%d/override" % vid, {
        "manualValue": 1700000, "reason": ok_reason, "coSignedBy": "advisory"}, token=rec)
    ok(st == 403 and res.get("code") == "AUTH_002",
       "录入岗做覆盖 → 403（D-4 的分岗落在角色上，不靠前端隐藏按钮）")
    st, res = call("POST", VAL + "/%d/override" % vid, {
        "manualValue": 1700000, "reason": ok_reason, "coSignedBy": "advisory"}, token=ovr)
    ok(st == 403 and res.get("code") == "AUTH_003",
       "第二签认人＝操作人自己 → 403 AUTH_003（二人必须是两个自然人）-> %s/%s" % (st, res.get("code")))
    st, res = call("POST", VAL + "/%d/override" % vid, {
        "manualValue": 1700000, "reason": "再看一眼", "coSignedBy": "admin"}, token=ovr)
    ok(st == 400 and res.get("code") == "VALID_001",
       "理由 4 字符 < 10 → 400（D08 §3.4 不接受「重新看一下」这种话）")
    st, res = call("POST", VAL + "/%d/override" % vid, {
        "reason": ok_reason, "coSignedBy": "admin"}, token=ovr)
    ok(st == 400 and res.get("code") == "VALID_001",
       "缺 manual_value → 400（缺字段不等于 0；0 会把这台车的基准直接清零）")
    st, res = call("POST", VAL + "/%d/override" % vid, {
        "manualValue": -5, "reason": ok_reason, "coSignedBy": "admin"}, token=ovr)
    ok(st == 400 and res.get("code") == "VALID_002", "人工值为负 → 400 VALID_002")
    st, res = call("POST", VAL + "/%d/override" % vid, {
        "manualValue": 2500000, "reason": ok_reason, "coSignedBy": "admin"}, token=ovr)
    ok(st == 422 and res.get("code") == "VALU_005",
       "上限 = 自动源最大 1,900,000 × 1.15 = 2,185,000 → 250 万被拒 -> ceiling=%s uplift=%s"
       % ((res.get("details") or {}).get("ceiling"), (res.get("details") or {}).get("uplift")))
    st, res = call("POST", VAL + "/%d/override" % vid, {
        "manualValue": 1500000, "reason": ok_reason, "coSignedBy": "admin"}, token=ovr)
    ok(st == 200 and D(res).get("prudentValue") == 1500000,
       "覆盖生效 → MIN 规则下人工值 150 万成为新基准 -> %s/%s" % (st, D(res).get("prudentValue")))
    ok(D(res).get("previousPrudentValue") == 1750000
       and D(res).get("maxAutoSource") == 1900000 and D(res).get("overrideCeiling") == 2185000,
       "上限分母只算自动源（人工值不进分母，否则 115%% 会随覆盖次数复利放大）-> %s/%s/%s"
       % (D(res).get("previousPrudentValue"), D(res).get("maxAutoSource"),
          D(res).get("overrideCeiling")))
    ok(psql("SELECT manual_value||'/'||prudent_value||'/'||primary_source||'/'||overridden_by||'/'||co_signed_by "
             "FROM vehicle_valuations WHERE id=%d" % vid) == "1500000/1500000/MANUAL_OVERRIDE/advisory/admin",
       "主表：人工值＋二人落名，三个自动源列未被改写")
    n = psql("SELECT count(*) FROM valuation_inputs WHERE valuation_id=%d" % vid)
    ok(n == "3", "覆盖是追加一条明细，不是改两条自动源 -> %s" % n)
    row = psql("SELECT value||'/'||coalesce(ref,'NULL') FROM valuation_inputs "
               "WHERE valuation_id=%d AND source='MANUAL_OVERRIDE'" % vid)
    ok(row.startswith("1500000/REASON:"), "明细 ref 带 REASON: 前缀（审计一眼区分理由与源侧引用号）-> %s" % row)
    st, res = call("GET", VAL + "/active?vin=" + VIN, token=rec)
    ok(D(res).get("prudentValue") == 1500000 and D(res).get("consistentWithInputs") is True,
       "覆盖后主表与明细仍同源可复算（D11 A6）-> %s/%s"
       % (D(res).get("prudentValue"), D(res).get("recalculatedPrudentValue")))
    st, res = call("POST", VAL + "/%d/override" % vid, {
        "manualValue": 1450000, "reason": "同日第二次覆盖，用来验明细唯一键的静默丢弃防线", "coSignedBy": "admin"},
        token=ovr)
    ok(st == 409 and res.get("code") == "CONFLICT_001",
       "同日重复覆盖被拒（uk_val_input 不含 rank，放行则第二条被 DO NOTHING 丢掉 ⟹ 主表新值、明细旧值）-> %s"
       % res.get("code"))

    # ---- 人工置失效 ----
    st, res = call("POST", VAL + "/%d/expire" % vid, {"reason": "查定机构撤回本报告，基准不可再用"}, token=rec)
    ok(st == 200 and D(res).get("status") == "EXPIRED" and D(res).get("previousStatus") == "ACTIVE",
       "人工置失效 → 200 EXPIRED（前态一并回显）-> %s" % st)
    st, res = call("GET", VAL + "/active?vin=" + VIN, token=rec)
    ok(st == 404 and res.get("code") == "NOT_FOUND_001",
       "失效后该车没有基准 → 404（闸门据此拒付，而不是拿一条已撤回的结论放款）")
    st, res = call("POST", VAL + "/%d/expire" % vid, {"reason": "再失效一次看看状态机"}, token=rec)
    ok(st == 422 and res.get("code") == "VALU_002",
       "终态不可逆：EXPIRED 再失效 → 422 VALU_002（D03 §3.7 没有 EXPIRED→ACTIVE 这条边，要基准请重录）")

    # ---- 参数真的读自库，不是 Java 常量 ----
    def rec_with(ref, **kw):
        return call("POST", VAL, {"vin": VIN, "purpose": "ADVANCE_BASE", "sources": [
            {"source": "AUCTION_WON_PRICE", "value": 1800000, "provider": "USS", "ref": ref},
            {"source": "THIRD_PARTY_APPRAISAL", "value": 1600000, "provider": "GOS", "ref": ref + "-A"}], **kw},
            token=rec)

    saved = psql("SELECT param_value FROM used_config_params WHERE param_key='%s' AND status='ACTIVE'" % KEY_VALID_DAYS)
    ok(saved is not None, "有效期键在 used_config_params 里（V79 已落库）-> %s" % saved)
    psql("UPDATE used_config_params SET param_value='30' WHERE param_key='%s' AND status='ACTIVE'" % KEY_VALID_DAYS)
    st, res = rec_with("LOT-D30")
    ok(st == 201 and D(res).get("validUntil") == d(30),
       "valid-days 调成 30 → 同一次录入的有效期跟着变 -> %s" % D(res).get("validUntil"))
    st, res = rec_with("LOT-GIVEN", validUntil=d(200))
    ok(st == 201 and D(res).get("validUntil") == d(200),
       "请求显式给 valid_until 时以请求为准（配置键是缺省值，不是强制值）-> %s" % D(res).get("validUntil"))
    psql("UPDATE used_config_params SET param_value='45' WHERE param_key='%s' AND status='ACTIVE'" % KEY_VALID_DAYS)
    st, res = rec_with("LOT-D45")
    ok(st == 201 and D(res).get("validUntil") == d(45),
       "改回 45 立刻生效（营业中可调参数，不用发版）-> %s" % D(res).get("validUntil"))
    psql("UPDATE used_config_params SET param_value='0' WHERE param_key='%s' AND status='ACTIVE'" % KEY_VALID_DAYS)
    st, res = rec_with("LOT-ZERO")
    ok(st == 400 and res.get("code") == "VALID_001",
       "valid-days 脏成 0 且不传 valid_until → 拒绝，不退回隐式 45 天（有效期必须有出处）-> %s/%s"
       % (st, res.get("code")))
    psql("UPDATE used_config_params SET param_value='%s' WHERE param_key='%s' AND status='ACTIVE'"
         % (saved or "45", KEY_VALID_DAYS))

    psql("UPDATE used_config_params SET param_value='WEIGHTED' WHERE param_key='%s' AND status='ACTIVE'" % KEY_RULE)
    st, res = rec_with("LOT-WT")
    ok(st == 201 and D(res).get("prudentRule") == "WEIGHTED"
       and D(res).get("prudentValue") == 1700000,
       "算法键改成 WEIGHTED → 等权退化 (1,800,000+1,600,000)/2 = 1,700,000（同一段代码，值来自库）-> %s/%s"
       % (D(res).get("prudentRule"), D(res).get("prudentValue")))
    ok(D(res).get("weightedApplied") is True, "加权时如实标 weightedApplied=true")
    psql("UPDATE used_config_params SET param_value='NOT_A_RULE' WHERE param_key='%s' AND status='ACTIVE'" % KEY_RULE)
    st, res = rec_with("LOT-BAD-RULE")
    ok(st == 400 and res.get("code") == "VALID_001",
       "算法键脏成非法词 → 400 fail-closed，不悄悄退回 MIN -> %s/%s" % (st, res.get("code")))
    psql("UPDATE used_config_params SET param_value='MIN_OF_SOURCES' WHERE param_key='%s' AND status='ACTIVE'"
         % KEY_RULE)
    st, res = rec_with("LOT-UPL0")
    ok(st == 201, "覆盖上限实验的基准估值录入 -> %s" % st)
    vid3 = D(res).get("valuationId")
    psql("UPDATE used_config_params SET param_value='0' WHERE param_key='%s' AND status='ACTIVE'" % KEY_UPLIFT)
    if vid3 is not None:
        st, res2 = call("POST", VAL + "/%d/override" % vid3, {
            "manualValue": 1600000, "reason": ok_reason, "coSignedBy": "admin"}, token=ovr)
        ok(st == 400 and res2.get("code") == "VALID_001",
           "覆盖上限键脏成 0 → 拒绝覆盖（上限不可知时「不限制」是最危险的解释，硬约束不许软掉）-> %s/%s"
           % (st, res2.get("code")))
    psql("UPDATE used_config_params SET param_value='1.15' WHERE param_key='%s' AND status='ACTIVE'" % KEY_UPLIFT)

    # 刻意不种的键：两条「可配置绕过」必须一直读不到值（AGENTS §8 的软放行形态）。
    for bypass in ("valuation.require-second-source-for-advance", "valuation.default-max-ratio-fallback"):
        cnt = psql("SELECT count(*) FROM used_config_params WHERE param_key LIKE '%%%s%%'" % bypass)
        ok(cnt == "0", "参数台账里没有 %s（单源不可作基准、无政策不运行，都是已裁决硬策略）-> %s" % (bypass, cnt))
    for k in (KEY_VALID_DAYS, KEY_UPLIFT, KEY_RULE):
        cnt = psql("SELECT count(*) FROM used_config_params WHERE param_key='%s' AND status='ACTIVE'" % k)
        ok(cnt == "1", "R3 三枚参数键各有且只有一行 ACTIVE（多行会让 findActiveValue 取到哪条看运气）-> %s=%s" % (k, cnt))

    audited = psql("SELECT count(*) FROM audit_events WHERE target_type='VEHICLE_VALUATION' AND target_id='%s'" % VIN)
    ok(int(audited or 0) >= 6, "估值/覆盖/失效逐笔留痕 -> %s 条" % audited)
    for action in ("VALUATION_RECORD", "VALUATION_OVERRIDE", "VALUATION_EXPIRE"):
        c = psql("SELECT count(*) FROM audit_events WHERE action='%s' AND target_id='%s'" % (action, VIN))
        ok(int(c or 0) >= 1, "%s 有留痕 -> %s 条" % (action, c))
    for action in ("LTV_POLICY_CREATE", "LTV_POLICY_BANDS", "LTV_POLICY_APPROVE"):
        c = psql("SELECT count(*) FROM audit_events WHERE action='%s' AND target_id='%d'" % (action, pid))
        ok(int(c or 0) >= 1, "%s 有留痕（三代际动作分开记，审计才分得清哪一次是定案）-> %s 条" % (action, c))
    sod = psql("SELECT count(*) FROM audit_events WHERE action='SOD_VIOLATION_BLOCKED'")
    ok(sod == "0",
       "SOD 留痕 0 条＝本轮没触到「同一自然人」分支：当前角色矩阵下 MANAGE 与 APPROVE、RECORD 与 OVERRIDE "
       "都不并列，所以换人校验只可能以 403 AUTH_002 出现；同人分支由单测覆盖，缺口已登记 PROGRESS §4 -> %s 条" % sod)


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
    r3()

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
