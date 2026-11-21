#!/usr/bin/env bash
# 在 WSL 内执行：   bash run-docker.sh
# 以 Docker 容器方式启动库存融资核心系统（容器由 docker daemon 管理，可跨会话存活）。
# 说明：本机 Docker 后端的容器 DNS 不可用，故用数据库容器在 bridge 上的 IP 直连。

set -u
PROJ=/mnt/c/Users/Administrator/WorkBuddy/2026-10-25-21-28-58/floorplan-system
IMAGE=eclipse-temurin:17-jre-alpine
JAR=floorplan-web-1.0.0-SNAPSHOT.jar

echo "==> 确保数据库容器运行"
docker start floorplan-db2 >/dev/null 2>&1 || true
sleep 3

DB_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}' floorplan-db2 2>/dev/null | awk '{print $1}')
if [ -z "${DB_IP:-}" ]; then
  DB_IP=172.17.0.3
fi
echo "==> 数据库容器 IP = $DB_IP"

echo "==> 重建应用容器"
docker rm -f floorplan-app >/dev/null 2>&1 || true
# -Dfile.encoding / -Dstdout.encoding：强制 UTF-8，否则 Windows 环境 JVM 默认 GBK，
# 日志里的中文全是乱码（详见 logback-spring.xml 顶部说明）
#
# 注意：数据库容器内部实际监听 5434（数据目录 postgresql.conf 的 port=5434），
# 不是默认 5432。SPRING_DATASOURCE_URL 必须与之对齐，否则应用启动即连接失败。
docker run -d --name floorplan-app \
  -p 8080:8080 \
  -v "$PROJ/web/target:/app" \
  -v "$PROJ/logs:/app/logs" \
  -e "SPRING_DATASOURCE_URL=jdbc:postgresql://$DB_IP:5434/floorplan" \
  -e "LOG_HOME=/app/logs" \
  -e "SPRING_SERVER_TOMCAT_ACCESSLOG_DIRECTORY=/app/logs" \
  "$IMAGE" \
  java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
       -jar /app/"$JAR" >/dev/null

echo "==> 等待启动..."
sleep 32

echo "==> 容器状态"
docker ps --filter name=floorplan-app --format '{{.Names}} | {{.Status}} | {{.Ports}}'

echo "==> 启动日志"
docker logs floorplan-app 2>&1 | grep -E "Started Floorplan|Tomcat started|ERROR" | head -5

echo "==> 健康检查"
curl -s -m 5 "http://localhost:8080/api/alerts?limit=1" -w "\nHTTP:%{http_code}\n" | tail -2
