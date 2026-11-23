# -*- coding: utf-8 -*-
"""一次性把第 3 轮实测出的 9 处脚本侧偏差改掉（临时工具，跑完即删，不入库）。"""
import io

P = "scripts/smoke_used_wu3.py"
s = io.open(P, encoding="utf-8").read()
n = 0


def rep(a, b, tag):
    global s, n
    assert s.count(a) == 1, "anchor %s -> %d" % (tag, s.count(a))
    s = s.replace(a, b, 1)
    n += 1


# ---------- 1) 密钥集合加入我方 self partner（出站签名主体） ----------
rep('''def emit_secrets():
    """打印一组 shell export 行（值随机、自造、不进任何 yml/代码/库）。"""
    if os.path.exists(SECRETS_FILE):
        with open(SECRETS_FILE, encoding="utf-8") as f:
            existing = f.read()
        if all(secret_var(p) in existing for p in MY_PARTNERS):
            sys.stdout.write(existing)
            return 0
    lines = []
    for p in MY_PARTNERS:''',
    '''def emit_secrets():
    """打印一组 shell export 行（值随机、自造、不进任何 yml/代码/库）。

    含**我方自己**那把：出站是 DMZ 拿 self partner（floorplan.used.integration.outbound
    .self-partner-code，默认 FLOORPLAN-USED）的凭据签名，少它则每条出站任务在签名一步就
    SIGN_ERROR、一次 HTTP 都发不出去（串行第 3 棒 08:32 实测 G11 整组 15 条红在这上面）。
    """
    if os.path.exists(SECRETS_FILE):
        with open(SECRETS_FILE, encoding="utf-8") as f:
            existing = f.read()
        if all(secret_var(p) in existing for p in SECRET_PARTNERS):
            sys.stdout.write(existing)
            return 0
    lines = []
    for p in SECRET_PARTNERS:''', "1a")

rep('''    missing = [p for p in MY_PARTNERS if not os.environ.get(secret_var(p, 1))]''',
    '''    missing = [p for p in SECRET_PARTNERS if not os.environ.get(secret_var(p, 1))]''', "1b")

rep('''SELF_PARTNER_CODE = "FLOORPLAN-USED"       # 出站 X-Partner-Code（yml 默认值，D07 §4 表头口径）''',
    '''SELF_PARTNER_CODE = "FLOORPLAN-USED"       # 出站 X-Partner-Code（yml 默认值，D07 §4 表头口径）
# 要注入密钥的主体 = 七个对方档 + 我方自己那一个（出站签名用的是我方凭据，见 PartnerHttpSender）
SECRET_PARTNERS = MY_PARTNERS + (SELF_PARTNER_CODE,)''', "1c")

# ---------- 2) G2：PF 的限流值查的是 PF 而不是 PA ----------
rep('''    got = dbj(f"SELECT row_to_json(p) FROM (SELECT allowed_scopes, rate_limit_per_min, endpoint_base, status"
              f" FROM integration.partners WHERE partner_code='{PA}') p")
    L.assert_eq("G2 PA 的 scope 已入库", "WMS_INBOUND_WRITE,RELEASE_RECEIPT_WRITE,MESSAGE_READ",
                (got or {}).get("allowed_scopes"))
    L.assert_eq("G2 PF 的限流值=2（限流断言的前提）", 2, (got or {}).get("rate_limit_per_min"))''',
    '''    got = dbj(f"SELECT row_to_json(p) FROM (SELECT allowed_scopes, rate_limit_per_min, endpoint_base, status"
              f" FROM integration.partners WHERE partner_code='{PA}') p")
    L.assert_eq("G2 PA 的 scope 已入库", "WMS_INBOUND_WRITE,RELEASE_RECEIPT_WRITE,MESSAGE_READ",
                (got or {}).get("allowed_scopes"))
    # 上一版在这里读的是 PA 的行却断言 PF 的值（PA=300），限流断言的前提其实没被钉住。
    gotpf = dbj(f"SELECT row_to_json(p) FROM (SELECT rate_limit_per_min"
                f" FROM integration.partners WHERE partner_code='{PF}') p")
    L.assert_eq("G2 PF 的限流值=2（限流断言的前提）", 2, (gotpf or {}).get("rate_limit_per_min"))''', "2")

