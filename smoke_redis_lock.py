#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Redis 分布式锁实例冒烟（2026-11-24）。

前置：
    bash run-redis.sh
    LOCK_PROVIDER=redis bash run-microservices.sh start
（或直接 `bash run-microservices.sh redis-smoke`，脚本会启动 Redis + 服务 + 清理）

断言：
  1) BANK_FETCH 作业响应里的 lockProvider == redis（证明 RedisDistributedLock 生效，
     不是内存实现）；
  2) 作业仍能正常完成（source/accountCount 有值）；
  3) 并发两次触发都返回 200，且至少一次成功/被锁跳过（不出现 500）。

为什么不做"两个实例"的强断言：本机只起一套服务；多实例互斥由
`RedisDistributedLock` 的 SET NX + Lua 释放保证，另有单测覆盖锁语义。
本脚本证明的是"实例真的连上了 Redis 并在用"，不是模拟两个进程。
"""
import json
import os
import sys
import threading
import urllib.error
import urllib.request

BASE = os.environ.get("SMOKE_BASE", "http://localhost:8080")
FAILS = []
TOKEN = ""


def call(method, path, body=None, token=""):
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"raw": raw}


def ok(cond, msg):
    print(("  [PASS] " if cond else "  [FAIL] ") + msg)
    if not cond:
        FAILS.append(msg)


def main():
    global TOKEN
    print("=" * 68)
    print("Redis 分布式锁实例冒烟")
    print("=" * 68)

    st, res = call("POST", "/api/auth/login", {"username": "admin", "password": "admin123"})
    assert st == 200 and res.get("data", {}).get("token"), \
        "预检失败：admin 登录未通过 -> %s" % json.dumps(res, ensure_ascii=False)
    TOKEN = res["data"]["token"]

    st, res = call("POST", "/api/jobs/bank-fetch", {}, token=TOKEN)
    data = res.get("data") or {}
    ok(st == 200, "BANK_FETCH 触发 -> %s" % st)
    ok(data.get("jobName") == "BANK_FETCH", "响应 jobName=BANK_FETCH -> %s" % data.get("jobName"))
    ok(data.get("lockProvider") == "redis",
       "lockProvider=redis（RedisDistributedLock 生效，不是内存锁）-> %s" % data.get("lockProvider"))
    ok((data.get("accountCount") or 0) > 0, "账户数 > 0 -> %s" % data.get("accountCount"))
    ok(str(data.get("source", "")).lower() in ("demo", "csv", "api"),
       "source 可识别 -> %s" % data.get("source"))

    # 并发两次：至少都要有明确响应；允许 lockedCount>0（另一线程先拿到账户锁）
    results = []

    def fire():
        results.append(call("POST", "/api/jobs/bank-fetch", {}, token=TOKEN))

    threads = [threading.Thread(target=fire) for _ in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    ok(len(results) == 2 and all(s == 200 for s, _ in results),
       "并发两次触发均返回 200 -> %s" % [s for s, _ in results])
    providers = {(r.get("data") or {}).get("lockProvider") for _, r in results}
    ok(providers == {"redis"}, "并发响应仍全部使用 redis 锁 -> %s" % providers)

    print("=" * 68)
    if FAILS:
        print("FAIL=%d" % len(FAILS))
        for f in FAILS:
            print("  - " + f)
        return 1
    print("全部断言通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
