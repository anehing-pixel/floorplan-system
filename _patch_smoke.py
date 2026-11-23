import io
import re

p = 'scripts/smoke_used_wu3.py'
s = io.open(p, encoding='utf-8').read()
fails = []


def rep(a, b):
    global s
    if s.count(a) != 1:
        fails.append((s.count(a), a[:80]))
        return
    s = s.replace(a, b)


rep('''        g0_preflight()
        tok_admin, tok_key, tok_mon = g1_permissions()''',
    '''        g0_preflight()
        tok_admin, tok_key, tok_mon = g1_permissions()
        TOK[:] = [tok_admin, tok_key, tok_mon]''')

rep('''    # §2.4 派生键：不传 Idempotency-Key，脚本按同一模板逐字符比对
    b = json.loads(inbound_body(60, warehouse="WH:C"))
    body60 = json.dumps(b, ensure_ascii=False).encode("utf-8")
    st, rj, _, _ = dmz("POST", "/external/wms/inbound-confirm", partner=PA, secret=sec(PA), body=body60)
    L.assert_eq("G8 未传键也能受理", 202, st)
    expect = f"SRC:{PA}:INB:WH%3A{vin_of(60)}:{b['inbound_no']}"
    expect = expect.replace(f"WH%3A{vin_of(60)}", f"{esc_key('WH:C')}%3A{esc_key(vin_of(60))}")
    got_key = data_of(rj).get("idempotency_key")
    L.assert_eq("G8 派生键与 §2.4 模板逐字符一致（含 : → %3A）", expect, got_key)
    L.assert_eq("G8 入库的键与响应一致", got_key, db1(
        f"SELECT idempotency_key FROM integration.inbound_messages WHERE id={data_of(rj).get('message_id')}"))
    L.assert_eq("G8 组合键里的冒号已转义", 0, (got_key or "").count("WH:C"))

    b2 = json.loads(inbound_body(61, warehouse="WH%P"))
    st, rj, _, _ = dmz("POST", "/external/wms/inbound-confirm", partner=PA, secret=sec(PA),
                      body=json.dumps(b2, ensure_ascii=False).encode("utf-8"))
    L.assert_eq("G8 % 也转义（% → %25）", True, "%25P" in str(data_of(rj).get("idempotency_key")))

    st, rj, _, _ = dmz("POST", "/external/wms/inbound-confirm", partner=PA, secret=sec(PA),
                       body=snapshot_body(62), idem="SRC:" + RUN + "-reserved")
    L.assert_eq("G8 显式键占用保留命名空间 → 400", 400, st)
    codes = {e.get("rule") for e in (details_of(rj).get("field_errors") or []) if isinstance(e, dict)}
    L.assert_eq("G8 保留命名空间的规则码", "RESERVED_NAMESPACE", (codes or {None}).pop() if codes else None)

    st, rj, _, _ = dmz("POST", "/external/wms/inbound-confirm", partner=PA, secret=sec(PA),
                       body=inbound_body(63), idem="K" * 65)
    L.assert_eq("G8 显式键超 64 → 400", 400, st)
''',
    '''    # §2.4 派生键：不传 Idempotency-Key，脚本按同一模板逐字符算一遍（键必须能复现，不是抄响应）
    b = json.loads(inbound_body(60, warehouse="WH:C"))
    st, rj, _, _ = dmz("POST", "/external/wms/inbound-confirm", partner=PA, secret=sec(PA),
                       body=json.dumps(b, ensure_ascii=False).encode("utf-8"))
    L.assert_eq("G8 未传 Idempotency-Key 也能受理（派生键路径）", 202, st)
    expect = derived_key(PA, "INB", b["warehouse_code"], b["vin"], b["inbound_no"])
    got_key = data_of(rj).get("idempotency_key")
    L.assert_eq("G8 派生键与 §2.4 模板逐字符一致（含 : → %3A）", expect, got_key)
    L.assert_eq("G8 入库的键与响应一致", got_key, db1(
        "SELECT idempotency_key FROM integration.inbound_messages WHERE id="
        + str(data_of(rj).get("message_id"))))
    st, rj, _, _ = dmz("POST", "/external/wms/inbound-confirm", partner=PA, secret=sec(PA),
                       body=json.dumps(b, ensure_ascii=False).encode("utf-8"))
    L.assert_eq("G8 派生键可复现（同输入同键 = 幂等成立的前提）", 200, st)

    b2 = json.loads(inbound_body(61, warehouse="WH%P"))
    st, rj, _, _ = dmz("POST", "/external/wms/inbound-confirm", partner=PA, secret=sec(PA),
                       body=json.dumps(b2, ensure_ascii=False).encode("utf-8"))
    L.assert_eq("G8 % 转义为 %25（先 % 后 : 的顺序在脚本侧复现一致）",
                derived_key(PA, "INB", "WH%P", b2["vin"], b2["inbound_no"]),
                data_of(rj).get("idempotency_key"))

    st, rj, _, _ = dmz("POST", "/external/wms/inbound-confirm", partner=PA, secret=sec(PA),
                       body=inbound_body(62), idem="SRC:" + RUN + "-reserved")
    L.assert_eq("G8 显式键占用 SRC: 派生命名空间 → 400", 400, st)
    rules = {e.get("rule") for e in (details_of(rj).get("field_errors") or []) if isinstance(e, dict)}
    L.assert_eq("G8 保留命名空间的规则码", "RESERVED_NAMESPACE", rules.pop() if rules else None)

    st, rj, _, _ = dmz("POST", "/external/wms/inbound-confirm", partner=PA, secret=sec(PA),
                       body=inbound_body(63), idem="K" * 65)
    L.assert_eq("G8 显式键超 64 字符 → 400", 400, st)
    rules = {e.get("rule") for e in (details_of(rj).get("field_errors") or []) if isinstance(e, dict)}
    L.assert_eq("G8 超长键的规则码", "INVALID_FORMAT", rules.pop() if rules else None)

    st, rj, _, _ = dmz("POST", "/external/wms/inbound-confirm", partner=PA, secret=sec(PA),
                       body=inbound_body(64), idem="  ")
    L.assert_eq("G8 键头只给空格 = 视为未传，走派生", 202, st)
    L.assert_eq("G8 派生出的键不为空", True, bool(data_of(rj).get("idempotency_key")))
''')

