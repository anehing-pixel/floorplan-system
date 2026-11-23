#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""smoke_used_wu4.py —— W-U4 包（凭证质押 / WMS 与放车放证闭环）迁移与门禁冒烟。

判据口径
--------
本脚本的主角是 **V65–V69 这五个迁移在库里真实长成了什么形状**，以及这些形状
是不是真的拦得住违规数据。HTTP 只在最后一节用来做 LEGACY 回归（现役放车链路
被我 ALTER 过 release_orders / release_receipts，必须证明没被改坏）。
所以除最后一节外，每条断言都是"插一条、回库里数行数"，不认 HTTP 码。

为什么必须有"插一条被拒"这一类：CHECK 与部分唯一索引写成 `NOT VALID` 或谓词写错时，
迁移照样 success=true、表照样建出来、列照样在——只看 information_schema
会全绿，而防线其实一条都没生效。这类假绿只有负向插入能暴露。

运行前提（缺任何一条，结论无效）
--------------------------------
1) ``FLOORPLAN_DB_SCHEMA=used_wu4``：脚本与服务必须同一个 search_path，
   否则服务写 used_wu4、脚本查 public，断言照样全绿（agent_smoke_lib 头注警告的就是这个）。
   本脚本第 0 节会实测 current_schema()，不对就直接终止，不往下跑。
2) ``SMOKE_BASE=http://127.0.0.1:18880``：走网关，与主干 18080 隔离（端口偏移 +800）。
3) web 已启动过并完成 Flyway（只有 web 跑迁移）。

不覆盖什么（如实声明，别把这份报告当成"W-U4 已验收"）
------------------------------------------------------
* 本包 21 个 D07 端点**一个都还没实现**（domain 枚举/状态机/门禁已落，
  Controller 与 application 服务未写），所以本脚本不测新端点，测了就是假绿。
* ``inspection_certificates`` / ``certificate_custody_events`` 属 V52 / W-U1，
  本分支库里没有 ⇒ 凭证链（R5）无法端到端验证，第 11 节按 BLOCKED 登记，不计 PASS。
* ``inventory_releases``(R9) / ``integration.outbound_tasks``(W-U3) 同理缺席 ⇒
  证据链第④⑥条不可取证。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from agent_smoke_lib import (  # noqa: E402
    BASE, DB_SCHEMA, assert_eq, assert_fail, get, login, login_as, ok,
    psql, psql_exec, psql_one, post, summary, wait_ready,
)

SCHEMA = (DB_SCHEMA or "").strip()
TAG = "WU4SMK"                      # 所有夹具都用这个前缀，便于开头/结尾清理
VIN = TAG + "VIN000000001"[:32 - len(TAG)]
WH = TAG + "WH"
RUN_DATE = "2026-11-22"

# 我在 domain/.../model/UsedReleaseStatus.java 里落的十态；
# 与 V67 的 chk_release_order_status 取值集合必须逐字相同——本节就是核这个 parity。
TEN_STATES = ["PENDING_RELEASE", "SENT", "ACKNOWLEDGED", "PARTIAL", "CONFIRMED",
              "RELEASED", "LIEN_DISCHARGED", "REJECTED", "EXPIRED", "CANCELLED"]
# LEGACY 六态（V19 起现役、ReleaseStateMachineTest 36 组白名单依赖），必须仍是十态的子集。
LEGACY_SIX = ["PENDING_RELEASE", "SENT", "CONFIRMED", "RELEASED", "LIEN_DISCHARGED", "CANCELLED"]


def q_int(sql):
    """取一个标量并转 int；查不到返回 -1（不用 0，避免"没有行"和"0 行"混淆）。"""
    v = psql_one(sql)
    return int(v) if v not in (None, "") else -1


def accepts(msg, sql):
    """正向：这条 SQL 必须执行成功（用于验证 CHECK 没把合法值一起挡掉）。"""
    landed = psql_exec(sql)
    return ok(bool(landed), msg, "SQL 被拒: %s" % sql[:140])


def rejects(msg, sql, probe_sql, expect=0):
    """负向：这条 SQL 必须被库拒掉，**且回库里确认行数确实停在 expect**。

    两段判定缺一不可：psql 返回码非 0 只说明"报错了"，可能是语法错、也可能是
    撞了别的约束；probe 再数一次才说明"这条违规数据确实没进库"。

    expect 默认 0（CHECK 类：违规行压根不该存在）。唯一索引类要传 1——
    那个键位上本来就有一条合法行在，探针数的是"键位上的总行数"，
    判 0 会把"合法行还在"误读成"违规行插进去了"（本轮第一轮就踩过这个）。
    """
    landed = psql_exec(sql)
    still = q_int(probe_sql)
    return ok((not landed) and still == expect, msg,
              "landed=%s 键位行数=%s（期望 %s）" % (bool(landed), still, expect))