# ---------- 3) G2：endpoint_base 留空是代码里写明的设计（空值放行），按实际行为断言 ----------
rep('''    st, rj = core("POST", "/api/partners", token=tok_admin, base=WEB,
                  body={"partnerCode": f"{PA}-nobase", "partnerType": "WMS",
                        "rateLimitPerMin": 10, "receiptSlaHours": 24})
    L.assert_eq("G2 缺 endpoint_base → 400（出站地址必须有来源）", 400, st)''',
    '''    st, rj = core("POST", "/api/partners", token=tok_admin, base=WEB,
                  body={"partnerCode": f"{PA}-nobase", "partnerType": "WMS",
                        "rateLimitPerMin": 10, "receiptSlaHours": 24})
    # requireEndpointBase 的 javadoc 自陈「空值放行：建档时还没谈定通道（D-04 未签）是常态」，
    # 缺地址的出站任务在派发期被延后而不是误发（OutboundDispatchService#defer）。
    # 所以这条按代码实际行为断言：建得成，但没地址=出不了货，后果由 G9 的缺基地址断言兜。
    L.assert_eq("G2 缺 endpoint_base → 200（空值放行是写明设计，后果=该 partner 的出站被派发期延后）",
                200, st)
    L.assert_eq("G2 缺 endpoint_base 的行确实没地址（派发期延前的判据）", None, db1(
        f"SELECT endpoint_base FROM integration.partners WHERE partner_code='{PA}-nobase'"))''', "3")

# ---------- 4) G2：补上我方 self partner 的建档与凭据（出站签名的前提） ----------
rep('''    st, rj = core("POST", f"/api/partners/{PA}/rotate-key", token=tok_key, base=WEB,
                  body={"secretRef": "keystore:smoke/x"})
    L.assert_eq("G2 轮换缺 grace_until → 400（不给默认宽限窗口）", 400, st)''',
    '''    # 出站签名主体：主干迁移只种了 6 个 TEST-* 挡板 partner，没有任何地方建 self partner 行，
    # 干净环境里 DMZ 找不到 self 的 ACTIVE 凭据 → 每条任务 SIGN_ERROR（08:32 实测）。
    # 这里按「配置」而不是「迁移」补：建档 + 轮换 v1，密钥值仍旧走 env 注入。
    # 建过就不重复建，也**不删**（它是环境配置，不是本轮残留；G99 的清理口径不含它）。
    if not db1(f"SELECT 1 FROM integration.partners WHERE partner_code='{esc(SELF_PARTNER_CODE)}'"):
        st, rj = core("POST", "/api/partners", token=tok_admin, base=WEB,
                      body={"partnerCode": SELF_PARTNER_CODE, "partnerName": "本系统（出站签名主体）",
                            "partnerType": "INTERNAL", "protocol": "API", "endpointBase": STUB_BASE,
                            "allowedScopes": "MESSAGE_READ", "rateLimitPerMin": 300,
                            "receiptSlaHours": 24, "status": "ACTIVE"})
        L.assert_eq("G2 我方 self partner 建档 → 200（出站有主体可签）", 200, st)
        st, rj = core("POST", f"/api/partners/{SELF_PARTNER_CODE}/rotate-key", token=tok_key, base=WEB,
                      body={"secretRef": f"keystore:smoke/{SELF_PARTNER_CODE}:v1",
                            "secretFingerprint": sha256_hex(sec(SELF_PARTNER_CODE))[:12],
                            "graceUntil": (datetime.now(timezone.utc)
                                           + timedelta(hours=2)).isoformat(),
                            "reason": "冒烟首版：出站签名主体"})
        L.assert_eq("G2 我方 self 凭据轮换到 v1 → 200", 200, st)
    selfc = dbj("SELECT row_to_json(c) FROM (SELECT key_version, status, secret_fingerprint"
                " FROM integration.partner_credentials WHERE partner_code='"
                + esc(SELF_PARTNER_CODE) + "' AND status='ACTIVE' ORDER BY key_version DESC LIMIT 1) c")
    L.ok(selfc is not None, "G2 出站签名主体有 ACTIVE 凭据（缺它 DMZ 只能报 SIGN_ERROR）", str(selfc))
    if selfc:
        L.assert_eq("G2 self 凭据指纹与注入值同源（库记指纹、env 记原值，两边必须同一把钥）",
                    sha256_hex(sec(SELF_PARTNER_CODE))[:12], selfc.get("secret_fingerprint"))
    st, rj = core("POST", f"/api/partners/{PA}/rotate-key", token=tok_key, base=WEB,
                  body={"secretRef": "keystore:smoke/x"})
    L.assert_eq("G2 轮换缺 grace_until → 400（不给默认宽限窗口）", 400, st)''', "4")

