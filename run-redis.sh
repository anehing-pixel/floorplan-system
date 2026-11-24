#!/usr/bin/env bash
# ===========================================================================
# 本地 Redis 实例（多实例分布式锁 / 作业调度用）
# ===========================================================================
# 用法：
#   bash run-redis.sh          # 启动（已有容器则 start）
#   bash run-redis.sh stop     # 停止
#
# 说明：
#   - 容器名 floorplan-redis，宿主机 6379 → 容器 6379；
#   - 业务侧通过 LOCK_PROVIDER=redis 切到 RedisDistributedLock；
#   - 本地默认 lock.provider=memory，不装 Redis 也能跑单实例冒烟。
# ===========================================================================
set -u

NAME=floorplan-redis
PORT=${REDIS_PORT:-6379}

if [ "${1:-}" = "stop" ]; then
  docker stop "$NAME" >/dev/null 2>&1 && echo "[Redis] 已停止 $NAME" || echo "[Redis] 容器不存在或未运行"
  exit 0
fi

echo "[Redis] 确保容器 $NAME 运行（宿主机端口 $PORT）"
if docker start "$NAME" >/dev/null 2>&1; then
  echo "[Redis] 已启动既有容器"
else
  docker run -d --name "$NAME" -p "$PORT:6379" redis:7-alpine >/dev/null 2>&1 \
    || { echo "[Redis] 启动失败：Docker 不可用或镜像拉取失败"; exit 1; }
  echo "[Redis] 已新建容器"
fi

for _ in $(seq 1 30); do
  if docker exec "$NAME" redis-cli ping 2>/dev/null | grep -q PONG; then
    echo "[Redis] 就绪：127.0.0.1:$PORT"
    exit 0
  fi
  sleep 1
done

echo "[Redis] 启动超时；最近日志："
docker logs "$NAME" 2>&1 | tail -5
exit 1