def cleanup():
    """删掉本脚本自己的夹具。只碰 used_wu4（search_path 由 env 决定），不碰 public。

    圈定范围一律按 `vin LIKE 'WU4SMK%'` 而不是按 order_no：第 12 节走真实服务建单，
    单号是 `nextOrderNo()` 生成的，用前缀匹配 order_no 会漏掉它，跑两轮就留下一堆脏指令。
    """
    scope = "(SELECT id FROM release_orders WHERE vin LIKE '%s%%')" % TAG
    for sql in [
        "DELETE FROM release_receipts WHERE release_order_id IN %s OR receipt_no LIKE '%s-%%'"
        % (scope, TAG),
        "DELETE FROM release_order_items WHERE vin LIKE '%s%%'" % TAG,
        "DELETE FROM release_state_history WHERE order_id IN %s" % scope,
        "DELETE FROM release_orders WHERE vin LIKE '%s%%' OR order_no LIKE '%s-%%'" % (TAG, TAG),
        "DELETE FROM wms_inventory_items WHERE vin LIKE '%s%%'" % TAG,
        "DELETE FROM wms_inventory_snapshots WHERE warehouse_custodian_code = '%s'" % WH,
        "DELETE FROM wms_reconcile_diffs WHERE warehouse_custodian_code = '%s'" % WH,
        "DELETE FROM wms_inbound_confirms WHERE warehouse_custodian_code = '%s'" % WH,
        "DELETE FROM registration_applications WHERE apply_no LIKE '%s-%%'" % TAG,
        "DELETE FROM vehicle_units WHERE vin LIKE '%s%%'" % TAG,
        "DELETE FROM custodians WHERE custodian_code LIKE '%s-%%'" % TAG,
    ]:
        psql_exec(sql)


# ---------------------------------------------------------------------------
# 0. 隔离真实性：先证明"我现在看的确实是 used_wu4，而且没污染 public"
# ---------------------------------------------------------------------------
print("\n===== 0 隔离真实性（这一节不过，后面全部无效）=====")
if SCHEMA != "used_wu4":
    raise RuntimeError("FLOORPLAN_DB_SCHEMA=%r，不是 used_wu4 —— 冒烟会查错 schema，"
                       "断言再绿也不是本包的结论" % SCHEMA)
cur = psql_one("SELECT current_schema()")
if cur != "used_wu4":
    raise RuntimeError("current_schema()=%r —— search_path 没挂上，脚本在读 public" % cur)
assert_eq("脚本会话 current_schema=used_wu4", "used_wu4", cur)

# 服务侧也必须在同一个 schema：查 used_wu4 的 Flyway 历史里有 v69 才算数
assert_eq("used_wu4 已到 v69", "69",
          psql_one("SELECT version FROM used_wu4.flyway_schema_history "
                   "ORDER BY installed_rank DESC LIMIT 1"))

# 主干唯一真库 public 绝不能出现本包未合并的对象：这条是 W-U4 侧的自证清白。
# 不是"顺手一查"——V65–V69 一旦落进 public，主干 flyway_schema_history 就记下五笔
# 未合并迁移，W-U1/2/3/5 任何一路在 public 起服务都会与它对账，收口必炸。
leaked = q_int("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' "
               "AND table_name IN ('wms_inventory_snapshots','wms_inventory_items',"
               "'wms_reconcile_diffs','wms_inbound_confirms','registration_applications')")
assert_eq("public 里没有本包的五张新表（未污染主干真库）", 0, leaked)
pub_hist = q_int("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' "
                 "AND table_name='flyway_schema_history'")
if pub_hist == 1:
    bad = q_int("SELECT count(*) FROM public.flyway_schema_history "
                "WHERE version::int BETWEEN 65 AND 69")
    assert_eq("public.flyway_schema_history 里没有 V65–V69", 0, bad)
else:
    print("[BLOCKED] public 里没有 flyway_schema_history（主干从未在本库跑过迁移），"
          "这一项无从比对")

# 表数基准：主干 67 + 本包 5 张（agent_env.sh:144 明确"多于基准多半是主干涨了"，
# 所以这里是比对本包自己的 72，不改脚本默认值）
assert_eq("used_wu4 表数 = 67 基准 + 本包 5 张", 72,
          q_int("SELECT count(*) FROM information_schema.tables WHERE table_schema='used_wu4' "
                "AND table_type='BASE TABLE'"))

# 夹具先清一遍：本脚本要重复跑（改断言、改迁移后都要复跑），
# 而十态那一节是按 order_no 逐值插的，上一轮的残留会把这一轮的负向断言变成假绿。
cleanup()

# ---------------------------------------------------------------------------
# 1. Flyway：五笔逐字名、逐笔 success
# ---------------------------------------------------------------------------
print("\n===== 1 V65–V69 落库实况 =====")
rows = psql("SELECT version||'|'||description||'|'||success FROM used_wu4.flyway_schema_history "
            "WHERE version::int BETWEEN 65 AND 69 ORDER BY version::int")
got = {int(r[0].split("|")[0]): r[0] for r in (rows or [])}
assert_eq("五笔迁移都在历史表里", 5, len(got))
for v, name in [(65, "custodian wms"), (66, "wms snapshot reconcile"),
                (67, "release order used extension"), (68, "release item receiver"),
                (69, "registration application")]:
    line = (rows[v - 65][0] if rows and len(rows) >= v - 64 else "")
    ok(("%s|" % v) in line and name in line and "true" in line,
       "V%s 落库且 success=true（描述逐字：%s）" % (v, name), "实际行=%r" % line)

# ---------------------------------------------------------------------------
# 2. 形状：五张新表 + 各表新列
# ---------------------------------------------------------------------------
print("\n===== 2 表 / 列形状 =====")
for t in ["wms_inbound_confirms", "wms_inventory_snapshots", "wms_inventory_items",
          "wms_reconcile_diffs", "registration_applications"]:
    assert_eq("新表 %s 存在" % t, 1,
              q_int("SELECT count(*) FROM information_schema.tables WHERE table_schema='used_wu4' "
                    "AND table_name='%s'" % t))

