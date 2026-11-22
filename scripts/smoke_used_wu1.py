#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""W-U1（USED R1 车商资质与准入）冒烟脚本。

判据口径（AGENTS §5）：每一次写都必须回库读回来断言，HTTP 200 只当作"请求送达"的信号，
不当作结论。库侧统一走 agent_smoke_lib.psql()，它按 FLOORPLAN_DB_SCHEMA 挂
search_path=<schema>,public，和 web 进程的 currentSchema 是同一个 schema。

覆盖 D07 §7.1 的八个端点（全部 /api/dealers/{id}/...）：
  1 POST licenses              2 GET licenses
  3 POST auction-memberships   4 GET auction-memberships?status=
  5 POST compliance-checks     6 POST scores
  7 GET admission              8 POST admission/recalculate

两个 phase：
  gateoff —— 只验 P-3 配置闸门关着时"接口不可达且不写库"（web 不带 FLOORPLAN_USED_ENABLED 起）。
  full    —— 闸门开着跑八个端点的语义验证（默认）。

用法（必须与起服务在同一条命令里，Git Bash 下后台 JVM 会随父 shell 回收）：
  SMOKE_WEB=http://127.0.0.1:8780 SMOKE_AUTH=http://127.0.0.1:8781 \
  SMOKE_GATEWAY=http://127.0.0.1:18780 \
  FLOORPLAN_DB_SCHEMA=used_wu1 PYTHONIOENCODING=utf-8 python scripts/smoke_used_wu1.py full