rep('''    st, rj, _, _ = dmz("POST", "/external/wms/inventory-snapshot", partner=PB, secret=sec(PB),
                       body=snapshot_body(70))
    kb = data_of(rj).get("idempotency_key")
    L.assert_eq("G8 批量端点派生键带正文摘要后缀（§3.4 同日重推要成新行）",
                f"SRC:{PB}:SNAP:WH-B:{json.loads(snapshot_body(70))['snapshot_date']}:"
                + sha256_hex(snapshot_body(70))[:12], kb)
    st, rj, _, _ = dmz("POST", "/external/wms/inventory-snapshot", partner=PB, secret=sec(PB),
                       body=snapshot_body(71))
    L.assert_eq("G8 同内容重投仍是重复", 200, st)


def det_or_none(d, k):
    return d.get(k) if isinstance(d, dict) else None


def esc_key(v):
    """IdempotencyKeyFactory.escape：先 % → %25，再 : → %3A。"""
    return v.replace("%", "%25").replace(":", "%3A")
''',
    '''    sb = snapshot_body(70)
    st, rj, _, _ = dmz("POST", "/external/wms/inventory-snapshot", partner=PB, secret=sec(PB), body=sb)
    L.assert_eq("G8 批量端点派生键带正文摘要后 12 位（§3.4 同日重推必须成新行）",
                derived_key(PB, "SNAP", "WH-B", json.loads(sb)["snapshot_date"], body=sb, batch=True),
                data_of(rj).get("idempotency_key"))
    st, rj, _, _ = dmz("POST", "/external/wms/inventory-snapshot", partner=PB, secret=sec(PB), body=sb)
    L.assert_eq("G8 批量端点同内容重投 = 重复（200）", 200, st)
    st, rj, _, _ = dmz("POST", "/external/wms/inventory-snapshot", partner=PB, secret=sec(PB),
                       body=snapshot_body(72, n=2))
    L.assert_eq("G8 批量端点同日不同内容 = 新受理（摘要后缀起了作用）", 202, st)
''')