NEW_COLS = {
    "custodians": ["channel_capability", "service_window", "reconcile_cycle", "country",
                   "partner_code"],
    "release_orders": ["inspection_certificate_no", "wms_warehouse_code", "auction_lot_id",
                       "acked_at", "expired_at", "partial_at", "lien_extinguished_at",
                       "last_inbound_message_id", "receipt_degraded", "dispatch_seq",
                       "product_line"],
    "release_order_items": ["receiver_custodian_code", "receiver_partner_code", "item_status",
                            "receipt_id", "refuse_reason"],
    # exception_type / inbound_message_id 由更早的迁移就有（V19 / V67），V68 是幂等重挂
    "release_receipts": ["partner_code", "signature_ok", "actual_items", "photo_paths",
                         "exception_type", "inbound_message_id", "item_type", "release_result"],
    "vehicle_units": ["settle_seq"],
}
for tbl, cols in NEW_COLS.items():
    n = q_int("SELECT count(*) FROM information_schema.columns WHERE table_schema='used_wu4' "
              "AND table_name='%s' AND column_name = ANY(ARRAY[%s])"
              % (tbl, ",".join("'%s'" % c for c in cols)))
    assert_eq("%s 的 %d 个新列全部到位" % (tbl, len(cols)), len(cols), n)

# V67/V68 的默认值：这两条决定现役写入路径会不会因为新列缺省而炸
assert_eq("release_receipts.exception_type 默认 'NONE'（V68 回填后设的）",
          "'NONE'::character varying",
          psql_one("SELECT column_default FROM information_schema.columns "
                   "WHERE table_schema='used_wu4' AND table_name='release_receipts' "
                   "AND column_name='exception_type'"))
assert_eq("release_orders.receipt_degraded 默认 false 且 NOT NULL", "NO",
          psql_one("SELECT is_nullable FROM information_schema.columns "
                   "WHERE table_schema='used_wu4' AND table_name='release_orders' "
                   "AND column_name='receipt_degraded'"))
# product_line 刻意可空无默认：D04 §2 把它的语义基座放在 V40（W-U1），
# 本轮若在这里写 DEFAULT 'LEGACY' 就是替 W-U1 定口径（V67:77 的注释记着这件事）
pl = psql("SELECT is_nullable||'|'||COALESCE(column_default,'~') FROM information_schema.columns "
          "WHERE table_schema='used_wu4' AND table_name='release_orders' "
          "AND column_name='product_line'")
assert_eq("release_orders.product_line 可空且无默认（基座留给 W-U1 的 V40）",
          ["YES|~"], [r[0] for r in (pl or [])])

# ---------------------------------------------------------------------------
# 3. 十态 CHECK 与 domain 枚举 parity（正反各一轮）
# ---------------------------------------------------------------------------
print("\n===== 3 release_orders 十态：合法值全过、非法值全拒 =====")
base_order = ("INSERT INTO release_orders (order_no, redemption_id, dealer_id, vin, "
              "release_items, valid_until, status) VALUES ")
for st in TEN_STATES:
    psql_exec("DELETE FROM release_orders WHERE order_no='%s-ST-%s'" % (TAG, st))
    accepts("十态之 %s 可写入（CHECK 没误伤合法值）" % st,
            "%s ('%s-ST-%s', 900001, 900002, '%s', 'VEHICLE', now()+interval '7 day', '%s')"
            % (base_order, TAG, st, VIN, st))
rejects("第 11 个状态值 LEGACY_ONLY_FOO 被 chk_release_order_status 拒掉",
        "%s ('%s-ST-BAD', 900001, 900002, '%s', 'VEHICLE', now()+interval '7 day', 'LEGACY_ONLY_FOO')"
        % (base_order, TAG, VIN),
        "SELECT count(*) FROM release_orders WHERE order_no='%s-ST-BAD'" % TAG)
missing = [s for s in LEGACY_SIX if s not in TEN_STATES]
assert_eq("LEGACY 六态仍是十态子集（D03 §3.2 兼容底线）", [], missing)

# ---------------------------------------------------------------------------
# 4. WMS 快照：状态 CHECK + 部分唯一索引的谓词
# ---------------------------------------------------------------------------
print("\n===== 4 wms_inventory_snapshots：当日仅一份有效快照 =====")
snap = ("INSERT INTO wms_inventory_snapshots (snapshot_key, warehouse_custodian_code, "
        "snapshot_date, partner_code, status, item_count) VALUES ")
accepts("首份快照 RECEIVED 可落库",
        "%s ('SNAP:%s-%s', '%s', '%s', 'WU4PARTNER', 'RECEIVED', 0)" % (snap, WH, "20261122", WH, RUN_DATE))
rejects("同仓同业务日第二份未终结快照被 uk_wms_snapshot_active 拒掉",
        "%s ('SNAP:%s-B', '%s', '%s', 'WU4PARTNER', 'RECEIVED', 0)" % (snap, WH, WH, RUN_DATE),
        "SELECT count(*) FROM wms_inventory_snapshots WHERE snapshot_key='SNAP:%s-B'" % WH)
