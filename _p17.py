import io
P = "application/src/main/java/com/company/floorplan/application/service/IntegrationOpsApplicationService.java"
s = io.open(P, encoding="utf-8").read()
n = 0

a = r'''    /** 审计动作名（要能被 {@code GET /api/audit-events?action=} 直接检索，故写成常量而不是拼字符串）。 */
    private static final String AUDIT_INBOX_REDELIVER = "INTEGRATION_INBOX_REDELIVERED";
    private static final String AUDIT_OUTBOUND_REPLAY = "INTEGRATION_OUTBOUND_REPLAYED";'''
b = r'''    /** 审计动作名（要能被 {@code GET /api/audit-events?action=} 直接检索，故写成常量而不是拼字符串）。 */
    private static final String AUDIT_INBOX_REDELIVER = "INTEGRATION_INBOX_REDELIVERED";
    private static final String AUDIT_OUTBOUND_REPLAY = "INTEGRATION_OUTBOUND_REPLAYED";

    /**
     * 拒绝留痕的动作名。
     *
     * <p><b>为什么不复用成功动作名再拼 {@code "_REJECTED"}</b>：{@code audit_events.action} 是
     * {@code varchar(32)}（V2 建表，八个 schema 全一致，本轮 information_schema 实测），
     * 而 {@code INTEGRATION_INBOX_REDELIVERED + "_REJECTED"} 有 38 个字符 ⟹ 写审计直接抛
     * {@code value too long for type character varying(32)}，一次本该 409 的业务拒绝
     * 变成 500（串行第 3 棒 08:32:17 实测：{@code POST /api/integration/inbox/25/redeliver -> 500}）。
     * 拒绝因此用 {@code INT_} 前缀的独立常量（28/29 字符），并且和成功动作一样是常量而非拼串
     * ——检索时 {@code action=INT_INBOX_REDELIVER_REJECTED} 一次就能点中。</p>
     */
    private static final String AUDIT_INBOX_REDELIVER_REJECTED = "INT_INBOX_REDELIVER_REJECTED";
    private static final String AUDIT_OUTBOUND_REPLAY_REJECTED = "INT_OUTBOUND_REPLAY_REJECTED";

    /** {@code audit_events.action} 的列宽：新增动作名时先过这条，别等运行期撞 varchar(32)。 */
    static final int AUDIT_ACTION_MAX_LEN = 32;'''
assert s.count(a) == 1, "a1"; s = s.replace(a, b, 1); n += 1

a = r'''            throw reject(AUDIT_INBOX_REDELIVER, TARGET_INBOX, String.valueOf(messageId), decision, actor, traceId);'''
b = r'''            throw reject(AUDIT_INBOX_REDELIVER, AUDIT_INBOX_REDELIVER_REJECTED, TARGET_INBOX,
                    String.valueOf(messageId), decision, actor, traceId);'''
assert s.count(a) == 1, "a2"; s = s.replace(a, b, 1); n += 1

a = r'''            throw reject(AUDIT_OUTBOUND_REPLAY, TARGET_OUTBOUND, String.valueOf(taskId), decision, actor, traceId);'''
b = r'''            throw reject(AUDIT_OUTBOUND_REPLAY, AUDIT_OUTBOUND_REPLAY_REJECTED, TARGET_OUTBOUND,
                    String.valueOf(taskId), decision, actor, traceId);'''
assert s.count(a) == 1, "a3"; s = s.replace(a, b, 1); n += 1

a = r'''    private BusinessException reject(String action, String targetType, String targetId, Decision decision,
                                     String actor, String traceId) {
        support.writeAudit(actor, action + "_REJECTED", targetType, targetId,'''
b = r'''    private BusinessException reject(String action, String rejectedAction, String targetType, String targetId,
                                     Decision decision, String actor, String traceId) {
        // 列宽只有 32：越界时在写库之前先夹住并留下可检索的原名，而不是让 PSQLException 把 409 顶成 500。
        String name = rejectedAction.length() <= AUDIT_ACTION_MAX_LEN
                ? rejectedAction : rejectedAction.substring(0, AUDIT_ACTION_MAX_LEN);
        support.writeAudit(actor, name, targetType, targetId,'''
assert s.count(a) == 1, "a4"; s = s.replace(a, b, 1); n += 1

io.open(P, "w", encoding="utf-8", newline="").write(s)
print("patch17 applied", n, "edits")