# ---------- 5) G3：UNMATCHED 留痕真实存在，但 trace 被服务侧转小写、partner 记 UNKNOWN ----------
rep('''    L.assert_eq("G3 未注册端点也留痕（endpoint=UNMATCHED）", "1", db1(
        f"SELECT count(*) FROM integration.call_logs WHERE endpoint='UNMATCHED' AND trace_id='{hdr_trace('POST/external/does-not-exist')}'"))''',
    '''    # 实测两处与直觉不同：① filter 把外部 trace_id 统一 toLowerCase（SignatureVerificationFilter:408）；
    # ② 端点没匹配上时 partner_code 记字面量 'UNKNOWN'（还没走到取 partner 的那一步）。
    # 按实际行为取值，而不是按"我以为它该怎么存"。
    L.assert_eq("G3 未注册端点也留痕（endpoint=UNMATCHED，partner 记 UNKNOWN）", "1", db1(
        "SELECT count(*) FROM integration.call_logs WHERE endpoint='UNMATCHED'"
        f" AND trace_id=lower('{hdr_trace('POST/external/does-not-exist')}')"))''', "5")

# ---------- 6) G4：本组真的发了 3 次时间类失败 ----------
rep('''    L.assert_eq("G4 留痕归入 TIMESTAMP_SKEW", "1", db1(
        f"SELECT count(*) FROM integration.call_logs WHERE error_class='TIMESTAMP_SKEW' AND partner_code='{PB}'"))''',
    '''    # 本组对 PB 恰好发了三次时间类失败：过去超窗、未来超窗、非 13 位时间戳。
    # 上一版写死 "1" 是想当然，实测是 3——按真实调用次数断言，多一条少一条都算红。
    L.assert_eq("G4 留痕归入 TIMESTAMP_SKEW（本组三次时间类失败全部归类）", "3", db1(
        f"SELECT count(*) FROM integration.call_logs WHERE error_class='TIMESTAMP_SKEW' AND partner_code='{PB}'"))''', "6")

# ---------- 7) G5：未知版本要用新 nonce，否则先撞 NONCE_REPLAY ----------
rep('''    st, rj, _, _ = dmz("POST", "/external/wms/inbound-confirm", partner=PA, secret=sec(PA),
                       body=inbound_body(23), nonce=n, kv="99")''',
    '''    # nonce 校验在密钥版本之前（SignatureVerifier 的判定顺序），复用上面已消费的 n 会先撞
    # NONCE_REPLAY 而根本走不到 KEY_VERSION_UNKNOWN——用一把新 nonce 才能钉住这一条。
    st, rj, _, _ = dmz("POST", "/external/wms/inbound-confirm", partner=PA, secret=sec(PA),
                       body=inbound_body(23), nonce=new_nonce(), kv="99")''', "7")

# ---------- 8) G7：成功消费不涨 attempts，只有失败才涨 ----------
rep('''    L.assert_eq("G7 处理次数 attempts>0（core 真的消费过）", True,
                (db1(f"SELECT attempts FROM integration.inbound_messages WHERE id={mid}") or "0") != "0")''',
    '''    # 实测语义：attempts 只在**失败**时 +1（InboundMessageProcessor:387「失败：attempts+1」），
    # 一次就成的行停在 0。"core 真的消费过"的证据是 status=PROCESSED + processed_at + outbox 事件
    # （本组下面三条都断言了），不是 attempts。G10 里那条被重投后再次失败的行才是 attempts>0 的正例。
    L.assert_eq("G7 一次成功消费不涨 attempts（涨只发生在失败路径）", "0",
                db1(f"SELECT attempts FROM integration.inbound_messages WHERE id={mid}"))''', "8")

# ---------- 9) G8：跨 partner 撞键要换一个有 scope 的 partner ----------
rep('''    st, rj, _, _ = dmz("POST", "/external/wms/inbound-confirm", partner=PB, secret=sec(PB),
                       body=inbound_body(52), idem=key)
    L.assert_eq("G8 跨 partner 抢同一把键 → 409", 409, st)''',
    '''    # 换 PC（它和 PA 一样有 WMS_INBOUND_WRITE）。上一版用 PB，而 PB 的 scope 里没有这个端点，
    # 请求在 scope 检查就被 403 挡掉，根本走不到幂等键冲突——撞的是 PTNR_002 而不是 PTNR_005。
    st, rj, _, _ = dmz("POST", "/external/wms/inbound-confirm", partner=PC, secret=sec(PC),
                       body=inbound_body(52), idem=key)
    L.assert_eq("G8 跨 partner 抢同一把键 → 409", 409, st)''', "9")