# 谓词正向半边：旧快照置 SUPERSEDED 后，同一 (仓, 日) 必须能再进一份——
# 这正是 D07 §7.5 的 WMS_003「覆盖而非失败」路径在库层的依据
psql_exec("UPDATE wms_inventory_snapshots SET status='SUPERSEDED' WHERE snapshot_key='SNAP:%s-%s'"
          % (WH, "20261122"))
accepts("旧快照置 SUPERSEDED 后同仓同日可再进一份（部分唯一的谓词半边）",
        "%s ('SNAP:%s-C', '%s', '%s', 'WU4PARTNER', 'APPLIED', 0)" % (snap, WH, WH, RUN_DATE))
rejects("快照状态第四值 'ARCHIVED' 被 chk_wms_snapshot_status 拒掉",
        "%s ('SNAP:%s-D', '%s', '%s', 'WU4PARTNER', 'ARCHIVED', 0)" % (snap, WH, WH, RUN_DATE),
        "SELECT count(*) FROM wms_inventory_snapshots WHERE snapshot_key='SNAP:%s-D'" % WH)
rejects("snapshot_key 唯一（同幂等键不得两行）",
        "%s ('SNAP:%s-C', '%s', '%s', 'WU4PARTNER', 'RECEIVED', 0)" % (snap, WH, WH, RUN_DATE),
        "SELECT count(*) FROM wms_inventory_snapshots WHERE snapshot_key='SNAP:%s-C' AND status='RECEIVED'" % WH)
sid = q_int("SELECT id FROM wms_inventory_snapshots WHERE snapshot_key='SNAP:%s-C'" % WH)
accepts("快照明细可挂到快照上",
        "INSERT INTO wms_inventory_items (snapshot_id, vin, bin_location) VALUES (%d, '%s-I1', 'A-01')"
        % (sid, VIN))
rejects("同快照同 VIN 不得两条明细（uk_wms_item_snapshot_vin）",
        "INSERT INTO wms_inventory_items (snapshot_id, vin) VALUES (%d, '%s-I1')" % (sid, VIN),
        "SELECT count(*) FROM wms_inventory_items WHERE snapshot_id=%d AND vin='%s-I1'" % (sid, VIN),
        expect=1)

# ---------------------------------------------------------------------------
# 5. 账实差异：六类 / 四级 / 状态机列 / 忽略留痕
# ---------------------------------------------------------------------------
print("\n===== 5 wms_reconcile_diffs：差异分类与忽略证据链 =====")
diff = ("INSERT INTO wms_reconcile_diffs (biz_date, warehouse_custodian_code, vin, diff_type, "
        "severity, status) VALUES ")
accepts("MISSING_IN_WMS 配 HIGH 可落（缺口方向按高危固定）",
        "%s ('%s', '%s', '%s-D1', 'MISSING_IN_WMS', 'HIGH', 'OPEN')" % (diff, RUN_DATE, WH, VIN))
rejects("MISSING_IN_WMS 配 WARNING 被 chk_wms_diff_missing_is_high 拒掉",
        "%s ('%s', '%s', '%s-D2', 'MISSING_IN_WMS', 'WARNING', 'OPEN')" % (diff, RUN_DATE, WH, VIN),
        "SELECT count(*) FROM wms_reconcile_diffs WHERE vin='%s-D2'" % VIN)
rejects("第七种 diff_type 'VIN_DUP' 被 chk_wms_diff_type 拒掉",
        "%s ('%s', '%s', '%s-D3', 'VIN_DUP', 'HIGH', 'OPEN')" % (diff, RUN_DATE, WH, VIN),
        "SELECT count(*) FROM wms_reconcile_diffs WHERE vin='%s-D3'" % VIN)
rejects("第五级 severity 'FATAL' 被 chk_wms_diff_severity 拒掉",
        "%s ('%s', '%s', '%s-D4', 'EXTRA_IN_WMS', 'FATAL', 'OPEN')" % (diff, RUN_DATE, WH, VIN),
        "SELECT count(*) FROM wms_reconcile_diffs WHERE vin='%s-D4'" % VIN)
rejects("非 OPEN 状态但无处置人（chk_wms_diff_actor_present）",
        "UPDATE wms_reconcile_diffs SET status='ACKNOWLEDGED' WHERE vin='%s-D1'" % VIN,
        "SELECT count(*) FROM wms_reconcile_diffs WHERE vin='%s-D1' AND status='ACKNOWLEDGED' AND actor IS NULL" % VIN)
rejects("IGNORED 但无忽略理由（chk_wms_diff_ignore_evidence 第一层）",
        "UPDATE wms_reconcile_diffs SET status='IGNORED', actor='wu4_a', ignored_by='wu4_a', "
        "co_signed_by='wu4_b', ignore_reason=NULL WHERE vin='%s-D1'" % VIN,
        "SELECT count(*) FROM wms_reconcile_diffs WHERE vin='%s-D1' AND status='IGNORED'" % VIN)
rejects("IGNORED 双签同一人（chk_wms_diff_ignore_evidence 二人层）",
        "UPDATE wms_reconcile_diffs SET status='IGNORED', actor='wu4_a', ignored_by='wu4_a', "
        "co_signed_by='wu4_a', ignore_reason='仓侧确认错录' WHERE vin='%s-D1'" % VIN,
        "SELECT count(*) FROM wms_reconcile_diffs WHERE vin='%s-D1' AND ignored_by=co_signed_by AND status='IGNORED'" % VIN)