rep('''    L.assert_eq("G8 跨 partner 抢同一把键 → 409", 409, st)
    L.assert_eq("G8 跨 partner 不回显对方 message_id", None, det_or_none(details_of(rj), "message_id"))''',
    '''    L.assert_eq("G8 跨 partner 抢同一把键 → 409", 409, st)
    L.assert_eq("G8 跨 partner 撞键也是 PTNR_005", "PTNR_005", code_of(rj))
    det2 = details_of(rj)
    L.assert_eq("G8 跨 partner 不回显对方 message_id", None, det2.get("message_id"))
    L.ok("hint" in det2, "G8 跨 partner 只给提示、不交代键归谁", json.dumps(det2, ensure_ascii=False)[:160])''')

rep('''    det = details_of(rj)
    L.ok("expected_digest_prefix" in det and "actual_digest_prefix" in det,''',
    '''    det = details_of(rj)
    L.assert_eq("G8 同键异摘要的错误码", "PTNR_005", code_of(rj))
    L.ok("expected_digest_prefix" in det and "actual_digest_prefix" in det,''')

rep('''    L.equal = None
''', '')

rep('''    mid = db1(f"SELECT id FROM integration.inbound_messages WHERE partner_code='{PA}'"
              " AND signature_reason IS NULL AND last_error LIKE '%无 vin%'"
              " AND status IN ('FAILED','DEAD') ORDER BY id DESC LIMIT 1")
    if not mid:
        got = L.wait_until(lambda: db1(
            f"SELECT id FROM integration.inbound_messages WHERE partner_code='{PA}'"
            " AND status IN ('FAILED','DEAD') ORDER BY id DESC LIMIT 1"), timeout=45, interval=1.5)
        mid = got[1]
        L.ok(bool(mid), "G10 出现一条 FAILED/DEAD 收件行（core 消费失败的真实来源）", str(got[1]))
    L.ok(bool(mid), "G10 拿到可重投的收件行 id", str(mid))
    if not mid:
        return
    page = dbj(f"SELECT row_to_json(x) FROM (SELECT count(*) AS n FROM integration.inbound_messages"
               f" WHERE id={mid} AND signature_ok=TRUE) x")
    L.assert_eq("G10 该行是验签通过的（重投前置条件之一）", 1, (page or {}).get("n"))''',
    '''    # 来源不靠猜：G9 那条缺 vin 的 I-07 会被 core 判失败，attempts 到 3 即 DEAD
    got = L.wait_until(lambda: db1(
        "SELECT id FROM integration.inbound_messages WHERE partner_code='" + PA + "'"
        " AND message_type='WMS_RECEIPT' AND signature_ok=TRUE"
        " AND status IN ('FAILED','DEAD') ORDER BY id DESC LIMIT 1"), timeout=60, interval=1.5)
    mid = got[1]
    L.ok(bool(mid), "G10 出现一条验签通过且已 FAILED/DEAD 的收件行（重投的合法来源）",
         "id=" + str(got[1]) + " 耗时=" + ("%.1f" % got[2]) + "s")
    if not mid:
        return
    L.assert_eq("G10 该行 signature_ok=t（重投前置条件之一）", "t",
                db1("SELECT signature_ok FROM integration.inbound_messages WHERE id=" + str(mid)))''')

rep('''    st, rj, _, _ = corex("GET", f"/api/integration/inbox?partner={PA}&status=DEAD&limit=50", base=WEB)
    L.assert_eq("G10 运维口按 DEAD 过滤 → 200", 200, st)
    items = data_of(rj).get("items") or []
    L.ok(any(str(i.get("id")) == str(mid) for i in items) or True, "G10 DEAD 过滤可用",
         f"items={len(items)}")
    one_row = dbj(f"SELECT row_to_json(m) FROM (SELECT status FROM integration.inbound_messages WHERE id={mid}) m")
    st, rj, _, _ = corex("POST", f"/api/integration/inbox/{mid}/redeliver", base=WEB,
                        body={"reason": "冒烟重投"})
    L.assert_eq(f"G10 {one_row} → redeliver → 200", 200, st)''',
    '''    st, rj, _, _ = corex("GET", "/api/integration/inbox?businessRef=" + RUN + "-RLO-81&limit=50",
                         base=WEB)
    L.assert_eq("G10 运维口按 business_ref 精确过滤 → 200", 200, st)
    items = (data_of(rj) or {}).get("items") or []
    L.assert_eq("G10 business_ref 精确匹配到本条（V72 是 btree，不是 LIKE）", 1,
                sum(1 for i in items if str(i.get("id")) == str(mid)))
    before = db1("SELECT status FROM integration.inbound_messages WHERE id=" + str(mid))
    st, rj, _, _ = corex("POST", "/api/integration/inbox/" + str(mid) + "/redeliver", base=WEB,
                         body={"reason": "冒烟重投"})
    L.assert_eq("G10 " + str(before) + " → redeliver → 200", 200, st)''')

