#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
轻量级 HTTP 全链路压测脚本（Python 标准库，零第三方依赖）。

用途：在没有 JMeter / k6 / locust 的环境中，快速做接口容量摸底与回归压测，
      产出含 TPS / RT / P50~P99 / 错误率 / 容量结论的 Markdown 报告。

设计取舍：
  - 每个工作线程复用一个 HTTP/1.1 连接（更贴近真实压测，避免建连开销掩盖服务端瓶颈）。
  - closed-loop：线程尽可能快地发请求，TPS 由服务端能力决定，适合探测容量上限。
  - 预热期（--warmup）内的请求不计入统计；爬坡期（--ramp-up）错开线程启动时间。
  - 只用标准库：http.client / concurrent.futures / argparse / statistics。

正式的全链路、多协议、分布式压测仍建议使用 k6 / JMeter / locust；
本脚本定位为“随时可跑、无需装工具”的门禁前摸底工具。

退出码：0 = SLA 达标；2 = SLA 未达标；1 = 参数/运行错误。
"""

import argparse
import math
import sys
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime
from http.client import HTTPConnection, HTTPSConnection
from pathlib import Path
from typing import Dict, List, Optional, Tuple
from urllib.parse import urlsplit


@dataclass
class Sample:
    latency_ms: float
    ok: bool
    error: str = ""


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="零依赖 HTTP 压测脚本（TPS/RT/P99/容量结论）",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--url", required=True, help="目标 URL，含 scheme/host/port/path")
    parser.add_argument("--method", default="GET", help="HTTP 方法")
    parser.add_argument(
        "--body", default=None,
        help="请求体：直接给字符串，或用 @文件路径 读取（如 @payload.json）",
    )
    parser.add_argument(
        "--header", action="append", default=[],
        help="自定义请求头，格式 'K: V'，可重复；如 -H 'Authorization: Bearer xxx'",
    )
    parser.add_argument("-c", "--concurrency", type=int, default=10, help="并发线程数")
    parser.add_argument("-d", "--duration", type=int, default=30, help="正式压测时长（秒，不含预热）")
    parser.add_argument("--warmup", type=int, default=2, help="预热时长（秒，不计入统计）")
    parser.add_argument("--ramp-up", type=int, default=0, help="爬坡时长（秒，线程错峰启动）")
    parser.add_argument("--timeout", type=float, default=5.0, help="单次请求超时（秒）")
    parser.add_argument("--label", default="未命名场景", help="场景名称，写入报告标题")
    parser.add_argument("--sla-p99", type=float, default=500.0, help="P99 延迟 SLA（毫秒）")
    parser.add_argument("--sla-error-rate", type=float, default=1.0, help="错误率 SLA（百分比）")
    parser.add_argument("--out", default=None, help="报告输出路径；默认当前目录带时间戳")
    args = parser.parse_args(argv)

    if args.concurrency < 1:
        parser.error("--concurrency 必须 >= 1")
    if args.duration < 1:
        parser.error("--duration 必须 >= 1")
    if args.warmup < 0 or args.ramp_up < 0:
        parser.error("--warmup / --ramp-up 不能为负数")
    parts = urlsplit(args.url)
    if parts.scheme not in ("http", "https") or not parts.hostname:
        parser.error("--url 必须是合法的 http/https URL")
    return args


def build_headers(raw_headers: List[str], body: Optional[str]) -> Dict[str, str]:
    headers: Dict[str, str] = {"User-Agent": "floorplan-loadtest/1.0"}
    for item in raw_headers:
        if ":" not in item:
            raise ValueError("请求头格式应为 'K: V'，收到：%r" % item)
        k, v = item.split(":", 1)
        headers[k.strip()] = v.strip()
    if body is not None and not any(k.lower() == "content-type" for k in headers):
        headers["Content-Type"] = "application/json"
    return headers


def resolve_body(body_arg: Optional[str]) -> Optional[str]:
    if body_arg is None:
        return None
    if body_arg.startswith("@"):
        return Path(body_arg[1:]).read_text(encoding="utf-8")
    return body_arg


def make_connection(parts, timeout: float):
    port = parts.port or (443 if parts.scheme == "https" else 80)
    cls = HTTPSConnection if parts.scheme == "https" else HTTPConnection
    return cls(parts.hostname, port, timeout=timeout)


def request_path(parts) -> str:
    path = parts.path or "/"
    return path + ("?" + parts.query if parts.query else "")


def worker(idx: int, args, parts, headers, body, start_at: float,
           warmup_end: float, test_end: float) -> Tuple[List[Sample], Counter, Counter]:
    """单个工作线程：复用连接循环发请求，返回 (样本, 状态码分布, 错误分布)。"""
    samples: List[Sample] = []
    codes: Counter = Counter()
    errors: Counter = Counter()

    delay = start_at - time.time()
    if delay > 0:
        time.sleep(delay)

    conn = make_connection(parts, args.timeout)
    path = request_path(parts)
    method = args.method.upper()

    while time.time() < test_end:
        counted = time.time() >= warmup_end
        t0 = time.perf_counter()
        try:
            conn.request(method, path, body=body, headers=headers)
            resp = conn.getresponse()
            status = resp.status
            resp.read()  # 必须读尽响应体，否则连接不可复用
            latency = (time.perf_counter() - t0) * 1000.0
            ok = 200 <= status < 300
            if counted:
                samples.append(Sample(latency, ok))
                codes[status] += 1
        except Exception as exc:  # 连接错误/超时/中断都记为失败
            latency = (time.perf_counter() - t0) * 1000.0
            name = type(exc).__name__
            if counted:
                samples.append(Sample(latency, False, name))
                errors[name] += 1
            try:
                conn.close()
            except Exception:
                pass
            conn = make_connection(parts, args.timeout)

    try:
        conn.close()
    except Exception:
        pass
    return samples, codes, errors


def percentile(sorted_values: List[float], p: float) -> float:
    if not sorted_values:
        return 0.0
    k = (len(sorted_values) - 1) * p
    low, high = math.floor(k), math.ceil(k)
    if low == high:
        return sorted_values[int(k)]
    return sorted_values[low] * (high - k) + sorted_values[high] * (k - low)


def summarize(samples: List[Sample]) -> Dict[str, float]:
    if not samples:
        return dict(total=0, ok=0, fail=0, tps=0.0, avg=0.0, p50=0.0, p90=0.0,
                    p95=0.0, p99=0.0, min=0.0, max=0.0, err_rate=0.0)
    latencies = sorted(s.latency_ms for s in samples)
    ok_count = sum(1 for s in samples if s.ok)
    total = len(samples)
    return dict(
        total=total,
        ok=ok_count,
        fail=total - ok_count,
        avg=sum(latencies) / total,
        p50=percentile(latencies, 0.50),
        p90=percentile(latencies, 0.90),
        p95=percentile(latencies, 0.95),
        p99=percentile(latencies, 0.99),
        min=latencies[0],
        max=latencies[-1],
        err_rate=(total - ok_count) / total * 100.0,
    )


def render_report(args, parts, dur_actual: float, m: Dict[str, float],
                  codes: Counter, errors: Counter, pass_sla: bool) -> str:
    safe_url = "%s://%s%s" % (parts.scheme, parts.netloc, parts.path or "/")
    reason = []
    if m["err_rate"] > args.sla_error_rate:
        reason.append("错误率 %.2f%% 超过 SLA %.2f%%" % (m["err_rate"], args.sla_error_rate))
    if m["p99"] > args.sla_p99:
        reason.append("P99 %.1fms 超过 SLA %.1fms" % (m["p99"], args.sla_p99))
    verdict = "达标" if pass_sla else "不达标"
    verdict_detail = (
        "当前并发下满足 SLA，可继续阶梯加压探测容量上限。"
        if pass_sla else "；".join(reason) + "，需定位瓶颈后重试。"
    )
    code_lines = "\n".join("| %s | %d |" % (k, v) for k, v in sorted(codes.items())) or "| （无） | 0 |"
    err_lines = "\n".join("| %s | %d |" % (k, v) for k, v in errors.most_common()) or "| （无） | 0 |"

    return f"""# 全链路压测报告 - {args.label}

