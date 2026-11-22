"""多 agent 并行冒烟公共库（W0 交付物；Wave 1/2/3 的 scripts/smoke_wX_*.py 都 import 它）。

为什么单独建一个库：每个 wave 都要登录、发请求、查库、等异步收敛，逻辑一样。
如果各自复制一份，一个坑（比如超时后照样判绿）会在 5 个脚本里重复出现。

三条硬约束（都是这个项目踩过的坑，不是风格偏好）：
1. 判据是**业务语义**，不是 HTTP 200、不是 mvn exit 0。所以除 HTTP 封装外还提供
   psql() 和 wait_until()：库里真实状态 + 时间收敛才叫绿。
2. 登录/取 token 的逻辑从 smoke-test.py **复制**而非 import：那个脚本是基线资产，
   import 会让它的任何改动同时波及所有 wave。
3. 等待一律带超时上限，超时由调用方 assert_fail；不静默跳过。

与基线脚本的对应关系（保持习惯一致，降低切换成本）：
- BASE、psql() 的「本机 psql 优先 → docker exec 兜底」、ok(cond, msg) 的参数顺序，
  都与 smoke-test.py 相同；
- 差异一处：本库的 psql() 返回**行列表**而非整段字符串（卡面要求「返回行」），
  需要原始文本用 psql_text()。

用法：
    import sys, os
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from agent_smoke_lib import *
    login()
    st, res = post("http://127.0.0.1:8085/api/loans/by-vin/VIN1", None)
    assert_eq("借据已建", 1, len(psql("SELECT id FROM loans WHERE vin='VIN1'")))
    sys.exit(summary("smoke_w0"))
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

# ---------------------------------------------------------------------------
# 目标地址
# ---------------------------------------------------------------------------
# 与基线一致：默认直连单体 web(8080)；验证网关切流时设 SMOKE_BASE=http://127.0.0.1:18080。
# 多服务并行测试（如 web + loan-service）不要改这个全局值，用 call/get/post 的 base 参数逐次覆盖。
BASE = os.environ.get("SMOKE_BASE", "http://localhost:8080")

# 库连接参数：docker 容器里 PostgreSQL 监听 5434（不是默认 5432），可用环境变量覆盖。
DB_HOST = os.environ.get("FLOORPLAN_DB_HOST", "localhost")
DB_PORT = os.environ.get("FLOORPLAN_DB_PORT", "5434")
DB_USER = os.environ.get("FLOORPLAN_DB_USER", "floorplan")
DB_NAME = os.environ.get("FLOORPLAN_DB_NAME", "floorplan")
DB_PWD = os.environ.get("FLOORPLAN_DB_PWD", "floorplan")
DB_CONTAINER = os.environ.get("FLOORPLAN_DB_CONTAINER", "floorplan-db2")
# 并行开发隔离：W1 每个 agent 一个 schema（见 docs/并行开发-W1环境隔离.md）。
# 不设 = 沿用 public，基线行为零变化；设了则本库所有 SQL 都显式走
# `search_path = <schema>, public`——第二个 public 不是可选装饰，pgcrypto 的 gen_salt()
# 只装在 public，实测缺了它 V5 建 seed 就中断在 "function gen_salt(unknown, integer) does not exist"。
# 服务侧要配同一个 schema，用 SPRING_DATASOURCE_URL 里的 currentSchema=<schema>,public。
DB_SCHEMA = os.environ.get("FLOORPLAN_DB_SCHEMA", "").strip()
if DB_SCHEMA and not all(c.isalnum() or c in "_-" for c in DB_SCHEMA):
    raise RuntimeError("FLOORPLAN_DB_SCHEMA 只能含字母数字与 _-，实得 %r" % DB_SCHEMA)
# 逗号后不能有空格：PGOPTIONS 的值最终成为 GUC search_path 的原始串，
# "w1_demo, public" 会被解析成 ["w1_demo,", " public"] 而报
# `invalid value for parameter "search_path"`（连接直接失败，psql 返回 rc=2）。
SEARCH_PATH = ('%s,public' % DB_SCHEMA) if DB_SCHEMA else "public"

TOKEN = None          # 管理员令牌，login() 之后填充
OPERATOR = "smoke"    # 审计头，与基线同名，便于比对
CHECKS = 0
FAILURES = []


def call(method, path, body=None, token=None, headers=None, base=None):
    """发一个 HTTP 请求，返回 (状态码, 响应体字典)。

    token=None 用全局 TOKEN；显式传 token 可模拟其他岗位；传空串则不带 Authorization。
    4xx/5xx 不抛异常（HTTPError 里读 body），由调用方判状态码——很多断言要的就是 4xx。
    base 覆盖全局 BASE，用于同一脚本里打多个服务（web:8080、loan-service:8085 …）。

    **传输层失败也不抛**：连不上（进程没起 / 端口被上一轮残留占着 / 超时）返回 `(0, {...})`。
    HTTP 状态码没有 0，所以调用方拿 `st == 0` 就能区分「服务没在跑」和「服务跑了但报错」。
    之前这里是裸 URLError 栈：并行时隔壁 agent 的 8183 上没起 credit-service，
    本脚本一条「现状登记」打印直接崩掉整轮冒烟，连累后面的清理都不执行。
    """
    url = (base or BASE) + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Operator-Id", OPERATOR)
    tok = TOKEN if token is None else token
    if tok:
        req.add_header("Authorization", "Bearer " + tok)
    for hk, hv in (headers or {}).items():
        req.add_header(hk, hv)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8") or "{}"
        try:
            return exc.code, json.loads(raw)
        except ValueError:
            return exc.code, {"_raw": raw}
    except Exception as exc:      # URLError / ConnectionRefusedError / TimeoutError / 非 JSON 响应
        return 0, {"code": "TRANSPORT_ERROR",
                   "message": "%s %s → %s: %s" % (method, url, type(exc).__name__, exc)}


def get(path, base=None, token=None):
    """GET 简写，返回 (状态码, 字典)。"""
    return call("GET", path, None, token=token, base=base)


def post(path, body=None, base=None, token=None):
    """POST 简写，返回 (状态码, 字典)。body=None 表示空请求体（POST 无 JSON）。"""
    return call("POST", path, body, token=token, base=base)


def login(base=None):
    """登录拿 token（复制自 smoke-test.py 的登录逻辑：admin/admin123，全程复用）。

    这一步是**预检**而非可选：拿不到 token 后续每个 /api/** 都会 401，
    与其让几十条断言红成一片，不如在这里立刻终止并报出真实原因。
    """
    global TOKEN
    st, res = call("POST", "/api/auth/login", {"username": "admin", "password": "admin123"},
                   token="", base=base)
    if st != 200 or not (res.get("data") or {}).get("token"):
        raise RuntimeError("预检失败：登录未通过，无法开始冒烟 → HTTP %s %s"
                           % (st, json.dumps(res, ensure_ascii=False)[:300]))
    TOKEN = res["data"]["token"]
    return TOKEN


def login_as(username, password, base=None):
    """按岗位登录拿 token 但不覆盖全局 TOKEN，供权限类断言使用（返回 token 字符串，失败返回空串）。"""
    st, res = call("POST", "/api/auth/login", {"username": username, "password": password},
                   token="", base=base)
    if st != 200:
        return ""
    return (res.get("data") or {}).get("token") or ""


def ok(cond, msg, detail=""):
    """记一条断言，参数顺序与 smoke-test.py 的 ok(cond, msg) 完全一致。

    故意跟基线同序：如果这里反过来，从基线脚本抄代码的人会把 (cond, msg) 原样传进来，
    字符串 msg 是真值 → 断言恒 PASS，属于最隐蔽的那种假绿。
    判据必须传业务条件，别把 status == 200 当结论。
    """
    global CHECKS
    CHECKS += 1
    if cond:
        print("[PASS] %s" % msg)
    else:
        print("[FAIL] %s  %s" % (msg, detail))
        FAILURES.append(msg)
    return bool(cond)


def assert_eq(msg, expected, actual):
    """相等断言：失败详情自动带上期望值与实际值，不用回头猜是哪个数不对。"""
    return ok(expected == actual, msg, "expected=%r actual=%r" % (expected, actual))


def assert_fail(msg, detail=""):
    """显式判失败（用于超时、缺行这类「条件已经不可能再满足」的场景）。"""
    return ok(False, msg, detail)


def psql_text(sql):
    """执行 SQL 返回原始文本（每行一条记录，列以 tab 分隔）。

    与 smoke-test.py 同一条「本机 psql 优先 → docker exec 兜底」路径：
    本机装了客户端时最快，容器内 psql 是标准兜底。
    注意端口必须显式给 5434，容器里 postgres 不监听默认 5432。
    设了 FLOORPLAN_DB_SCHEMA 时，会话 search_path 通过 PGOPTIONS 设成 `<schema>, public`，
    于是脚本查的库和 Spring 用的库必然是同一个——不挂这条会出现「服务写 agent schema、
    脚本查 public」的错开，断言照样全绿，那才是最难查的假绿。
    失败返回 None（调用方据此区分「查不到」与「查询方式坏了」）。
    """
    env = dict(os.environ, PGPASSWORD=DB_PWD)
    # search_path 走 PGOPTIONS 而不是在 SQL 前面挂一条 `SET search_path TO …;`：
    # 后者会把 psql 的命令标签 "SET" 也打进 stdout，于是 psql_one("SELECT …") 拿到的第一个值是 "SET"，
    # count/RETURNING 之类全被污染（实测把 int('SET') 直接炸出来）。PGOPTIONS 对服务端会话生效，
    # 输出干净，且和 SPRING_DATASOURCE_URL 里的 currentSchema 是同一个语义。
    if DB_SCHEMA:
        env["PGOPTIONS"] = "-c search_path=%s" % SEARCH_PATH
    local = ["psql", "-h", DB_HOST, "-p", DB_PORT, "-U", DB_USER, "-d", DB_NAME, "-tAc", sql]
    try:
        r = subprocess.run(local, env=env, capture_output=True, timeout=30)
        if r.returncode == 0:
            return r.stdout.decode("utf-8", "replace").strip()
    except Exception:
        pass
    docker = ["docker", "exec"] + (["-e", "PGOPTIONS=" + env["PGOPTIONS"]] if DB_SCHEMA else []) + \
             [DB_CONTAINER, "psql", "-h", "127.0.0.1",
              "-p", DB_PORT, "-U", DB_USER, "-d", DB_NAME, "-tAc", sql]
    try:
        r = subprocess.run(docker, env=env, capture_output=True, timeout=30)
        if r.returncode == 0:
            return r.stdout.decode("utf-8", "replace").strip()
        return None
    except Exception:
        return None


def psql(sql):
    """执行 SQL 并返回**行列表**：list[list[str]]，空结果 → []。

    单列查询时长度就是行数，配合 assert_eq 直接判「插了几条 / 有没有重复」。
    多列查询按 tab 切列。DDL/DML 无返回集时同样是 []，判成功请用影响行数或再查一次。
    """
    text = psql_text(sql)
    if text in (None, ""):
        return [] if text == "" else None
    return [line.split("\t") for line in text.splitlines()]


def psql_one(sql):
    """取第一行第一列的标量，没有结果返回 None。等价于 psql()[0][0]，写法更短。"""
    rows = psql(sql)
    if not rows:
        return None
    return rows[0][0] if rows[0] else None


def psql_exec(sql):
    """执行写入类 SQL（INSERT/UPDATE/DELETE），返回是否成功。

    冒烟只允许**构造测试数据**（AGENTS 铁律：生产代码不得造数据，脚本内 psql 构造合法）。
    注意这里只能用纯 SQL：psql 的元命令（反斜杠开头那类）与 :变量 绑定都不生效，
    因为语句是 -c 单条传入的。字符串值记得自己转义单引号。
    """
    return psql_text(sql) is not None


def wait_until(fn, timeout=10, interval=0.3):
    """轮询 fn() 直到返回真值或超时。返回 (成功?, 最后一次结果, 耗时秒)。

    为什么要它：EventDispatcher 是 fixedDelay=1000ms 的库轮询，HTTP 返回时事件还没消费完。
    用固定 sleep 要么赌运气、要么白等；超时后必须让调用方 assert_fail，不能「没等到就跳过」。
    """
    started = time.time()
    last = None
    while time.time() - started < timeout:
        last = fn()
        if last:
            return True, last, time.time() - started
        time.sleep(interval)
    return False, last, time.time() - started


def wait_ready(path, base=None, timeout=120):
    """等服务就绪：反复 GET 一个**真实存在的业务路径**直到 200/401/403/404 之外的响应。

    不能用 /actuator/health —— 本仓库没引 spring-boot-starter-actuator，它恒 404，
    「等到 200」永远不会成立（这就是卡面说的假绿来源）。这里以「能连上且返回任意 HTTP 状态」
    为就绪信号，鉴权与否由后续断言判。
    """
    started = time.time()
    while time.time() - started < timeout:
        try:
            req = urllib.request.Request((base or BASE) + path, method="GET")
            with urllib.request.urlopen(req, timeout=5) as resp:
                if resp.status:
                    return True
        except urllib.error.HTTPError:
            return True   # 有 HTTP 响应 = 进程已起来（401/404 也算活着）
        except Exception:
            time.sleep(1)
    return False


def summary(title="冒烟"):
    """打印「断言 N 条，失败 M 条」并返回退出码；脚本结尾 sys.exit(summary(...))。

    返回 0 的充要条件是零失败断言——这样 `exit 0` 才等价于「全绿」而不是「跑完了」。
    """
    print("\n===== %s：断言 %d 条，失败 %d 条 =====" % (title, CHECKS, len(FAILURES)))
    for name in FAILURES:
        print("  [FAIL] " + name)
    return 1 if FAILURES else 0