rep('''    aud = L.wait_until(lambda: db1(
        f"SELECT count(*) FROM {SCHEMA}.audit_events WHERE action='INTEGRATION_INBOX_REDELIVERED'"
        f" AND target_id='{mid}' AND actor='admin'"), timeout=10, interval=1.0)
    L.ok((aud[1] or "0") != "0", "G10 审计行落 INBOUND 重投（含操作人）", str(aud[1]))
    again = L.wait_until(lambda: db1(
        f"SELECT attempts FROM integration.inbound_messages WHERE id={mid}::bigint*0+{mid}"
        ), timeout=5, interval=1.0)
    L.ok(again[0], "G10 重投后 core 再次消费（attempts 重新起来）", str(again[1]))''',
    '''    aud = L.wait_until(lambda: db1(
        "SELECT count(*) FROM " + SCHEMA + ".audit_events WHERE action='INTEGRATION_INBOX_REDELIVERED'"
        " AND target_type='INTEGRATION_INBOX_MESSAGE' AND target_id='" + str(mid) + "' AND actor='admin'"),
        timeout=15, interval=1.0)
    L.ok((aud[1] or "0") != "0", "G10 重投落审计行（含操作人 admin）", str(aud[1]))
    again = L.wait_until(lambda: (db1(
        "SELECT attempts FROM integration.inbound_messages WHERE id=" + str(mid)) or "0") != "0",
        timeout=40, interval=1.0)
    L.ok(again[0], "G10 重投后 core 再次消费（attempts 从 0 重新起来）", str(again[1]))''')

rep('''    fresh = db1(f"SELECT id FROM integration.inbound_messages WHERE partner_code='{PA}'"
                " AND status='RECEIVED' AND signature_ok=TRUE ORDER BY id DESC LIMIT 1")''',
    '''    fresh = db1(f"SELECT id FROM integration.inbound_messages WHERE partner_code='{PA}'"
                " AND status='PROCESSED' AND signature_ok=TRUE ORDER BY id DESC LIMIT 1")''')

rep('''    L.assert_eq("G11 对方 instruction_no 回写 business_ref", "STUB-0001", (row or {}).get("business_ref"))''',
    '''    L.assert_eq("G11 受理后 business_ref 仍是建单业务号（契约 instructionNoField=null ⟹ "
                "对方的 instruction_no 不回写，缺口见 G16）",
                RUN + "-ORD-T1", (row or {}).get("business_ref"))''')

rep('''    L.assert_eq("G11 拒绝原因写进 last_error（契约期望 ACKNOWLEDGED）", True,
                "ACKNOWLEDGED" in str(db1(f"SELECT last_error FROM integration.outbound_tasks WHERE id={t2}")))''',
    '''    L.assert_eq("G11 拒绝原因把契约期望值写进 last_error", True,
                "ACKNOWLEDGED" in str(db1(
                    "SELECT last_error FROM integration.outbound_tasks WHERE id=" + str(t2))))''')

rep('''    L.ok("Retry-After" in json.dumps(list(_h.keys())) if False else True, "G12 占位")
''', '')

rep('''    st, rj, _, _ = dmz("GET", f"/external/messages/{mid}", partner=PE, secret=sec(PE))
    L.assert_eq("G13 无 MESSAGE_READ 的合作方 → 403 PTNR_002", 403, st)''',
    '''    st, rj, _, _ = dmz("GET", "/external/messages/" + str(mid), partner=PG, secret=sec(PG))
    L.assert_eq("G13 无 MESSAGE_READ scope 的合作方查消息 → 403", 403, st)
    L.assert_eq("G13 错误码 PTNR_002", "PTNR_002", code_of(rj))''')

io.open(p, 'w', encoding='utf-8').write(s)
print("FAILS:", fails)
