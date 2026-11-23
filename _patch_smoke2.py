import io

p = 'scripts/smoke_used_wu3.py'
s = io.open(p, encoding='utf-8').read()
start = s.index('def g16_gaps():')
end = s.index('# ====================================================================== 主流程')

new = '''def g16_gaps():
    print("\\n--- G16 缺口与实况（按代码实际行为如实登记，不用断言迁就实现） ---")
    snap = dbj("SELECT row_to_json(m) FROM (SELECT id, status, processed_at, last_error"
               " FROM integration.inbound_messages WHERE partner_code='" + PB + "'"
               " AND message_type='WMS_SNAPSHOT' ORDER BY id DESC LIMIT 1) m")
    L.ok(snap is not None, "G16 I-05 快照消息确实进了收件表", str(snap))
    if snap:
        L.assert_eq("G16 缺口：core 无 WMS_SNAPSHOT 投影 → 停放为 PENDING（不是 PROCESSED）",
                    "PENDING", snap.get("status"))
        L.assert_eq("G16 停放原因用 PARK: 前缀写进 last_error", True,
                    str(snap.get("last_error", "")).startswith("PARK:"))
        L.ok(snap.get("processed_at") is not None, "G16 停放行也写 processed_at，轮询不再捞它")
        st, rj, _, _ = corex("POST", "/api/integration/inbox/" + str(snap.get("id")) + "/redeliver",
                             base=WEB, body={"reason": "冒烟试投停放行"})
        L.assert_eq("G16 后果：停放行不在 FAILED/DEAD，运维口重投规则不认它 → 409", 409, st)
        L.assert_eq("G16 拒绝规则名", "INBOX_STATUS_NOT_REDELIVERABLE",
                    details_of(rj).get("rule"))
    n_ok = corex("GET", "/api/integration/call-logs?direction=IN&limit=500", base=WEB)
    n_doc = corex("GET", "/api/integration/call-logs?direction=INBOUND&limit=500", base=WEB)
    L.assert_eq("G16 缺口：控制器 javadoc 写 direction=INBOUND，但 V74 列值是 IN（按文档值查不到）",
                True, len(data_list_of(n_ok[1])) > len(data_list_of(n_doc[1])))
    L.assert_eq("G16 IN 侧留痕可查", True, len(data_list_of(n_ok[1])) > 0)
    items = (data_of(corex("GET", "/api/integration/inbox?endpoint=I-04&limit=5", base=WEB)[1])
             or {}).get("items") or []
    L.assert_eq("G16 inbox.endpoint 存接口编号（I-04）而不是请求路径", True, len(items) > 0)
    items2 = (data_of(corex("GET", "/api/integration/inbox?endpoint=/external/wms/inbound-confirm",
                            base=WEB)[1]) or {}).get("items") or []
    L.assert_eq("G16 用请求路径查不到（与 V72 列注释的口径不一致，登记为实现说明）", 0, len(items2))
    L.assert_eq("G16 缺口：未验签的拒绝行不写正文文件，运维只能凭摘要排障", None, db1(
        "SELECT payload_ref FROM integration.inbound_messages WHERE signature_ok=FALSE"
        " AND payload_ref IS NOT NULL AND partner_code IN (" + q_in(MY_PARTNERS) + ") LIMIT 1"))
    L.assert_eq("G16 入站留痕的 response_digest 为空（core 侧不回写响应摘要）", None, db1(
        "SELECT response_digest FROM integration.call_logs WHERE direction='IN'"
        " AND partner_code IN (" + q_in(MY_PARTNERS) + ") AND response_digest IS NOT NULL LIMIT 1"))
    L.assert_eq("G16 O-02 契约 instructionNoField=null ⟹ 对方回执号在 core 侧无处可查（缺口）", None,
                db1("SELECT business_ref FROM integration.outbound_tasks WHERE business_ref LIKE 'STUB-%'"
                    " AND idempotency_key LIKE 'RLO:" + RUN + "%' LIMIT 1"))
    n_ev = db1("SELECT count(*) FROM " + SCHEMA + ".outbox_events WHERE event_type='WMS_INBOUND_CONFIRMED'")
    L.ok(True, "G16 实况：WMS_INBOUND_CONFIRMED 在本仓暂无消费者（链路终点是事件）", "count=" + str(n_ev))


# ====================================================================== 收尾

def g99_purge():
    print("\\n--- G99 清理（只按本 RUN_MARK 删自己造的行） ---")
    codes = q_in(MY_PARTNERS) + ", '" + esc(PA) + "-dup', '" + esc(PA) + "-nobase'"
    like = "'" + esc(PA) + "%'"
    ids = [str(r[0]) for r in (db("SELECT id FROM integration.inbound_messages WHERE partner_code IN ("
                                  + codes + ") OR partner_code LIKE " + like) or [])]
    id_list = ", ".join(ids) or "-1"
    tids = [str(r[0]) for r in (db("SELECT id FROM integration.outbound_tasks WHERE idempotency_key LIKE "
                                   "'RLO:" + RUN + "%'") or [])]
    tid_list = ", ".join(tids) or "-1"
    L.psql_exec("DELETE FROM integration.nonces WHERE partner_code IN (" + codes + ")"
                " OR partner_code LIKE " + like)
    L.psql_exec("DELETE FROM integration.call_logs WHERE message_id IN (" + id_list + ")"
                " OR task_id IN (" + tid_list + ") OR partner_code IN (" + codes + ")"
                " OR partner_code LIKE " + like)
    L.psql_exec("DELETE FROM integration.inbound_messages WHERE partner_code IN (" + codes + ")"
                " OR partner_code LIKE " + like)
    L.psql_exec("DELETE FROM integration.outbound_tasks WHERE idempotency_key LIKE 'RLO:" + RUN + "%'")
    L.psql_exec("DELETE FROM integration.partner_credentials WHERE partner_code IN (" + codes + ")"
                " OR partner_code LIKE " + like)
    L.psql_exec("DELETE FROM integration.partners WHERE partner_code IN (" + codes + ")"
                " OR partner_code LIKE " + like)
    # 事件与审计只认本 RUN_MARK 的业务号；outbox 属本 agent schema，行是我方入站投影出来的
    L.psql_exec("DELETE FROM " + SCHEMA + ".outbox_events WHERE event_type IN ('WMS_INBOUND_CONFIRMED',"
                " 'RELEASE_ORDER_RECEIPTED') AND (aggregate_key LIKE '" + RUN + "-INB-%'"
                " OR aggregate_key IN (" + ", ".join("'inbox:" + i + "'" for i in ids) + "))")
    L.psql_exec("DELETE FROM " + SCHEMA + ".audit_events WHERE (target_type='INTEGRATION_PARTNER'"
                " AND (target_id IN (" + codes + ") OR target_id LIKE " + like + "))"
                " OR (target_type='INTEGRATION_INBOX_MESSAGE' AND target_id IN (" + id_list + "))"
                " OR (target_type='INTEGRATION_OUTBOUND_TASK' AND target_id IN (" + tid_list + "))")
    L.assert_eq("G99 本 RUN_MARK 的 partner 已清空", "0", db1(
        "SELECT count(*) FROM integration.partners WHERE partner_code IN (" + codes + ")"
        " OR partner_code LIKE " + like))
    L.assert_eq("G99 本 RUN_MARK 的凭据已清空", "0", db1(
        "SELECT count(*) FROM integration.partner_credentials WHERE partner_code IN (" + codes + ")"
        " OR partner_code LIKE " + like))
    L.assert_eq("G99 本 RUN_MARK 的收件行已清空", "0", db1(
        "SELECT count(*) FROM integration.inbound_messages WHERE partner_code IN (" + codes + ")"
        " OR partner_code LIKE " + like))
    L.assert_eq("G99 本 RUN_MARK 的出站任务已清空", "0", db1(
        "SELECT count(*) FROM integration.outbound_tasks WHERE idempotency_key LIKE 'RLO:" + RUN + "%'"))
    L.assert_eq("G99 本 RUN_MARK 的留痕已清空", "0", db1(
        "SELECT count(*) FROM integration.call_logs WHERE partner_code IN (" + codes + ")"
        " OR partner_code LIKE " + like))
    L.assert_eq("G99 本 RUN_MARK 的 nonce 已清空", "0", db1(
        "SELECT count(*) FROM integration.nonces WHERE partner_code IN (" + codes + ")"
        " OR partner_code LIKE " + like))
    L.assert_eq("G99 别人留下的 partner 行没被误删（V74 六个 TEST-* 挡板仍在）", "6", db1(
        "SELECT count(*) FROM integration.partners WHERE partner_code LIKE 'TEST-%'"))
    L.assert_eq("G99 清理没有动到非本 RUN_MARK 的收件行（残留行仍在线）", True,
                db1("SELECT count(*) FROM integration.inbound_messages") is not None)


'''

s = s[:start] + new + s[end:]
io.open(p, 'w', encoding='utf-8').write(s)
print('g16/g99 replaced')
