# -*- coding: utf-8 -*-
"""IntegrationOpsController 两处 javadoc 纠偏 + 冒烟里 direction 断言改口径（临时工具，跑完即删）。"""
import io

P = "web/src/main/java/com/company/floorplan/web/controller/IntegrationOpsController.java"
s = io.open(P, encoding="utf-8").read()
n = 0


def rep(t, a, b, tag):
    global n
    assert t.count(a) == 1, "anchor %s -> %d" % (tag, t.count(a))
    t = t.replace(a, b, 1)
    n += 1
    return t


s = rep(s, '''     * @param direction INBOUND / OUTBOUND；留空两侧都回''',
        '''     * @param direction {@code IN} / {@code OUT}；留空两侧都回。
     *                  <b>取值就是 {@code call_logs.direction} 的列面值</b>（V74 的
     *                  {@code chk_calllog_direction} 只允许这两个），本参数不做枚举翻译：
     *                  传 {@code INBOUND} 不报 400，而是条件永不命中、回空页——
     *                  串行第 3 棒冒烟实测过这个形状，别再把它写回成 INBOUND/OUTBOUND。''', "1")

s = rep(s, ''' * <p><b>本类是 W-U3 管理面在 core 侧唯一直接注入仓储的读口</b>：留痕查询没有业务规则，
 * 包一层空转发 service 只会让读者以为规则在那一层。既有先例是
 * {@code JobController}/{@code VehicleController} 直接注入 {@code SupportRepository}/{@code *Repository}。</p>
 */''',
        ''' * <p><b>读留痕这一支直接注入仓储，不再包一层空转发 service</b>：留痕查询没有业务规则，
 * 包一层只会让读者以为规则在那一层。既有先例是
 * {@code JobController}/{@code VehicleController} 直接注入 {@code SupportRepository}/{@code *Repository}。
 * 上面那句"只读"也只限定这一支——收件箱与出站任务的四个口（两组查询 + 两个重放）
 * 是带状态机的写操作，全部经 {@link IntegrationOpsApplicationService}，本类不碰 SQL。</p>
 *
 * <p><b>拒绝也是一次运维动作</b>：两个重放口在状态不满足时不静默返回"没做成"，
 * 而是先落一条 {@code INT_INBOX_REDELIVER_REJECTED} / {@code INT_OUTBOUND_REPLAY_REJECTED}
 * 审计再抛 {@code CONFLICT_001}(409)。动作名用 {@code INT_} 缩写而不是"成功动作名 + _REJECTED"，
 * 因为 {@code audit_events.action} 是 varchar(32)——拼串会把 409 顶成 500，
 * 实测过程写在服务类同名字段的注释里。</p>
 */''', "2")

io.open(P, "w", encoding="utf-8", newline="").write(s)

Q = "scripts/smoke_used_wu3.py"
t = io.open(Q, encoding="utf-8").read()
t = rep(t, '''    L.assert_eq("G16 缺口：控制器 javadoc 写 direction=INBOUND，但 V74 列值是 IN（按文档值查不到）",
                True, len(data_list_of(n_ok[1])) > len(data_list_of(n_doc[1])))''',
        '''    # direction 不做枚举翻译：取值就是 V74 chk_calllog_direction 允许的两个列面值。
    # 传 INBOUND 不报 400，而是条件永不命中、回空页（控制器 javadoc 本轮已按实测改成 IN/OUT）。
    L.assert_eq("G16 实况：direction 按列面值 IN 才命中，写 INBOUND 回空页而不报 400",
                True, len(data_list_of(n_ok[1])) > len(data_list_of(n_doc[1])))''', "3")
io.open(Q, "w", encoding="utf-8", newline="").write(t)
print("patch19 applied", n, "edits")
