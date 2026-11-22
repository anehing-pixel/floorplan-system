#!/usr/bin/env bash
# ============================================================================
# 微服务模式一键启动（本地验证用）
#
# 与 run-local.sh 的区别：run-local 只起 web 单体；本脚本起全部 6 个进程，
# 并把 Spring Cloud Gateway 作为统一入口（18080），按路径切流到各服务。
#
# 三个本地环境障碍及处理：
#   1) WorkBuddy 注入 SERVER__PORT=51136，Spring 宽松绑定会解析成 server.port，
#      覆盖各服务 yml 里的端口。用 `env -u SERVER__PORT` 清掉。
#   2) 没起 Nacos：各服务配了 discovery fail-fast=false，注册失败只告警不影响启动；
#      网关的 lb:// 用 ROUTE_* 环境变量覆盖成 http://127.0.0.1:<port> 直连。
#   3) 只有 web 跑 Flyway（建表），其余服务 flyway.enabled=false，避免重复迁移。
#
# 端口分配：web=8080  auth=8081  bank=8082  credit=8083  risk=8084  gateway=18080
#           Wave 0 新增：loan=8085  collateral-reg=8086  release=8087  recon=8088
#
# 用法：
#   ./run-microservices.sh start   # 启动全部服务并前台保活；保持窗口开启，按 Ctrl+C 停止
#   ./run-microservices.sh stop    # 另开窗口执行，停止全部服务
#   ./run-microservices.sh smoke   # 启动→走网关跑冒烟→自动清理，一条命令内完成
#
# 为什么 start 必须前台保活：Windows/Git Bash 下用 `&` 起的后台进程会随父 shell
# 退出被回收。若 start 启动完就返回，服务立刻随之消失。因此 start 用 wait 阻塞
# 在此，并 trap 捕获 Ctrl+C，先清理服务再退出。
# ============================================================================
set -u
cd "$(dirname "$0")"
ROOT="$PWD"
PIDDIR="$ROOT/.microservice-pids"
LOGDIR="$ROOT/logs/microservices"
mkdir -p "$PIDDIR" "$LOGDIR"

# 冒烟用的 Python 解释器。默认 python3 = 本脚本历史行为，不改变任何现有跑法。
# 为什么要留这个口子：Windows/Git Bash 上常见只有 `python`（miniconda 与官方安装包都不落
# python3.exe），此时 `bash run-microservices.sh smoke` 会在调用冒烟那一行直接
# `127: command not found` —— 服务全起来了、冒烟一行没跑，最容易被误读成「跑过了」。
# 本机实测 2026-11-22：command -v python3 为空、python 在 miniconda3。
# 跑不通就显式指定：PYTHON=python bash run-microservices.sh smoke
PYTHON="${PYTHON:-python3}"

JAR() { echo "$ROOT/$1/target/floorplan-$1-1.0.0-SNAPSHOT.jar"; }

# ----------------------------------------------------------------------------
# W0-5 本地直连：不启 Nacos 时让 Feign 也能真的打到对端服务。
#
# 背景：契约只有 @FeignClient(name=...) 没有 url，本地没注册中心时 Feign 解析不到实例，
# agent 只能对着存根测——于是"写了代码没接通"（/api/payments/** 被 core 兜底 404 就是同类）。
# 现在契约统一写成 url = "${floorplan.clients.<key>.url:}"：
#   给了值 → 直连该地址；不给（生产）→ 空串回退 lb://服务名，行为零变化。
#
# 键名规矩：<key> 一律用小写单个单词（loan / credit / collateral），不要带连字符。
#   原因：Spring 宽松绑定把环境变量里的下划线解析成点，
#   FLOORPLAN_CLIENTS_COLLATERAL_REG_URL 会被读成 floorplan.clients.collateral.reg.url，
#   带连字符的属性键没法用环境变量稳定表达。
#
# 端口值与下面网关的 ROUTE_* 严格一致，两处不一致就会有一处骗人。
# 在这里 export 而不是逐个 start_one 传参：Feign 调用方会随波次增加（W1 起 bank 也要用），
# 逐个传要改 N 行，全局 export 只加一次，且各服务 application.yml 无需任何改动。
# ----------------------------------------------------------------------------
export FLOORPLAN_CLIENTS_LOAN_URL=http://127.0.0.1:8085
export FLOORPLAN_CLIENTS_CREDIT_URL=http://127.0.0.1:8083
export FLOORPLAN_CLIENTS_COLLATERAL_URL=http://127.0.0.1:8086

start_one() {
  local name="$1" jar="$2"; shift 2
  if [ ! -f "$jar" ]; then echo "[跳过] $jar 不存在，请先 mvn clean package"; return 1; fi
  # env -u SERVER__PORT 是关键：否则端口被 WorkBuddy 的 51136 覆盖
  env -u SERVER__PORT java -jar "$jar" "$@" > "$LOGDIR/$name.log" 2>&1 &
  echo $! > "$PIDDIR/$name.pid"
  echo "[启动] $name pid=$(cat "$PIDDIR/$name.pid")"
}

wait_one() {
  local name="$1" marker="$2" port="$3"
  local i
  for i in $(seq 1 90); do
    if grep -q "$marker" "$LOGDIR/$name.log" 2>/dev/null; then
      echo "[就绪] $name ($ port $port, ${i}s)"; return 0
    fi
    if grep -qE "APPLICATION FAILED TO START|Application run failed" "$LOGDIR/$name.log" 2>/dev/null; then
      echo "[失败] $name 启动异常，最后 20 行："; tail -20 "$LOGDIR/$name.log"; return 1
    fi
    sleep 1
  done
  echo "[超时] $name 90s 未就绪，最后 20 行："; tail -20 "$LOGDIR/$name.log"; return 1
}