# ---------- 10) G10：未验签+FAILED 的行被 V72 约束挡死，服务侧那条规则端到端不可达 ----------
rep('''    bad = db1("SELECT id FROM integration.inbound_messages WHERE signature_ok=FALSE"
              " AND status IN ('FAILED','DEAD') ORDER BY id DESC LIMIT 1")
    if bad:
        st, rj, _, _ = corex("POST", f"/api/integration/inbox/{bad}/redeliver", base=WEB, body={"reason": "x"})
        L.assert_eq("G10 未验签的行永不许重投 → 409", 409, st)
        L.assert_eq("G10 规则名", "MESSAGE_NOT_SIGNATURE_VERIFIED", details_of(rj).get("rule"))
    else:
        L.assert_fail("G10 没有未验签的 FAILED/DEAD 行可供判前置（构造缺失）", str(bad))''',
    '''    # V72 的 chk_inbox_unverified_not_claimable 规定 signature_ok=FALSE 的行只能停在
    # REJECTED_BAD_SIGNATURE/DUPLICATE ⟹ 库里根本不存在"未验签却已 FAILED/DEAD"的行，
    # 服务侧 IntegrationRetryPolicy.RULE_NOT_SIGNED 那道前置在端到端上不可达（纵深防御，
    # 由域单测覆盖）。这里把"不可达"本身测出来：直接构造那样一行，断言库把它拒绝掉。
    L.assert_eq("G10 未验签的行到不了 FAILED/DEAD（V72 约束挡死，服务侧 RULE_NOT_SIGNED 是纵深防御）",
                False, L.psql_exec(
                    "INSERT INTO integration.inbound_messages (idempotency_key, partner_code,"
                    " message_type, signature_ok, status, attempts, trace_id) VALUES ('"
                    + esc(RUN + "-UNVERIFIED-1") + "', '" + esc(PA) + "', 'WMS_INBOUND',"
                    + " FALSE, 'FAILED', 1, '" + esc(hdr_trace("unverified")) + "')"))
    L.assert_eq("G10 构造失败后库里确实没有这把键的行", "0", db1(
        "SELECT count(*) FROM integration.inbound_messages WHERE idempotency_key='"
        + esc(RUN + "-UNVERIFIED-1") + "'"))
    # 可构造的那一半：验签通过但状态不可重投 → 409 + 规则名（RECEIVED/PROCESSED 都是这类）。
    bad = db1(f"SELECT id FROM integration.inbound_messages WHERE partner_code='{PA}'"
              " AND signature_ok=TRUE AND status='RECEIVED' ORDER BY id DESC LIMIT 1")
    if bad:
        st, rj, _, _ = corex("POST", f"/api/integration/inbox/{bad}/redeliver", base=WEB, body={"reason": "x"})
        L.assert_eq("G10 RECEIVED 行重投 → 409（不静默、不 500）", 409, st)
        L.assert_eq("G10 规则名", "INBOX_STATUS_NOT_REDELIVERABLE", details_of(rj).get("rule"))
    else:
        L.assert_fail("G10 构造不出 RECEIVED 行（本轮没有已验签未消费的消息可用）", str(bad))''', "10")

# ---------- 11) G99：按 trace 前缀补删（partner_code='UNKNOWN' 的留痕上一版删不掉） ----------
rep('''    L.psql_exec("DELETE FROM integration.call_logs WHERE message_id IN (" + id_list + ")"
                " OR task_id IN (" + tid_list + ") OR partner_code IN (" + codes + ")"
                " OR partner_code LIKE " + like)''',
    '''    # 再加一条 trace 前缀作用域：未注册端点那类留痕把 partner_code 记成字面量 'UNKNOWN'，
    # 只按 partner_code 删会留下本轮的残渣（第 3 轮实测 id=17 那行）。TR 是本轮独有的追踪前缀，
    # 依然只删自己造的行。
    L.psql_exec("DELETE FROM integration.call_logs WHERE message_id IN (" + id_list + ")"
                " OR task_id IN (" + tid_list + ") OR partner_code IN (" + codes + ")"
                " OR partner_code LIKE " + like
                + " OR lower(trace_id) LIKE lower('" + esc(TR) + "%')")''', "11")

io.open(P, "w", encoding="utf-8", newline="").write(s)
print("patch18 applied", n, "edits")