accepts("IGNORED 带理由且双签不同人可落（D-7 承认差异的正路）",
        "UPDATE wms_reconcile_diffs SET status='IGNORED', actor='wu4_a', ignored_by='wu4_a', "
        "co_signed_by='wu4_b', ignore_reason='仓侧确认错录' WHERE vin='%s-D1'" % VIN)
rejects("同 (业务日, 仓, VIN, 类型) 不得开第二条差异（uk_wms_diff_daily_vin）",
        "%s ('%s', '%s', '%s-D1', 'MISSING_IN_WMS', 'HIGH', 'OPEN')" % (diff, RUN_DATE, WH, VIN),
        "SELECT count(*) FROM wms_reconcile_diffs WHERE vin='%s-D1' AND status='OPEN'" % VIN)
# 仓级差异走另一半部分唯一：vin IS NULL 时按 (日, 仓, 类型) 去重。
# 必须先让正例落库，否则"第二条被拒"根本无从发生——只写负例会得到一个恒真的假绿。
# 【发现，需裁决】D04:455 只规定了唯一键 (biz_date, wh, vin, diff_type) 一个，
# 而 D04:450 的六个 diff_type 全是 VIN 级语义（MISSING/EXTRA/CERT/BIN/ODOMETER/CONDITION），
# 没有任何一个是"仓级合计差异"。所以 uk_wms_diff_daily_wh 这条 vin IS NULL 分支
# 目前**没有合法生产者**：我建它是为了补 PostgreSQL 里 NULL 不参与 UNIQUE 去重的洞
# （否则仓级行会无限重复），但类型集合没跟着扩——本轮不自造第七个值（§3.6 反自造规格），
# 这里只能借一个 VIN 级值把索引谓词本身验掉。语义要不要给仓级行开一个值，请收口方裁。
wh_diff = ("INSERT INTO wms_reconcile_diffs (biz_date, warehouse_custodian_code, vin, diff_type, "
           "severity, status) VALUES ")
accepts("仓级差异行（vin 为空）在库层可落一条（借 BIN_MISMATCH 验谓词，非语义结论）",
        "%s ('%s', '%s', NULL, 'BIN_MISMATCH', 'WARNING', 'OPEN')" % (wh_diff, RUN_DATE, WH))
rejects("仓级差异（VIN 为空）同 (日, 仓, 类型) 也只许一条（uk_wms_diff_daily_wh）",
        "%s ('%s', '%s', NULL, 'BIN_MISMATCH', 'WARNING', 'OPEN')" % (wh_diff, RUN_DATE, WH),
        "SELECT count(*) FROM wms_reconcile_diffs WHERE vin IS NULL AND diff_type='BIN_MISMATCH'",
        expect=1)

# ---------------------------------------------------------------------------
# 6. 入库确认幂等
# ---------------------------------------------------------------------------
print("\n===== 6 wms_inbound_confirms：三键幂等 =====")
inb = ("INSERT INTO wms_inbound_confirms (inbound_no, warehouse_custodian_code, vin, confirmed_at, "
       "partner_code, source_message_id) VALUES ")
accepts("入库确认可落库",
        "%s ('INB-001', '%s', '%s', now(), 'WU4PARTNER', 700001)" % (inb, WH, VIN))
rejects("同 (仓, VIN, 入库单号) 重复确认被 uk_wms_inbound 拒掉",
        "%s ('INB-001', '%s', '%s', now(), 'WU4PARTNER', 700002)" % (inb, WH, VIN),
        "SELECT count(*) FROM wms_inbound_confirms WHERE inbound_no='INB-001' AND warehouse_custodian_code='%s'" % WH,
        expect=1)

# ---------------------------------------------------------------------------
# 7. 回执：三值权威 + 代人回执必须有入站引用 + inbox 一对一
# ---------------------------------------------------------------------------
print("\n===== 7 release_receipts：确认权威反伪造（三层里的库层那层）=====")
oid = q_int("SELECT id FROM release_orders WHERE order_no='%s-ST-SENT'" % TAG)
rct = ("INSERT INTO release_receipts (receipt_no, release_order_id, released_at, confirmed_at, "
       "source, confirmation_authority, inbound_message_id, partner_code, item_type, release_result) VALUES ")
accepts("INTERNAL_WITNESS 无入站引用可落（代录口径）",
        "%s ('%s-R1', %d, now(), now(), 'PORTAL', 'INTERNAL_WITNESS', NULL, 'WU4PARTNER', 'VEHICLE', 'RELEASED')"
        % (rct, TAG, oid))
rejects("PARTNER_RECEIPT 却没有 inbound_message_id → chk_receipt_partner_needs_inbox 拒掉",
        "%s ('%s-R2', %d, now(), now(), 'PORTAL', 'PARTNER_RECEIPT', NULL, 'WU4PARTNER', 'VEHICLE', 'RELEASED')"
        % (rct, TAG, oid),
        "SELECT count(*) FROM release_receipts WHERE receipt_no='%s-R2'" % TAG)
accepts("PARTNER_RECEIPT 带 inbound_message_id 可落（防伪造的正路）",
        "%s ('%s-R3', %d, now(), now(), 'PORTAL', 'PARTNER_RECEIPT', 800001, 'WU4PARTNER', 'CERTIFICATE', 'PARTIAL')"
        % (rct, TAG, oid))
