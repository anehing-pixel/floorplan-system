import io
P = "scripts/smoke_used_wu3.py"
s = io.open(P, encoding="utf-8").read()
o = len(s)

# 1) G99 的删除段抽成可重入函数
a = r'''def g99_purge():
    print("\n--- G99 清理（只按本 RUN_MARK 删自己造的行） ---")
    codes = q_in(MY_PARTNERS)'''
b = r'''def purge_run_rows():
    """按本 RUN_MARK 删掉自己造的行（integration 六张表 + 本 agent schema 的 outbox/audit 留痕）。

    单列成一个函数是为了**可重跑**：`integration` 是全局 schema，不随 agent schema 的 drop/init 而清，
    上一轮若在 G99 之前崩掉（本轮就崩过两次），`integration.partners` 里就留着本 RUN_MARK 的同码行，
    而 partner_code 上有唯一索引 ⟹ 下一轮 G2 建档直接撞约束、7 条轮换跟着一起红。
    所以 G2 建档前先调一次，把判据从"看上一轮跑没跑完"换成"本轮确定从零开始"。
    删的口径不变：只按自己的 partner_code（含 PA 前缀的临时档）与自己的业务号删，
    不 TRUNCATE、不无条件 DELETE，别人的行（V74 的 TEST-* 挡板）一条都不碰。
    """
    codes = q_in(MY_PARTNERS)'''
assert s.count(a) == 1, "a1"
s = s.replace(a, b, 1)

# 2) 删除段结束、核验断言开始处切开
a = r'''    L.assert_eq("G99 本 RUN_MARK 的 partner 已清空"'
    L.assert_eq("G99 本 RUN_MARK 的 partner 已清空", "0"'''
b = r'''    return codes, like, id_list, tid_list


def g99_purge():
    print("\n--- G99 清理（只按本 RUN_MARK 删自己造的行） ---")
    codes, like, id_list, tid_list = purge_run_rows()
    L.assert_eq("G99 本 RUN_MARK 的 partner 已清空", "0"'''
assert s.count(a.rstrip("\n").split("\n")[-1]) == 1, "a2"
s = s.replace(a.rstrip("\n").split("\n")[-1], b, 1)

# 3) G2 建档前预清
a = r'''    print("\n--- G2 partner 建号 + 首版密钥轮换（PARTNER_KEY_ROTATE 真实用过一次） ---")'''
b = r'''    print("\n--- G2 partner 建号 + 首版密钥轮换（PARTNER_KEY_ROTATE 真实用过一次） ---")
    # 先清自己上一轮的残留，理由见 purge_run_rows 的文档注释（全局 schema + 唯一索引 = 不可重跑）。
    print("[INFO] G2 预清本 RUN_MARK 的残留行（只删自己造的码，不 TRUNCATE、不碰别人的 partner_code）")
    purge_run_rows()'''
assert s.count(a) == 1, "a3"
s = s.replace(a, b, 1)

io.open(P, "w", encoding="utf-8", newline="").write(s)
print("patch16 ok bytes=%d->%d" % (o, len(s)))
