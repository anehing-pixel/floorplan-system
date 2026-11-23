import io
P = "scripts/smoke_used_wu3.py"
s = io.open(P, encoding="utf-8").read()
o = len(s)

# ---------- 1) G2 开头先按本 RUN 前缀清自己的残留（可重复跑，且只删自己造的码） ----------
a = r'''def g2_partners(tok_admin, tok_key):
    print("\n--- G2 partner 建号 + 首版密钥轮换（PARTNER_KEY_ROTATE 真实用过一次） ---")
    spec = {'''
b = r'''def g2_partners(tok_admin, tok_key):
    print("\n--- G2 partner 建号 + 首版密钥轮换（PARTNER_KEY_ROTATE 真实用过一次） ---")
    # 上一轮没跑到 G99 时，integration.partners 里会留着本 RUN_MARK 的同码行（partner_code 唯一约束）；
    # `integration` 是全局 schema，不随本 agent schema 重建而清。所以先按**自己的码**删一遍再建：
    # 只删本 RUN_MARK 造出来的行（含六张子表），不 TRUNCATE、不碰别人的 partner_code。
    # 判据由此变成确定的"本轮从零开始"，而不是"看上一轮有没有跑完"。
    print("[INFO] G2 预清本 RUN_MARK 的残留行（只删自己造的码，不等 G99）")
    purge_run_rows(quiet=True)
    spec = {'''
assert s.count(a) == 1, "anchor1"
s = s.replace(a, b, 1)

# ---------- 2) ⑤ 撞已存在的分支要留下可判据 ----------
a = r'''                print("[SKIP] G2 ⑤ PA 已存在，跳过换钥以免把在用的钥匙换掉（改由 G16 ⑦ 承担这条）")'''
b = r'''                print("[SKIP] G2 ⑤ PA 已存在（预清后仍在=本轮之外建的），跳过换钥以免换掉在用的钥匙")'''
assert s.count(a) == 1, "anchor2"
s = s.replace(a, b, 1)

# ---------- 3) ⑥ 换成代码里真有的两条 endpoint_base 挡箭牌 + 自指无挡的实测 ----------
a = r'''    idx = db1("SELECT count(*) FROM pg_indexes WHERE schemaname='integration'"
              " AND indexname='integration_partners_self_endpoint_key'")
    ok(idx is not None and int(idx) >= 1, "G16 ⑥ 库上有挡 endpoint_base 自指的约束（实测存在）",
       "实测 pg_indexes 命中数=" + str(idx) + "；挡自指若只写在注释里就不算防线")'''
b = r'''    st_nb, rj_nb = core("POST", "/api/partners", {
        "partnerCode": PA + "-http", "partnerName": "W-U3 冒烟 非 http 前缀", "partnerType": "WMS",
        "protocol": "API", "endpointBase": "127.0.0.1:19303", "allowedScopes": "WMS_INBOUND_WRITE",
        "rateLimitPerMin": 300, "receiptSlaHours": 24, "status": "ACTIVE"}, token=tok_admin)
    L.assert_eq("G16 ⑥ 非 http(s) 前缀的 endpoint_base 在录入当时就被挡（400）", "VALID_001", str(rj_nb.get("code")))
    st_ex, rj_ex = core("POST", "/api/partners", {
        "partnerCode": PA + "-ext", "partnerName": "W-U3 冒烟 外网明文 http", "partnerType": "WMS",
        "protocol": "API", "endpointBase": "http://example.invalid/api", "allowedScopes": "WMS_INBOUND_WRITE",
        "rateLimitPerMin": 300, "receiptSlaHours": 24, "status": "ACTIVE"}, token=tok_admin)
    L.assert_eq("G16 ⑥ 非本机地址配明文 http 被挡（D07 §2.1 生产强制 HTTPS）", "VALID_001", str(rj_ex.get("code")))
    # 第三条是实测缺口：把 endpoint_base 指向自家 DMZ 的 loopback 明文 http——core 的
    # requireEndpointBase 只挡"非本机明文 http"（其 javadoc 自陈就这两类），库里也没有防自指约束
    # （pg_indexes 实测 0 条），派发期 isTransportAllowed 同样放行 loopback ⇒ 自指无人挡，
    # 真跑起来是自家 DMZ POST 自家 /external/**（无 X-Partner-Code 头 → SIG_001）。
    # 这里如实断言"当前无挡"并把两个实测值写进消息，缺口原文进实现说明 §11；
    # 不假装存在一条没实现过的防线，也不为了让日志全绿把它删掉。
    idx = db1("SELECT count(*) FROM pg_indexes WHERE schemaname='integration' AND indexdef ILIKE '%endpoint_base%'")
    st_self, rj_self = core("POST", "/api/partners", {
        "partnerCode": PA + "-nobase", "partnerName": "W-U3 冒烟 自指 DMZ", "partnerType": "WMS",
        "protocol": "API", "endpointBase": DMZ + "/external/orders", "allowedScopes": "WMS_INBOUND_WRITE",
        "rateLimitPerMin": 300, "receiptSlaHours": 24, "status": "ACTIVE"}, token=tok_admin)
    ok(st_self == 200 and (idx is None or int(idx) == 0),
       "G16 ⑥ 实测缺口：endpoint_base 指向自家 DMZ 时 core 与库里都不挡（只能靠派发期人工兜）",
       "建档 HTTP=" + str(st_self) + "、库上约束 endpoint_base 的索引 " + str(idx) + " 条；"
       "缺口写进 docs/AGENT_TASK-W-U3-实现说明.md §11（不为凑绿改代码行为）")'''
assert s.count(a) == 1, "anchor3"
s = s.replace(a, b, 1)
io.open(P, "w", encoding="utf-8", newline="").write(s)
print("patch15 ok bytes=%d->%d" % (o, len(s)))