rejects("同一条入站消息不得生成两份回执（ux_receipt_inbox）",
        "%s ('%s-R4', %d, now(), now(), 'PORTAL', 'PARTNER_RECEIPT', 800001, 'WU4PARTNER', 'VEHICLE', 'ACKNOWLEDGED')"
        % (rct, TAG, oid),
        "SELECT count(*) FROM release_receipts WHERE receipt_no='%s-R4'" % TAG)
rejects("第四种权威 'EXTERNAL_API' 被 chk_receipt_confirmation_authority 拒掉",
        "%s ('%s-R5', %d, now(), now(), 'PORTAL', 'EXTERNAL_API', NULL, 'WU4PARTNER', 'VEHICLE', 'RELEASED')"
        % (rct, TAG, oid),
        "SELECT count(*) FROM release_receipts WHERE receipt_no='%s-R5'" % TAG)
rejects("第七种 release_result 'CANCELLED' 被 chk_receipt_result 拒掉（撤单不是回执结果）",
        "%s ('%s-R6', %d, now(), now(), 'PORTAL', 'INTERNAL_WITNESS', NULL, 'WU4PARTNER', 'VEHICLE', 'CANCELLED')"
        % (rct, TAG, oid),
        "SELECT count(*) FROM release_receipts WHERE receipt_no='%s-R6'" % TAG)
rejects("第七种 item_type 'PLATE' 被 chk_receipt_item_type 拒掉",
        "%s ('%s-R7', %d, now(), now(), 'PORTAL', 'INTERNAL_WITNESS', NULL, 'WU4PARTNER', 'PLATE', 'RELEASED')"
        % (rct, TAG, oid),
        "SELECT count(*) FROM release_receipts WHERE receipt_no='%s-R7'" % TAG)
accepts("exception_type 默认值 NONE 生效（不写该列也合法）",
        "INSERT INTO release_receipts (receipt_no, release_order_id, released_at, confirmed_at, source) "
        "VALUES ('%s-R8', %d, now(), now(), 'PORTAL')" % (TAG, oid))

# ---------------------------------------------------------------------------
# 8. 指令明细项状态
# ---------------------------------------------------------------------------
print("\n===== 8 release_order_items：项状态与接收方三键列 =====")
itm = ("INSERT INTO release_order_items (order_id, vin, item_type, item_status, "
       "receiver_custodian_code, receiver_partner_code) VALUES ")
accepts("项可落 INSTRUCTED 并带接收方三键",
        "%s (%d, '%s', 'VEHICLE', 'INSTRUCTED', '%s', 'WU4PARTNER')" % (itm, oid, VIN, WH))
rejects("第七种项状态 'HELD' 被 chk_roi_item_status 拒掉",
        "%s (%d, '%s', 'CERTIFICATE', 'HELD', '%s', 'WU4PARTNER')" % (itm, oid, VIN, WH),
        "SELECT count(*) FROM release_order_items WHERE vin='%s' AND item_status='HELD'" % VIN)
rejects("第七种项类型 'KEY_FOB' 被 chk_roi_item_type 拒掉",
        "%s (%d, '%s', 'KEY_FOB', 'PENDING', '%s', 'WU4PARTNER')" % (itm, oid, VIN, WH),
        "SELECT count(*) FROM release_order_items WHERE item_type='KEY_FOB'")

# ---------------------------------------------------------------------------
# 9. 登记委托：完成必须有凭证 + 二人 + 状态白名单
# ---------------------------------------------------------------------------
print("\n===== 9 registration_applications：D-2 委托与结果确认分离（库层那层）=====")
reg = ("INSERT INTO registration_applications (apply_no, apply_type, vin, status, applicant_user, "
       "co_signed_by, source_message_id, completed_on, registry_result, receipt_path) VALUES ")
accepts("DRAFT 委托可落（同人未双签也合法：还没到确认环节）",
        "%s ('%s-A1', 'MORTGAGE_DISCHARGE', '%s', 'DRAFT', 'wu4_a', NULL, NULL, NULL, NULL, NULL)"
        % (reg, TAG, VIN))
rejects("同一人自办自确认（chk_reg_two_person）被拒",
        "UPDATE registration_applications SET status='COMPLETED', co_signed_by='wu4_a', "
        "source_message_id=900001, completed_on=now(), registry_result='已抹消', receipt_path='/f/x.pdf' "
        "WHERE apply_no='%s-A1'" % TAG,
        "SELECT count(*) FROM registration_applications WHERE apply_no='%s-A1' AND co_signed_by=applicant_user" % TAG)
accepts("换人双签后可确认完成（D-2 正路）",
        "UPDATE registration_applications SET status='COMPLETED', co_signed_by='wu4_b', "
        "source_message_id=900001, completed_on=now(), registry_result='已抹消', receipt_path='/f/x.pdf' "
        "WHERE apply_no='%s-A1'" % TAG)
rejects("COMPLETED 但无 source_message_id（chk_reg_completed_needs_inbox）被拒",
        "UPDATE registration_applications SET source_message_id=NULL WHERE apply_no='%s-A1'" % TAG,
        "SELECT count(*) FROM registration_applications WHERE apply_no='%s-A1' AND status='COMPLETED' AND source_message_id IS NULL" % TAG)
accepts("回滚为 SUBMITTED 是允许的（未完成态不吃完成证据）",
        "UPDATE registration_applications SET status='SUBMITTED', source_message_id=NULL WHERE apply_no='%s-A1'" % TAG)
