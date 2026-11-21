#!/usr/bin/env bash
# 本机（Git Bash / Windows）启动库存融资核心系统
#
# 与 run-docker.sh 的区别：本脚本不依赖 WSL，直接用本机 java 跑 jar。
# 两个脚本都强制 UTF-8 编码 —— Windows 环境 JVM 默认 file.encoding=GBK，
# 不加这个参数日志里的中文全是乱码。
#
# 用法：
#   bash run-local.sh            # 前台启动（Ctrl+C 停止）
#   bash run-local.sh background  # 后台启动，日志写 logs/console.log

set -u
cd "$(dirname "$0")"

JAR=web/target/floorplan-web-1.0.0-SNAPSHOT.jar
# 注意：这里刻意用 APP_PORT 而不是 SERVER__PORT。
# SERVER__PORT 是部分运行环境自动注入的变量（Spring Boot 会把它当 server.port 的环境变量来源），
# 直接拿来当默认值会导致服务起在意外端口上。要改端口请传 APP_PORT。
PORT=${APP_PORT:-8080}
LOG_HOME=${LOG_HOME:-logs}

if [ ! -f "$JAR" ]; then
  echo "[ERROR] 未找到 $JAR，请先执行：mvn -o package -DskipTests"
  exit 1
fi

mkdir -p "$LOG_HOME"

JAVA_OPTS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Duser.language=zh -Duser.country=CN"

# Spring Boot 的 relaxed binding 会把 SERVER__PORT 这类环境变量当成 server.port 的候选值。
# 它是某些运行环境的自动注入值（内容往往是随机端口），会与应用实际要用的端口打架。
# 命令行参数 --server.port 优先级最高，但为避免歧义，这里直接把它清掉。
unset SERVER__PORT

echo "==> 启动应用（端口 $PORT，日志目录 $LOG_HOME）"

if [ "${1:-}" = "background" ]; then
  # 后台启动：用 Windows 原生的 `cmd start` 拉起独立进程。
  # 为什么不用 "&" / nohup / setsid：
  #   - Git Bash for Windows 没有 setsid
  #   - 某些托管环境在主进程退出时会回收同进程组的子进程，普通 & 起的 java 会被一起杀掉
  # cmd start 创建的是 Windows 独立进程，不随本 shell 退出而终止。
  CONSOLE_LOG="$(pwd)/$LOG_HOME/console.log"
  mkdir -p "$LOG_HOME"

  # 关键：绝不能在这里 rm 旧日志。
  # Windows 上「删除一个正被进程持有的文件」不会真正断开持有者的句柄：
  # 持有者继续向已被移走的旧文件写入，而同名的新文件是另一个独立句柄，
  # 结果是新文件里出现「空洞」（前面一大段 NUL 字节），历史日志也彻底丢失。
  # 正确做法是归档而非删除 —— 把旧日志改名保留，新进程写新文件。
  if [ -s "$CONSOLE_LOG" ]; then
    STAMP=$(date +%Y%m%d-%H%M%S)
    mv "$CONSOLE_LOG" "$(pwd)/$LOG_HOME/archive/console-$STAMP.log" 2>/dev/null || true
  fi

  cmd //c start "" /B java $JAVA_OPTS -jar "$(pwd)/$JAR" --server.port="$PORT" \
      > "$CONSOLE_LOG" 2>&1

  # 等待端口真正起来（最多 40 秒）
  echo "==> 等待应用启动..."
  for i in $(seq 1 40); do
    if netstat -ano 2>/dev/null | grep ":$PORT " | grep -q LISTENING; then
      echo "==> 启动成功（端口 $PORT）"
      echo "==> 控制台日志：$CONSOLE_LOG"
      echo "==> 应用日志：  $(pwd)/$LOG_HOME/floorplan.log"
      exit 0
    fi
    sleep 1
  done

  echo "==> [ERROR] 40 秒内端口 $PORT 未监听，请查看 $CONSOLE_LOG"
  tail -20 "$CONSOLE_LOG" 2>/dev/null
  exit 1
else
  export LOG_HOME
  exec java $JAVA_OPTS -jar "$JAR" --server.port="$PORT"
fi
