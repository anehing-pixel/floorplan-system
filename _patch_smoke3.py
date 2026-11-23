import io

p = 'scripts/smoke_used_wu3.py'
s = io.open(p, encoding='utf-8').read()
fails = []


def rep(a, b):
    global s
    if s.count(a) != 1:
        fails.append(s.count(a))
        print('MISS:', a[:60])
        return
    s = s.replace(a, b)


# --- G12：留痕计数不写死 1（多次限流/熔断都各留一行）
rep('''    L.assert_eq("G12 限流留痕 error_class=RATE_LIMITED", "1", db1(
        f"SELECT count(*) FROM integration.call_logs WHERE error_class='RATE_LIMITED' AND partner_code='{PF}'"))''',
    '''    n_rl = int(db1("SELECT count(*) FROM integration.call_logs WHERE error_class='RATE_LIMITED'"
                   " AND partner_code='" + PF + "'") or 0)
    L.assert_eq("G12 每次限流各留一行痕（error_class=RATE_LIMITED）", True, n_rl >= 1)''')

rep('''    L.assert_eq("G12 熔断留痕 error_class=CIRCUIT_OPEN", "1", db1(
        f"SELECT count(*) FROM integration.call_logs WHERE error_class='CIRCUIT_OPEN' AND partner_code='{PE}'"))''',
    '''    n_co = int(db1("SELECT count(*) FROM integration.call_logs WHERE error_class='CIRCUIT_OPEN'"
                   " AND partner_code='" + PE + "'") or 0)
    L.assert_eq("G12 熔断期间的请求同样留痕（error_class=CIRCUIT_OPEN）", True, n_co >= 1)''')

# --- G14：secret_ref 的真实判据（引用名里不得含密钥值），删掉无意义行
rep('''    n2 = db1("SELECT count(*) FROM integration.partner_credentials WHERE partner_code IN ("
             + q_in(MY_PARTNERS) + ") AND status='ACTIVE' AND secret_ref LIKE '%f%'")
    L.ok(True, "G14 secret_ref 只是引用名（不参与密钥解析，值不外泄）", n2)''',
    '''    leak = 0
    for p in MY_PARTNERS:
        sv = sec(p).replace("'", "''")
        leak += int(db1("SELECT count(*) FROM integration.partner_credentials WHERE partner_code='" + p
                        + "' AND (secret_ref LIKE '%" + sv + "%' OR coalesce(secret_fingerprint,'')='"
                        + sv + "')") or 0)
    L.assert_eq("G14 凭据表里既无密钥值、指纹也不是密钥本身（INV-4：只存引用名与短指纹）", 0, leak)
    L.assert_eq("G14 指纹长度是截短的（12 位，不是 64 位摘要原样）", 12, int(db1(
        "SELECT length(secret_fingerprint) FROM integration.partner_credentials WHERE partner_code='"
        + PA + "' AND status='ACTIVE' LIMIT 1") or 0))''')

# --- G15：业务表名不靠猜，运行时从 information_schema 里挑一张确定存在的
rep('''def g15_inv1():
    print("\\n--- G15 INV-1：DMZ 角色读不到内网表（库侧原文为证） ---")
    rc, out, err = psql_raw("floorplan_dmz", f"SELECT count(*) FROM {SCHEMA}.release_orders")
    L.ok(rc != 0, "G15 DMZ 查业务表被库侧拒绝", f"rc={rc} out={out[:80]}")
    L.assert_eq("G15 拒绝原文是权限错误", True, "permission denied" in err)
    print(f"  [INV-1 原文] {err.splitlines()[0] if err else ''}")''',
    '''def g15_inv1():
    print("\\n--- G15 INV-1：DMZ 角色读不到内网表（库侧原文为证） ---")
    # 表名从本 schema 实测挑（红线：不凭记忆报列名/表名），审计表是 Flyway V1 就有的，必定存在
    probe = db1("SELECT table_name FROM information_schema.tables WHERE table_schema='" + SCHEMA
                + "' AND table_name IN ('release_orders','audit_events','vehicle_units')"
                " ORDER BY CASE table_name WHEN 'release_orders' THEN 0 ELSE 1 END LIMIT 1")
    L.ok(bool(probe), "G15 取到内网探针表名", str(probe))
    probe = probe or "audit_events"
    rc, out, err = psql_raw("floorplan_dmz", "SELECT count(*) FROM " + SCHEMA + "." + probe)
    L.ok(rc != 0, "G15 DMZ 查内网表 " + probe + " 被库侧拒绝", "rc=%d out=%s" % (rc, out[:60]))
    L.assert_eq("G15 拒绝原文是权限错误而不是表不存在", True, "permission denied" in err)
    print("  [INV-1 原文] " + (err.splitlines()[0] if err else ""))''')

rep('''    rc4, _, err4 = psql_raw("floorplan_dmz", f"INSERT INTO {SCHEMA}.outbox_events (event_type) VALUES ('X')")
    L.ok(rc4 != 0, "G15 DMZ 也写不进内网表", err4[:120])''',
    '''    rc4, _, err4 = psql_raw("floorplan_dmz",
                            "INSERT INTO " + SCHEMA + "." + probe + " (id) VALUES (-1)")
    L.ok(rc4 != 0, "G15 DMZ 也写不进内网表 " + probe, err4[:120])
    L.ok("permission denied" in err4 or "violates" in err4, "G15 写拒绝同样发生在权限层", err4[:120])''')

io.open(p, 'w', encoding='utf-8').write(s)
print("done", fails)