rejects("第六种 apply_type 'OTHER' 被 chk_reg_apply_type 拒掉",
        "%s ('%s-A2', 'OTHER', '%s', 'DRAFT', 'wu4_a', NULL, NULL, NULL, NULL, NULL)" % (reg, TAG, VIN),
        "SELECT count(*) FROM registration_applications WHERE apply_no='%s-A2'" % TAG)
rejects("第六种 status 'IN_PROGRESS' 被 chk_reg_status 拒掉",
        "%s ('%s-A3', 'TITLE_TRANSFER', '%s', 'IN_PROGRESS', 'wu4_a', NULL, NULL, NULL, NULL, NULL)" % (reg, TAG, VIN),
        "SELECT count(*) FROM registration_applications WHERE apply_no='%s-A3'" % TAG)
rejects("apply_no 唯一（委托单号是外部锚点）",
        "%s ('%s-A1', 'MORTGAGE_SETUP', '%s', 'DRAFT', 'wu4_a', NULL, NULL, NULL, NULL, NULL)" % (reg, TAG, VIN),
        "SELECT count(*) FROM registration_applications WHERE apply_no='%s-A1'" % TAG,
        expect=1)
# 「内部 confirm 不得伪造 COMPLETED」的库层半边：没有入站引用就进不了 COMPLETED
psql_exec("UPDATE registration_applications SET status='ACCEPTED_BY_AGENT' WHERE apply_no='%s-A1'" % TAG)
rejects("内部确认不得凭空把委托单推到 COMPLETED（§3.2 那条闸门的库层兜底）",
        "UPDATE registration_applications SET status='COMPLETED', completed_on=now(), "
        "registry_result='已登记', receipt_path='/f/y.pdf', co_signed_by='wu4_b' WHERE apply_no='%s-A1'" % TAG,
        "SELECT count(*) FROM registration_applications WHERE apply_no='%s-A1' AND status='COMPLETED' AND source_message_id IS NULL" % TAG)

# ---------------------------------------------------------------------------
# 10. settle_seq 幂等锚点 + custodians 主数据
# ---------------------------------------------------------------------------
print("\n===== 10 vehicle_units.settle_seq 与 custodians 新列 =====")
vu = ("INSERT INTO vehicle_units (vin, dealer_id, status, settle_seq) VALUES ")
accepts("结清水次可写入 vehicle_units",
        "%s ('%s-V1', 900002, 'SETTLED', '%s:ORD001:1')" % (vu, VIN, TAG))
rejects("同一 settle_seq 不得记两次结清（uk_vehicle_settle_seq）",
        "%s ('%s-V2', 900002, 'SETTLED', '%s:ORD001:1')" % (vu, VIN, TAG),
        "SELECT count(*) FROM vehicle_units WHERE vin='%s-V2'" % VIN)
accepts("未结清的行 settle_seq 为 NULL，可以有多条（部分唯一的谓词半边）",
        "%s ('%s-V3', 900002, 'IN_STOCK', NULL)" % (vu, VIN))
accepts("监管方可写 V65 的四个新列",
        "INSERT INTO custodians (custodian_code, custodian_name, custodian_type, "
        "channel_capability, service_window, reconcile_cycle, country, partner_code) "
        "VALUES ('%s-CUST', '冒烟监管仓', 'WMS', 'API,PORTAL', '09:00-18:00', 'DAILY', 'JP', 'WU4PARTNER')" % TAG)
rejects("第七种 custodian_type 'DRONE_YARD' 被 chk_custodian_type 拒掉",
        "INSERT INTO custodians (custodian_code, custodian_name, custodian_type) "
        "VALUES ('%s-CUST2', '野值监管方', 'DRONE_YARD')" % TAG,
        "SELECT count(*) FROM custodians WHERE custodian_code='%s-CUST2'" % TAG)
rejects("channel_capability 混入白名单外的通道段（chk_custodian_channel_capability）",
        "UPDATE custodians SET channel_capability='API,FAX' WHERE custodian_code='%s-CUST'" % TAG,
        "SELECT count(*) FROM custodians WHERE custodian_code='%s-CUST' AND channel_capability='API,FAX'" % TAG)

# ---------------------------------------------------------------------------
# 11. BLOCKED 登记：本分支缺 W-U1 / W-U3 / R9 的对象，不计 PASS 也不算 FAIL
# ---------------------------------------------------------------------------
print("\n===== 11 已知阻塞（不计入断言，等合并后在主干复跑）=====")
for t, owner in [("inspection_certificates", "V52 / W-U1"),
                 ("certificate_custody_events", "V52 / W-U1"),
                 ("inventory_releases", "R9（未签字，本轮不建）"),
                 ("outbound_tasks", "W-U3 / V70+ 号段")]:
    n = q_int("SELECT count(*) FROM information_schema.tables WHERE table_schema='used_wu4' "
              "AND table_name='%s'" % t)
    print("[BLOCKED] %-28s 在 used_wu4 中存在=%s（归属：%s）" % (t, "是" if n else "否", owner))
fk = q_int("SELECT count(*) FROM pg_constraint WHERE connamespace='used_wu4'::regnamespace "
           "AND conrelid='used_wu4.registration_applications'::regclass AND contype='f'")
print("[BLOCKED] registration_applications 指向 inspection_certificates 的 FK：实建 %d 条"
      "（V69:123 的存在性判定在无表时退化为逻辑引用，符合预期，合并后须复验）" % fk)
