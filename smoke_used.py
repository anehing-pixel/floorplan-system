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
  后续切片（U-R2 车辆/車検証、U-R3 估值分档、U-R4 落札、U-R5 放款闸门、
  U-R6 WMS 账实、U-R7 放车放证、U-R8 回款回补）在此文件里按同样的 ok() 断言追加。

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
    "admin":    ("admin123",    "SYSTEM_ADMIN"),      # 持 DEALER_* 全套（含 DEALER_COMPLIANCE_CHECK）
    "credit":   ("credit123",   "CREDIT_REVIEWER"),   # 持资质维护，但刻意不持合规核查
    "operator": ("operator123", "BUSINESS_OPERATOR"),  # 既无资质写权也无读权之外的部分
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