# 端口占用预检：Git Bash 下 netstat 可用。启动前先扫一遍，避免某个服务中途
# 才报 "Port already in use" 失败、留下半套进程（多个 start/stop 并发时尤其容易踩到）。
port_in_use() {
  netstat -ano 2>/dev/null | grep -q ":$1 .*LISTENING"
}

do_start() {
    local busy=""
    for p in 8080 8081 8082 8083 8084 8085 8086 8087 8088 18080; do
      port_in_use "$p" && busy="$busy $p"
    done
    if [ -n "$busy" ]; then
      echo "[错误] 端口已被占用:$busy —— 请先执行 stop，或关闭占用这些端口的进程后重试"
      return 1
    fi
    echo "=== 启动后端服务 ==="
    # 先起核心账务 + 4 个拆分服务（web 最先，Flyway 建表）
    start_one web "$(JAR web)"
    start_one auth-service "$(JAR auth-service)"
    start_one bank-service "$(JAR bank-service)"
    start_one credit-service "$(JAR credit-service)"
    start_one risk-service "$(JAR risk-service)"
    # Wave 0 新增四个服务（骨架可启动；agent 填充业务后再纳入完整冒烟）
    start_one loan-service "$(JAR loan-service)"
    start_one collateral-reg-service "$(JAR collateral-reg-service)"
    start_one release-service "$(JAR release-service)"
    start_one recon-service "$(JAR recon-service)"
    # 等后端就绪再起网关（网关依赖路由目标的 http 直连地址存在）
    # 任一服务失败：先清理已拉起的进程再退出，避免留下半套服务占着端口
    wait_one web "Started FloorplanApplication" 8080 || { do_stop; exit 1; }
    wait_one auth-service "Started AuthServiceApplication" 8081 || { do_stop; exit 1; }
    wait_one bank-service "Started BankServiceApplication" 8082 || { do_stop; exit 1; }
    wait_one credit-service "Started CreditServiceApplication" 8083 || { do_stop; exit 1; }
    wait_one risk-service "Started RiskServiceApplication" 8084 || { do_stop; exit 1; }
    wait_one loan-service "Started LoanServiceApplication" 8085 || { do_stop; exit 1; }
    wait_one collateral-reg-service "Started CollateralRegServiceApplication" 8086 || { do_stop; exit 1; }
    wait_one release-service "Started ReleaseServiceApplication" 8087 || { do_stop; exit 1; }
    wait_one recon-service "Started ReconServiceApplication" 8088 || { do_stop; exit 1; }

    echo "=== 启动网关（http 直连覆盖 lb://）==="
    env -u SERVER__PORT \
      ROUTE_CORE=http://127.0.0.1:8080 \
      ROUTE_AUTH=http://127.0.0.1:8081 \
      ROUTE_BANK=http://127.0.0.1:8082 \
      ROUTE_CREDIT=http://127.0.0.1:8083 \
      ROUTE_RISK=http://127.0.0.1:8084 \
      ROUTE_LOAN=http://127.0.0.1:8085 \
      ROUTE_COLLATERAL=http://127.0.0.1:8086 \
      ROUTE_RELEASE=http://127.0.0.1:8087 \
      ROUTE_RECON=http://127.0.0.1:8088 \
      java -jar "$(JAR gateway)" > "$LOGDIR/gateway.log" 2>&1 &
    echo $! > "$PIDDIR/gateway.pid"
    wait_one gateway "Started GatewayApplication" 18080 || { do_stop; exit 1; }
    echo ""
    echo "全部就绪。网关入口: http://127.0.0.1:18080"
    echo "跑冒烟测试: SMOKE_BASE=http://127.0.0.1:18080 $PYTHON smoke-test.py"
}

do_stop() {
    echo "=== 停止全部服务 ==="
    for pf in "$PIDDIR"/*.pid; do
      [ -f "$pf" ] || continue
      pid=$(cat "$pf")
      name=$(basename "$pf" .pid)
      if kill -0 "$pid" 2>/dev/null; then kill "$pid" && echo "[停止] $name $pid"; else echo "[已停] $name"; fi
      rm -f "$pf"
    done
}

case "${1:-}" in
  start)
    do_start || exit 1
    echo ""
    echo "服务已就绪并保持运行。按 Ctrl+C 停止全部服务。"
    # Git Bash 下 & 起的后台进程会随脚本退出被回收，故此处必须阻塞保活。
    # trap 捕获 Ctrl+C：先清理全部服务，再退出，避免留下占端口的孤儿进程。
    trap 'echo; do_stop; exit 0' INT TERM
    wait
    ;;
  smoke)
    # 启动 → 走网关跑冒烟 → 无论结果都清理，全在一条命令生命周期内，避免 JVM 被回收
    do_start || { do_stop; exit 1; }
    echo "=== 通过网关跑冒烟测试 ==="
    SMOKE_BASE=http://127.0.0.1:18080 "$PYTHON" smoke-test.py
    RC=$?
    echo "=== 冒烟退出码: $RC ==="
    do_stop
    exit $RC
    ;;
  stop)
    do_stop
    ;;
  *)
    echo "用法: $0 {start|stop|smoke}"; exit 1
    ;;
esac