- 生成时间：{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}
- 目标（已去除 query 参数）：`{safe_url}`
- 方法：`{args.method.upper()}`　并发：`{args.concurrency}`　正式时长：`{args.duration}s`（预热 `{args.warmup}s`，爬坡 `{args.ramp_up}s`）
- 实际统计时长：`{dur_actual:.1f}s`　超时：`{args.timeout}s`
- SLA：P99 ≤ `{args.sla_p99:.0f}ms`，错误率 ≤ `{args.sla_error_rate:.2f}%`

## 核心指标

| 指标 | 结果 |
|---|---|
| 总请求数 | {m['total']} |
| 成功 / 失败 | {m['ok']} / {m['fail']} |
| 错误率 | {m['err_rate']:.2f}% |
| **TPS（成功请求/秒）** | **{m['tps']:.1f}** |
| 平均 RT | {m['avg']:.1f} ms |
| 最小 / 最大 RT | {m['min']:.1f} / {m['max']:.1f} ms |
| **P99 RT** | **{m['p99']:.1f} ms** |
| P50 / P90 / P95 | {m['p50']:.1f} / {m['p90']:.1f} / {m['p95']:.1f} ms |

## 状态码分布

| 状态码 | 次数 |
|---|---|
{code_lines}

## 错误类型分布