print("[BLOCKED] R5 凭证链（收证/质押生效/放证）与新端点：本包 Controller 未实现，无从冒烟")

# ---------------------------------------------------------------------------
# 12. LEGACY 回归：现役放车链在我 ALTER 过的表上跑完六态
# ---------------------------------------------------------------------------
print("\n===== 12 LEGACY 回归（经网关 %s，断言看落库）=====" % BASE)
if not wait_ready("/api/releases/1", timeout=90):
    assert_fail("网关可达（release-service 链路）", "90s 内 %s 无响应" % BASE)
else:
    admin_tok = login()  # 拿管理员令牌做"越权应被拒"的对照样本
    tok_col = login_as("collateral", "collateral123")
    ok(bool(tok_col), "担保管理岗可登录（V9 种子 + 我改过的 UserRole 未破坏既有授予）")
    body = {"redemptionId": 900101, "dealerId": 900102, "vin": VIN + "-L",
            "certificateNo": "WU4-CERT-L", "custodianId": None,
            "releaseItems": "VEHICLE,CERTIFICATE", "validUntil": None,
            "deliveryChannel": "MANUAL", "custodianByItem": None}
    # SOD 反向半边：管理员刻意不持 SECURITY_MAINTAIN（"管理员不碰业务单据"）
    st_forbid, res_forbid = post("/api/releases", body, token=admin_tok)
    ok(st_forbid == 403 and res_forbid.get("code") == "AUTH_002",
       "管理员建放车指令被拒（AUTH_002 / 403）", "实得 HTTP %s %s" % (st_forbid, res_forbid))
    st_c, res_c = post("/api/releases", body, token=tok_col)
    lov = q_int("SELECT count(*) FROM release_orders WHERE vin='%s-L'" % VIN)
    ok(st_c == 200 and lov == 1, "担保管理岗建单：HTTP %s，库里真有 1 条指令" % st_c,
       "响应=%s 库内行数=%d" % (str(res_c)[:200], lov))
    lid = q_int("SELECT id FROM release_orders WHERE vin='%s-L'" % VIN)
    if lid > 0:
        st_s, _ = get("/api/releases/%d" % lid, token=tok_col)
        ok(st_s == 200, "新指令可读回（GET /api/releases/{id}）", "HTTP %s" % st_s)
        sent = q_int("SELECT count(*) FROM release_orders WHERE id=%d AND status='SENT'" % lid)
        assert_eq("create 内部已下发到 SENT（LEGACY 语义未变）", 1, sent)
        nulls = q_int("SELECT count(*) FROM release_orders WHERE id=%d AND product_line IS NULL "
                      "AND receipt_degraded=FALSE AND dispatch_seq=1" % lid)
        assert_eq("LEGACY 单子的新列取默认：product_line=NULL / degraded=FALSE / seq=1", 1, nulls)
        for step, path in [("confirm", "/confirm"), ("release", "/release"),
                           ("discharge-lien", "/discharge-lien")]:
            st_a, res_a = post("/api/releases/%d%s" % (lid, path),
                               {"reason": "W-U4 冒烟 %s" % step}, token=tok_col)
            want = {"confirm": "CONFIRMED", "release": "RELEASED",
                    "discharge-lien": "LIEN_DISCHARGED"}[step]
            now_st = psql_one("SELECT status FROM release_orders WHERE id=%d" % lid)
            ok(st_a == 200 and now_st == want,
               "六态推进 %s → 库里 status=%s" % (step, want),
               "HTTP %s 库里实际=%s 响应=%s" % (st_a, now_st, str(res_a)[:160]))
        ts_missing = q_int("SELECT count(*) FROM release_orders WHERE id=%d AND (sent_at IS NULL "
                           "OR confirmed_at IS NULL OR released_at IS NULL "
                           "OR lien_discharged_at IS NULL)" % lid)
        assert_eq("四个时间戳列全部写上（V67 的 lien_extinguished_at 没抢现有列的活）", 0, ts_missing)
        hist = q_int("SELECT count(*) FROM release_state_history WHERE order_id=%d" % lid)
        ok(hist >= 5, "状态历史记满 CREATE/DISPATCH/CONFIRM/RELEASE/DISCHARGE（实得 %d 条）" % hist)
        rcpt = q_int("SELECT count(*) FROM release_receipts WHERE release_order_id=%d" % lid)
        ok(rcpt >= 1, "confirm 代录了回执（库里回执行数 %d）" % rcpt)
        auth_bad = q_int("SELECT count(*) FROM release_receipts WHERE release_order_id=%d "
                         "AND confirmation_authority IS NULL" % lid)
        assert_eq("代录回执的确认权威非空（三值 CHECK 有默认值可填）", 0, auth_bad)
    else:
        assert_fail("放车指令建单", "库里没有该行，后续 5 条断言无从执行")

# ---------------------------------------------------------------------------
# 收尾
# ---------------------------------------------------------------------------
cleanup()
leftover = q_int("SELECT count(*) FROM release_orders WHERE order_no LIKE '%s-%%'" % TAG)
assert_eq("夹具已清干净（release_orders 残留）", 0, leftover)
print("\n[提示] used_wu4 是本轮临时隔离 schema，验收合并后回 public 跑全量（AGENTS §6.3）。")
sys.exit(summary("smoke_used_wu4"))