"""
import datetime
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import agent_smoke_lib as L
from agent_smoke_lib import ok, assert_eq, assert_fail, psql, psql_one, psql_exec, summary

PHASE = (sys.argv[1] if len(sys.argv) > 1 else "full")
WEB = os.environ.get("SMOKE_WEB", "http://127.0.0.1:8780")
GW = os.environ.get("SMOKE_GATEWAY", "http://127.0.0.1:18780")
# 登录只能走 auth-service：/api/auth/login 在该仓里只有 auth-service 提供，
# web 单体没有这个控制器（实查：POST 8780/api/auth/login → 404 NOT_FOUND_001）。
# 两边 floorplan.auth.secret 用同一个 dev 兜底值，所以 auth 出的令牌能被 web 校验。
AUTH = os.environ.get("SMOKE_AUTH", "http://127.0.0.1:8781")
# 功能断言直连 web：本包的八个端点在 web(core)，而网关把 /api/dealers/** 整体给了
# credit-service（见下方网关探测那条断言，它就是用来把这件事打进结果的）。
L.BASE = WEB

RUN_MARK = "SMOKE-WU1"
TODAY = datetime.date.today()
DEALER = None


def code_of(res):
    """响应体的业务错误码（VALID_001 / CONFLICT_001 / QUAL_001 …）。"""
    return res.get("code") or ""


def data_of(res):
    return res.get("data") or {}


def lic(dealer_id, body):
    return L.post("/api/dealers/%s/licenses" % dealer_id, body)


def member(dealer_id, body):
    return L.post("/api/dealers/%s/auction-memberships" % dealer_id, body)


def check(dealer_id, body):
    return L.post("/api/dealers/%s/compliance-checks" % dealer_id, body)


def score(dealer_id, body, key=None):
    # 只有 call() 暴露 headers（post() 的签名是 path/body/base/token），
    # Idempotency-Key 必须走请求头，所以这里直接用 call()。
    return L.call("POST", "/api/dealers/%s/scores" % dealer_id, body,
                  headers={"Idempotency-Key": key} if key else None)


def admission(dealer_id):
    return L.get("/api/dealers/%s/admission" % dealer_id)


def readmit(dealer_id, reason):
    return L.post("/api/dealers/%s/admission/recalculate" % dealer_id, {"reason": reason})


def db(sql):
    """取一个标量并把 NULL 翻成字符串 'NULL'，避免 None 和空列表混淆。"""
    v = psql_one(sql)
    return "NULL" if v is None else v


def setup():
    """库内构造一个干净车商（AGENTS 允许脚本内 psql 造数，生产代码不得造数）。

    先把同一 dealer_code 的旧车商连同其 USED 行整体删掉再建——本脚本要能反复跑：
    上一轮崩溃留下的行会把后面的"库里只有一行"这类计数断言全带偏。
    """
    global DEALER
    old = psql_one("SELECT id FROM dealers WHERE dealer_code='%s'" % RUN_MARK)
    if old:
        oid = int(old)
        for tbl in ("dealer_licenses", "dealer_auction_memberships", "dealer_compliance_checks",
                    "dealer_score_reviews", "alerts"):
            psql_exec("DELETE FROM %s WHERE dealer_id=%d" % (tbl, oid))
        psql_exec("DELETE FROM audit_events WHERE target_id='%d' "
                  "AND target_type IN ('DEALER','DEALER_LICENSE','DEALER_MEMBERSHIP',"
                  "'DEALER_COMPLIANCE','DEALER_SCORE_REVIEW')" % oid)
        psql_exec("DELETE FROM dealers WHERE id=%d" % oid)
    psql_exec("INSERT INTO dealers (dealer_code, name) VALUES ('%s', '冒烟车商WU1')" % RUN_MARK)
    DEALER = int(psql_one("SELECT id FROM dealers WHERE dealer_code='%s'" % RUN_MARK))
    return DEALER


def gateoff():
    """闸门关闭：八个端点必须一律拒绝，且库里一行都不许多。"""
    L.login(base=AUTH)
    ok(bool(L.TOKEN), "gateoff: auth-service 登录取到令牌")
    setup()
    before = db("SELECT count(*)::text FROM dealer_licenses WHERE dealer_id=%d" % DEALER)
    st, res = lic(DEALER, {"licenseType": "FURIGUSHO", "licenseNo": "%s-GATEOFF" % RUN_MARK,
                           "competentAuthority": "東京都公安委員会",
                           "issuedOn": str(TODAY - datetime.timedelta(days=30)),
                           "expiresOn": str(TODAY + datetime.timedelta(days=365))})
    ok(st == 400 and code_of(res) == "VALID_001",
       "gateoff: 闸门关闭时写接口回 400 VALID_001", "实得 st=%s code=%s" % (st, code_of(res)))
    ok((res.get("details") or {}).get("reason") == "USED_FEATURE_DISABLED",
       "gateoff: 拒绝原因是 USED_FEATURE_DISABLED（不是参数错）",
       "details=%s" % res.get("details"))
    st2, _ = admission(DEALER)
    ok(st2 == 400, "gateoff: 读接口同样被闸门拦住（P-3 是读写双闸，不是只挡写）", "实得 st=%s" % st2)
    after = db("SELECT count(*)::text FROM dealer_licenses WHERE dealer_id=%d" % DEALER)
    assert_eq("gateoff: 被拒的写入没有落库", before, after)


def gateway_probe():
    """网关侧只探可达性：结论无论红绿都进报告，不改判据、也不绕过网关再假装跑过。"""
    st, res = L.call("POST", "/api/auth/login", {"username": "admin", "password": "admin123"},
                     token="", base=GW)
    gw_token = (res.get("data") or {}).get("token") or ""
    ok(st == 200 and bool(gw_token), "gateway: 18780 登录可用（auth-service 路由正常）",
       "st=%s body=%s" % (st, str(res)[:200]))
    st2, res2 = L.call("GET", "/api/dealers/%d/admission" % (DEALER or 0), token=gw_token, base=GW)
    ok(st2 == 200, "gateway: 本包 USED R1 端点经 18780 可达",
       "实得 st=%s code=%s body=%s —— /api/dealers/** 被网关 credit 路由整段接管"
       "（gateway/application.yml:49-52，D07:523 已登记该冲突，改谁属跨包裁决）"
       % (st2, code_of(res2), str(res2)[:200]))


def test_licenses():
    body = {"licenseType": "FURIGUSHO", "licenseNo": "%s-A001" % RUN_MARK,
            "competentAuthority": "東京都公安委員会",
            "issuedOn": str(TODAY - datetime.timedelta(days=30)),
            "expiresOn": str(TODAY + datetime.timedelta(days=365)),
            "evidencePath": "/evidence/a001.pdf", "evidenceDigest": "sha256:aaaa",
            "verifiedBy": "担当A", "verifiedAt": "%sT09:30:00Z" % TODAY}
    st, res = lic(DEALER, body)
    ok(st == 200, "R1-1 许可新增返回 200", "st=%s %s" % (st, str(res)[:200]))
    lid = data_of(res).get("id")
    assert_eq("R1-1 库里确有这一行", "1",
              db("SELECT count(*)::text FROM dealer_licenses WHERE id=%s" % lid))
    assert_eq("R1-1 状态落库为 ACTIVE", "ACTIVE",
              db("SELECT status FROM dealer_licenses WHERE id=%s" % lid))
    assert_eq("R1-1 dealers 摘要投影同步刷新（古物商番号）", "%s-A001" % RUN_MARK,
              db("SELECT furigusho_no FROM dealers WHERE id=%d" % DEALER))
    assert_eq("R1-1 审计有 DEALER_LICENSE_UPSERTED", "1",
              db("SELECT count(*)::text FROM audit_events WHERE action='DEALER_LICENSE_UPSERTED' "
                 "AND target_id='%s'" % lid))

    st2, res2 = lic(DEALER, body)
    assert_eq("R1-1 同键同指纹重放返回同一行 id", str(lid), str(data_of(res2).get("id")))
    assert_eq("R1-1 重放不产生第二行", "1",
              db("SELECT count(*)::text FROM dealer_licenses WHERE dealer_id=%d "
                 "AND license_no='%s-A001'" % (DEALER, RUN_MARK)))
    assert_eq("R1-1 重放不产生第二条审计", "1",
              db("SELECT count(*)::text FROM audit_events WHERE action='DEALER_LICENSE_UPSERTED' "
                 "AND target_id='%s'" % lid))

    fixed = dict(body, expiresOn=str(TODAY + datetime.timedelta(days=700)),
                 evidenceDigest="sha256:bbbb")
    st3, res3 = lic(DEALER, fixed)
    assert_eq("R1-1 订正命中同一行（不换号不新增）", str(lid), str(data_of(res3).get("id")))
    assert_eq("R1-1 订正真的写进库", str(TODAY + datetime.timedelta(days=700)),
              db("SELECT expires_on::text FROM dealer_licenses WHERE id=%s" % lid))
    assert_eq("R1-1 订正写 DEALER_LICENSE_CORRECTED 审计", "1",
              db("SELECT count(*)::text FROM audit_events WHERE action='DEALER_LICENSE_CORRECTED' "
                 "AND target_id='%s'" % lid))

    new_no = dict(body, licenseNo="%s-B002" % RUN_MARK,
                  evidenceDigest="sha256:cccc")
    st4, res4 = lic(DEALER, new_no)
    nid = data_of(res4).get("id")
    assert_eq("R1-1 换证：新行 ACTIVE", "ACTIVE",
              db("SELECT status FROM dealer_licenses WHERE id=%s" % nid))
    assert_eq("R1-1 换证：旧行置 SUPERSEDED", "SUPERSEDED",
              db("SELECT status FROM dealer_licenses WHERE id=%s" % lid))
    assert_eq("R1-1 uk_dealer_license_active：同类型 ACTIVE 只有一条", "1",
              db("SELECT count(*)::text FROM dealer_licenses WHERE dealer_id=%d "
                 "AND license_type='FURIGUSHO' AND status='ACTIVE'" % DEALER))

    st5, res5 = lic(DEALER, {"licenseType": "DRIVER", "licenseNo": "X-1"})
    ok(st5 == 400 and code_of(res5) == "VALID_001", "R1-1 非枚举许可类型被入口拦下",
       "st=%s code=%s" % (st5, code_of(res5)))
    assert_eq("R1-1 非法类型的请求没有落库", "2",
              db("SELECT count(*)::text FROM dealer_licenses WHERE dealer_id=%d" % DEALER))

    st6, rows = L.get("/api/dealers/%d/licenses" % DEALER)
    ok(st6 == 200 and len(rows.get("data") or []) == 2,
       "R1-2 GET 许可清单返回 ACTIVE+SUPERSEDED 两行（换证链在接口上连着）",
       "st=%s len=%s" % (st6, len(rows.get("data") or [])))
    st7, _ = lic(999999999, body)
    ok(st7 == 404, "R1-1 车商不存在回 404，不做静默建账", "st=%s" % st7)


def test_memberships():
    body = {"auctionHouseCode": "TAA", "memberNo": "%s-M01" % RUN_MARK,
            "memberGrade": "A", "joinedOn": str(TODAY - datetime.timedelta(days=100)),
            "expiresOn": str(TODAY + datetime.timedelta(days=265)),
            "evidencePath": "/evidence/taa.pdf"}
    st, res = member(DEALER, body)
    ok(st == 200, "R1-3 会员关系新增 200", "st=%s %s" % (st, str(res)[:200]))
    mid = data_of(res).get("id")
    assert_eq("R1-3 会员行落库且状态 ACTIVE", "ACTIVE",
              db("SELECT status FROM dealer_auction_memberships WHERE id=%s" % mid))
    st2, res2 = member(DEALER, body)
    assert_eq("R1-3 同自然键同指纹重放不换行", str(mid), str(data_of(res2).get("id")))
    assert_eq("R1-3 uk_dmem_dealer_house_no：同场同员号只有一行", "1",
              db("SELECT count(*)::text FROM dealer_auction_memberships WHERE dealer_id=%d "
                 "AND auction_house_code='TAA' AND member_no='%s-M01'" % (DEALER, RUN_MARK)))
    st3, res3 = member(DEALER, dict(body, memberGrade="S"))
    assert_eq("R1-3 等级订正写进同一行", "S",
              db("SELECT member_grade FROM dealer_auction_memberships WHERE id=%s" % mid))
    assert_eq("R1-3 订正没多出第二行", "1",
              db("SELECT count(*)::text FROM dealer_auction_memberships WHERE dealer_id=%d" % DEALER))
    st4, rows4 = L.get("/api/dealers/%d/auction-memberships?status=ACTIVE" % DEALER)
    ok(st4 == 200 and len(rows4.get("data") or []) == 1,
       "R1-3 GET status=ACTIVE 过滤返回 1 行", "st=%s len=%s" % (st4, len(rows4.get("data") or [])))
    before = db("SELECT count(*)::text FROM dealer_auction_memberships WHERE dealer_id=%d" % DEALER)
    st5, res5 = L.get("/api/dealers/%d/auction-memberships?status=NOT_A_STATUS" % DEALER)
    ok(st5 == 400 and code_of(res5) == "VALID_001",
       "R1-3 非法状态过滤回 400，而不是静默给空列表", "st=%s code=%s" % (st5, code_of(res5)))
    assert_eq("R1-3 非法过滤未改库", before,
              db("SELECT count(*)::text FROM dealer_auction_memberships WHERE dealer_id=%d" % DEALER))


def test_compliance():
    rn = "%s-CHK-1" % RUN_MARK
    body = {"checkType": "ANTI_SOCIAL", "checkedOn": str(TODAY), "result": "PASS",
            "source": "MANUAL", "requestNo": rn, "evidencePath": "/evidence/anti.pdf",
            "note": "冒烟", "checkedBy": "担当B"}
    st, res = check(DEALER, body)
    ok(st == 200, "R1-4 合规核查录入 200", "st=%s %s" % (st, str(res)[:200]))
    cid = data_of(res).get("id")
    assert_eq("R1-4 核查行落库", "PASS",
              db("SELECT result FROM dealer_compliance_checks WHERE id=%s" % cid))
    # D-02（结论有效天数）未签字 ⟹ 服务端不猜：valid_until 必须留在库里为 NULL。
    assert_eq("R1-4 valid_until 未被硬编码天数填满（D-02 未签，fail-closed）", "NULL",
              db("SELECT coalesce(valid_until::text,'NULL') FROM dealer_compliance_checks WHERE id=%s" % cid))
    assert_eq("R1-4 审计动作 COMPLIANCE_CHECK_DONE 落库", "1",
              db("SELECT count(*)::text FROM audit_events WHERE action='COMPLIANCE_CHECK_DONE' "
                 "AND target_id='%s'" % cid))
    st2, res2 = check(DEALER, body)
    assert_eq("R1-4 报文号重放同结论＝返回首行", str(cid), str(data_of(res2).get("id")))
    st3, res3 = check(DEALER, dict(body, result="FAIL"))
    ok(st3 == 409 and code_of(res3) == "CONFLICT_001",
       "R1-4 同 requestNo 换结论＝409，不覆盖历史结论", "st=%s code=%s" % (st3, code_of(res3)))
    assert_eq("R1-4 uk_dchk_request_no：报文号仍只有一行", "1",
              db("SELECT count(*)::text FROM dealer_compliance_checks WHERE request_no='%s'" % rn))
    st4, res4 = check(DEALER, dict(body, checkType="NOT_A_TYPE", requestNo=None))
    ok(st4 == 400 and code_of(res4) == "VALID_001",
       "R1-4 非法核查种类被入口拦下（R13.4 双层约束的应用层）", "st=%s code=%s" % (st4, code_of(res4)))
    assert_eq("R1-4 非法种类未落库", "1",
              db("SELECT count(*)::text FROM dealer_compliance_checks WHERE dealer_id=%d" % DEALER))


def test_scores():
    key1 = "%s-K1" % RUN_MARK
    op = {"dimension": "OPERATING", "score": 70, "reviewYear": 2026,
          "evidencePath": "/evidence/score.pdf", "note": "经营资质"}
    st, res = score(DEALER, op, key1)
    ok(st == 200, "R1-5 单维评分录入 200", "st=%s %s" % (st, str(res)[:200]))
    rid = data_of(res).get("id")
    assert_eq("R1-5 候选行状态 PENDING", "PENDING",
              db("SELECT status FROM dealer_score_reviews WHERE id=%s" % rid))
    assert_eq("R1-5 维度分写进候选行", "70",
              db("SELECT operating_score::text FROM dealer_score_reviews WHERE id=%s" % rid))
    # G-A4：候选值不得回写 dealers，只有第二人确认后的终值才允许。
    assert_eq("R1-5 候选分未回写 dealers.operating_score", "NULL",
              db("SELECT coalesce(operating_score::text,'NULL') FROM dealers WHERE id=%d" % DEALER))
    st2, res2 = score(DEALER, op, key1)
    assert_eq("R1-5 同键同指纹重放返回同一候选行", str(rid), str(data_of(res2).get("id")))
    assert_eq("R1-5 uk_dsr_idem：幂等键只对应一行", "1",
              db("SELECT count(*)::text FROM dealer_score_reviews WHERE idempotency_key='%s'" % key1))
    before = db("SELECT count(*)::text FROM dealer_score_reviews WHERE dealer_id=%d" % DEALER)
    st3, res3 = score(DEALER, {"dimension": "STOCK_QUALITY", "score": 60, "reviewYear": 2026}, key1)
    ok(st3 == 409 and (res3.get("details") or {}).get("reason") == "SAME_KEY_DIFFERENT_REQUEST",
       "R1-5 同键换请求体＝409 SAME_KEY_DIFFERENT_REQUEST", "st=%s code=%s" % (st3, code_of(res3)))
    assert_eq("R1-5 被拒的换体请求未新增行", before,
              db("SELECT count(*)::text FROM dealer_score_reviews WHERE dealer_id=%d" % DEALER))
    st4, res4 = score(DEALER, {"dimension": "STOCK_QUALITY", "score": 60, "reviewYear": 2026},
                      "%s-K2" % RUN_MARK)
    assert_eq("R1-5 第二维并进同一 PENDING 候选行（uk_dsr_pending）", str(rid),
              str(data_of(res4).get("id")))
    assert_eq("R1-5 两维都在候选行上", "60",
              db("SELECT stock_quality_score::text FROM dealer_score_reviews WHERE id=%s" % rid))
    assert_eq("R1-5 该车商该年度仍只有一条 PENDING", "1",
              db("SELECT count(*)::text FROM dealer_score_reviews WHERE dealer_id=%d "
                 "AND review_year=2026 AND status='PENDING'" % DEALER))
    st5, res5 = score(DEALER, {"dimension": "OPERATING", "score": 80})
    ok(st5 == 400 and (res5.get("details") or {}).get("reason") == "REVIEW_YEAR_REQUIRED",
       "R1-5 缺评审年度不猜年度（400 REVIEW_YEAR_REQUIRED）", "st=%s details=%s" % (st5, res5.get("details")))
    st6, res6 = score(DEALER, {"dimension": "NOT_A_DIM", "score": 1, "reviewYear": 2026})
    ok(st6 == 400 and code_of(res6) == "VALID_001", "R1-5 非法维度回 400", "st=%s" % st6)
    assert_eq("R1-5 两次非法请求未落库", before,
              db("SELECT count(*)::text FROM dealer_score_reviews WHERE dealer_id=%d" % DEALER))


def test_admission():
    st, res = readmit(DEALER, "SMOKE_NO_COMPLIANCE")
    ok(st == 200, "R1-6 准入重算返回 200", "st=%s %s" % (st, str(res)[:200]))
    # 许可 ACTIVE，但四类核查只有 ANTI_SOCIAL 一条，且 valid_until 为 NULL ⟹ 仍判合规阻断。
    assert_eq("R1-6 缺核查＝BLOCKED_COMPLIANCE（P-7 同源不放过）", "BLOCKED_COMPLIANCE",
              data_of(res).get("admissionState"))
    assert_eq("R1-6 缺核查的错误码是 D07 §7.1 的 QUAL_003", "QUAL_003",
              data_of(res).get("errorCode"))
    assert_eq("R1-6 结论落库进 dealers.admission_state", "BLOCKED_COMPLIANCE",
              db("SELECT admission_state FROM dealers WHERE id=%d" % DEALER))
    assert_eq("R1-6 重算写审计 DEALER_ADMISSION_RECALCULATED", "1",
              db("SELECT count(*)::text FROM audit_events WHERE action='DEALER_ADMISSION_RECALCULATED' "
                 "AND target_type='DEALER' AND target_id='%d'" % DEALER))

    st2, res2 = admission(DEALER)
    ok(st2 == 200, "R1-6 GET 准入判定 200", "st=%s" % st2)
    d2 = data_of(res2)
    assert_eq("R1-6 GET 的现算结论与库里存量一致", "True", str(d2.get("storedMatchesComputed")))
    assert_eq("R1-6 预警窗口天数未签字（expiringWindowSigned=false）", False,
              bool(d2.get("expiringWindowSigned")))
    assert_eq("R1-6 参数表零种子（未签字值不预置）", "0",
              db("SELECT count(*)::text FROM used_config_params"))

    # 补齐四类核查，且由调用方显式给 valid_until（配置缺省时"以入参为准"，见 V45 列注释）
    until = str(TODAY + datetime.timedelta(days=365))
    for t in ("ANTI_SOCIAL", "AML", "IDENTITY", "CREDIT_BUREAU"):
        stc, resc = check(DEALER, {"checkType": t, "checkedOn": str(TODAY), "validUntil": until,
                                   "result": "PASS", "source": "MANUAL",
                                   "requestNo": None, "checkedBy": "担当B"})
        if stc != 200:
            assert_fail("R1-6 补齐核查 %s 失败" % t, "st=%s %s" % (stc, str(resc)[:200]))
    st3, res3 = readmit(DEALER, "SMOKE_ADMIT")
    assert_eq("R1-6 四类核查齐且许可有效＝ADMITTED", "ADMITTED", data_of(res3).get("admissionState"))
    assert_eq("R1-6 ADMITTED 已落库", "ADMITTED",
              db("SELECT admission_state FROM dealers WHERE id=%d" % DEALER))

    # GET 只算不落：手工把存量改成 BLOCKED_LICENSE，GET 必须报"不一致"而不是偷偷改库。
    psql_exec("UPDATE dealers SET admission_state='BLOCKED_LICENSE' WHERE id=%d" % DEALER)
    st4, res4 = admission(DEALER)
    d4 = data_of(res4)
    assert_eq("R1-6 GET 不改库（存量仍是人工置的值）", "BLOCKED_LICENSE",
              db("SELECT admission_state FROM dealers WHERE id=%d" % DEALER))
    assert_eq("R1-6 GET 如实报出存量与现算不一致", False, bool(d4.get("storedMatchesComputed")))

    # 许可过期：走 API 订正到期日到过去，再重算 ⟹ BLOCKED_LICENSE + 预警工单
    expired = {"licenseType": "FURIGUSHO", "licenseNo": "%s-B002" % RUN_MARK,
               "competentAuthority": "東京都公安委員会",
               "issuedOn": str(TODAY - datetime.timedelta(days=400)),
               "expiresOn": str(TODAY - datetime.timedelta(days=1)),
               "evidenceDigest": "sha256:expired"}
    ste, rese = lic(DEALER, expired)
    ok(ste == 200, "R1-6 许可到期日订正成功（为过期分支造数据）", "st=%s %s" % (ste, str(rese)[:200]))
    st5, res5 = readmit(DEALER, "SMOKE_EXPIRED")
    assert_eq("R1-6 许可过期＝BLOCKED_LICENSE", "BLOCKED_LICENSE", data_of(res5).get("admissionState"))
    assert_eq("R1-6 过期分支产出资质到期预警工单", "1",
              db("SELECT count(*)::text FROM alerts WHERE dealer_id=%d "
                 "AND alert_type='DEALER_QUALIFICATION_LAPSED'" % DEALER))


def test_legacy_untouched():
    """V40 的两条 CHECK 必须放得过 LEGACY 行（分流键可空、渠道仍是 OEM）。"""
    vin = "%s-VIN1" % RUN_MARK
    ok(psql_exec("INSERT INTO vehicle_units (vin, dealer_id, invoice_amount, advance_amount, "
                 "principal_balance, paid_principal, accrued_interest, aging_days, status, "
                 "overdue_days, oot_suspected, audit_mismatch_count, purchase_channel) "
                 "VALUES ('%s', %d, 1000000, 800000, 800000, 0, 0, 0, 'ACTIVE', 0, false, 0, 'OEM')"
                 % (vin, DEALER)),
       "LEGACY：vehicle_type 不填（NULL）仍能插入，V40 CHECK 未伤及存量口径")
    assert_eq("LEGACY：新行 vehicle_type 为 NULL", "NULL",
              db("SELECT coalesce(vehicle_type,'NULL') FROM vehicle_units WHERE vin='%s'" % vin))
    bad = psql_exec("INSERT INTO vehicle_units (vin, dealer_id, invoice_amount, advance_amount, "
                    "principal_balance, paid_principal, accrued_interest, aging_days, status, "
                    "overdue_days, oot_suspected, audit_mismatch_count, vehicle_type) "
                    "VALUES ('%s-BAD', %d, 1, 1, 1, 0, 0, 0, 'ACTIVE', 0, false, 0, 'TRUCK')"
                    % (vin, DEALER))
    # psql_exec 只看命令是否返回文本：约束违例时 psql 仍返回空串→判成功，
    # 因此结论以库里有没有这行为准（AGENTS §5：判据是库状态）。
    assert_eq("分流键 CHECK 拦住非法 vehicle_type（库里无此行）", "0",
              db("SELECT count(*)::text FROM vehicle_units WHERE vin='%s-BAD'" % vin))
    ok(psql_exec("INSERT INTO vehicle_units (vin, dealer_id, invoice_amount, advance_amount, "
                 "principal_balance, paid_principal, accrued_interest, aging_days, status, "
                 "overdue_days, oot_suspected, audit_mismatch_count, vehicle_type) "
                 "VALUES ('%s-VIN2', %d, 1, 1, 1, 0, 0, 0, 'ACTIVE', 0, false, 0, 'USED')"
                 % (vin, DEALER)),
       "USED：vehicle_type='USED' 可写入（分流键第二值真的进了约束值域）")
    assert_eq("USED 行落库", "USED",
              db("SELECT vehicle_type FROM vehicle_units WHERE vin='%s-VIN2'" % vin))


def cleanup():
    psql_exec("DELETE FROM dealer_licenses WHERE dealer_id=%d" % DEALER)
    psql_exec("DELETE FROM dealer_auction_memberships WHERE dealer_id=%d" % DEALER)
    psql_exec("DELETE FROM dealer_compliance_checks WHERE dealer_id=%d" % DEALER)
    psql_exec("DELETE FROM dealer_score_reviews WHERE dealer_id=%d" % DEALER)
    psql_exec("DELETE FROM alerts WHERE dealer_id=%d" % DEALER)
    psql_exec("DELETE FROM vehicle_units WHERE vin LIKE '%s%%'" % RUN_MARK)
    psql_exec("DELETE FROM audit_events WHERE target_id='%d' "
              "AND target_type IN ('DEALER','DEALER_LICENSE','DEALER_MEMBERSHIP',"
              "'DEALER_COMPLIANCE','DEALER_SCORE_REVIEW')" % DEALER)
    psql_exec("DELETE FROM dealers WHERE id=%d" % DEALER)
    left = db("SELECT count(*)::text FROM dealers WHERE dealer_code='%s'" % RUN_MARK)
    assert_eq("清理：冒烟车商已删除", "0", left)


def full():
    L.login(base=AUTH)
    ok(bool(L.TOKEN), "full: auth-service(8781) 登录可用")
    st_web_login, res_web_login = L.call("POST", "/api/auth/login",
                                         {"username": "admin", "password": "admin123"},
                                         token="", base=WEB)
    print("[现状登记] web 单体 %s/api/auth/login → HTTP %s（该仓登录只在 auth-service，"
          "非本包改动引起，不计入断言）" % (WEB, st_web_login))
    setup()
    gateway_probe()
    test_licenses()
    test_memberships()
    test_compliance()
    test_scores()
    test_admission()
    test_legacy_untouched()
    cleanup()


if PHASE == "gateoff":
    gateoff()
else:
    full()
sys.exit(summary("smoke_used_wu1/%s" % PHASE))