| 错误类型 | 次数 |
|---|---|
{err_lines}

## 容量结论

- 判定：**{verdict}**
- 说明：{verdict_detail}
- 瓶颈线索（需结合服务端监控确认）：P99 明显高于 P50 → 存在长尾（GC、锁竞争、慢 SQL）；
  错误率为 0 但 TPS 不再随并发增长 → 已达服务端容量拐点。
- 后续动作：按 `{args.concurrency} → 2× → 4×` 阶梯加压，重复本脚本；每级记录本报告并归档执行计划。

## 慢 SQL 关联

- 执行计划归档目录：`docs/performance/explain/{{YYYYMMDD}}-{{SERVICE}}-{{SQL_ID}}/`（按 T4-4 规范填写）
- 关联说明：（填写压测期间发现的慢 SQL 编号与归档路径）

## 备注

- 工具：`scripts/loadtest/loadtest.py`（Python 标准库，零依赖）；本脚本为 closed-loop 摸底，非正式全链路压测工具。
- 报告中 URL 已去除 query 参数；自定义 Header 的值不会写入报告，请放心归档。
"""


def main(argv: Optional[List[str]] = None) -> int:
    try:
        args = parse_args(argv)
        headers = build_headers(args.header, args.body)
        body = resolve_body(args.body)
    except (ValueError, OSError) as exc:
        print("[loadtest] 参数错误：%s" % exc, file=sys.stderr)
        return 1

    parts = urlsplit(args.url)
    warmup_end = time.time() + args.warmup
    t0 = warmup_end
    test_end = warmup_end + args.duration

    print("[loadtest] 场景=%s 目标=%s://%s 并发=%d 时长=%ds 预热=%ds" % (
        args.label, parts.scheme, parts.netloc, args.concurrency, args.duration, args.warmup))

    all_samples: List[Sample] = []
    codes: Counter = Counter()
    errors: Counter = Counter()
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = []
        for i in range(args.concurrency):
            start_at = t0 + i * (args.ramp_up / args.concurrency)
            futures.append(pool.submit(worker, i, args, parts, headers, body,
                                       start_at, warmup_end, test_end))
        for f in as_completed(futures):
            try:
                samples, c, e = f.result()
            except Exception as exc:
                print("[loadtest] 线程异常：%s" % exc, file=sys.stderr)
                return 1
            all_samples.extend(samples)
            codes.update(c)
            errors.update(e)

    dur_actual = max(args.duration, time.time() - warmup_end)
    m = summarize(all_samples)
    m["tps"] = m["ok"] / dur_actual
    pass_sla = m["err_rate"] <= args.sla_error_rate and m["p99"] <= args.sla_p99

    out = Path(args.out) if args.out else Path.cwd() / (
        "loadtest-report-%s.md" % datetime.now().strftime("%Y%m%d-%H%M%S"))
    try:
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(render_report(args, parts, dur_actual, m, codes, errors, pass_sla),
                       encoding="utf-8")
    except OSError as exc:
        print("[loadtest] 报告写入失败：%s" % exc, file=sys.stderr)
        return 1

    print("[loadtest] 完成：请求 %d，成功 %d，失败 %d，错误率 %.2f%%，TPS %.1f，P99 %.1fms，SLA %s" % (
        m["total"], m["ok"], m["fail"], m["err_rate"], m["tps"], m["p99"],
        "达标" if pass_sla else "不达标"))
    print("[loadtest] 报告：%s" % out.resolve())
    return 0 if pass_sla else 2


if __name__ == "__main__":
    sys.exit(main())
