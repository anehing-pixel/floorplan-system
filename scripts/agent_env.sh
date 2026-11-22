#!/usr/bin/env bash
# ============================================================================
# agent_env.sh —— 并行开发的环境隔离器（W0 交付，W1/W2/W3 使用）
#
# 要解决的问题：五个 agent 同时开发时共用一个 PostgreSQL（floorplan 库 / public schema）
# 和一个端口表（8080~8088、18080）。共库时 A 造的测试数据会被 B 的作业扫到，
# A 的游标会被 B 的服务推走；共端口时第二个服务直接起不来。
#
# 方案：一个 agent 一个 schema（库还是同一个 floorplan），外加一个端口偏移量。
# 合并到 master 之后回到 public 跑一次全量回归（那是唯一的验收口径）。
#
# 关键约束（都是实测出来的，不要"优化"掉）：
#  1) currentSchema 必须是「<agent_schema>,public」两段。只给一段时，pgcrypto 的
#     gen_salt() 找不到（它装在 public），V5 建 seed 账号就中断在
#     "function gen_salt(unknown, integer) does not exist"——实测中断时只建出 23 张表。
#  2) search_path 的顺序决定不带 schema 的表名解析到哪：agent schema 在前，
#     所以建表与读写都落在 agent schema，只有 public 里的扩展函数被兜到 public。
#     前提是这个 schema 迁移完整；迁移中断过的半成品 schema 会穿透到 public，
#     那种隔离是假的（表现为「改了 A 却看到 B 的数据」）。check 子命令就是查这个的。
#  3) 环境变量必须 SPRING_DATASOURCE_URL 整条覆盖，不能只改库名：
#     web 单体的 application.yml 里 url 是写死的（没有 ${} 占位），
#     靠的是 Spring 宽松绑定让环境变量优先于 yml。
#  4) 启动一律 env -u SERVER__PORT：宿主注入的 SERVER__PORT 会被解析成 server.port，
#     把偏移端口顶掉。
#
# 用法：
#   scripts/agent_env.sh init   w1_loan            # 建 schema（幂等）
#   scripts/agent_env.sh env    w1_loan 100        # 打印这个 agent 该 export 的东西
#   scripts/agent_env.sh check  w1_loan            # 迁移是否完整（表数 + V26 + gen_salt 可用）
#   scripts/agent_env.sh drop   w1_loan            # 合并后清理（会二次确认）
#   scripts/agent_env.sh psql   w1_loan "SELECT …" # 在该 schema 的 search_path 下执行 SQL
# ============================================================================
set -u
cd "$(dirname "$0")/.."

DB_CONTAINER="${DB_CONTAINER:-floorplan-db2}"
DB_PORT="${DB_PORT:-5434}"
DB_NAME="${DB_NAME:-floorplan}"
DB_USER="${DB_USER:-floorplan}"
# public 的表数基准：由 W0 实测（V1–V26 全部应用后 = 67）。
# 写死是有意的——这是「迁移完整」的判据，check 用它比数量，少一张就红。
PUBLIC_TABLES="${FLOORPLAN_PUBLIC_TABLES:-67}"

usage() {
  echo "用法: $0 {init|env|check|drop|psql} <schema名> [参数...]"
  echo "  init  <schema>          建 schema（幂等，owner=floorplan）"
  echo "  env   <schema> [偏移]    打印该 agent 需要 export 的环境变量（偏移默认 0）"
  echo "  check <schema>          验证迁移完整：表数=$PUBLIC_TABLES、V26 到位、gen_salt 可达"
  echo "  drop  <schema>          删掉整个 schema（合并后清理，需交互确认）"
  echo "  psql  <schema> \"SQL\"    在该 schema 的 search_path 下执行 SQL"
  exit 1
}

# 库内执行一条 SQL。<schema> 传空串表示不挂 search_path（用连接默认，即 public）。
# search_path 用 PGOPTIONS 传给服务端会话，不要写成 `SET …; SELECT …` 两条语句：
# psql 会把命令标签 "SET" 一起打进 stdout，取值时就变成 int('SET') 报错或更糟的静默错值。
# 这里固定用 docker exec：本机 psql 在部分开发机上没装，容器路径是唯一确定可用的。
dbsql() {
  local schema="$1" sql="$2" pgopt=()
  [ -n "$schema" ] && pgopt=(-e "PGOPTIONS=-c search_path=${schema},public")
  docker exec "${pgopt[@]}" "$DB_CONTAINER" psql -h 127.0.0.1 -p "$DB_PORT" -U "$DB_USER" \
    -d "$DB_NAME" -Atc "${sql}" | tr -d '\000'
}

# schema 名白名单：它会被拼进 SQL，并行脚本里不接受"看起来像名字"的任意串。
check_name() {
  case "$1" in
    *[!a-zA-Z0-9_]*) echo "[错误] schema 名只允许字母数字下划线，实得：$1" >&2; exit 1 ;;
  esac
  [ -n "$1" ] || { echo "[错误] schema 名不能为空" >&2; exit 1; }
}

ACTION="${1:-}"; SCHEMA="${2:-}"
[ -n "$ACTION" ] || usage

case "$ACTION" in
  init)
    check_name "$SCHEMA"
    dbsql "" "CREATE SCHEMA IF NOT EXISTS ${SCHEMA} AUTHORIZATION ${DB_USER}"
    echo "[完成] schema=${SCHEMA} 已就绪。下一步：按下面 env 输出起服务，Flyway 会在 web 启动时把 V1–V26 建进这个 schema。"
    "$0" env "$SCHEMA" "${3:-0}" | head -3
    ;;

  env)
    check_name "$SCHEMA"
    OFF="${3:-0}"
    case "$OFF" in *[!0-9]*) echo "[错误] 端口偏移只能是非负整数，实得：$OFF" >&2; exit 1 ;; esac
    # 下面这些值全部与 run-microservices.sh 的端口表一致，只是整体加偏移。
    # 偏移方案：agent 之间隔 100，即 0 / 100 / 200 / 300 / 400。
    echo "# ---- $(date +%F) agent=${SCHEMA} 端口偏移 +${OFF}（复制粘贴即可用）----"
    echo "# 1) 数据源：整条 URL 覆盖，currentSchema 第二段 public 不能省（gen_salt 在那）"
    echo "export SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:${DB_PORT}/${DB_NAME}?currentSchema=${SCHEMA},public'"
    echo "# 2) 冒烟库侧：脚本与服务必须落同一个 schema，否则脚本查 public、服务写 ${SCHEMA}"
    echo "export FLOORPLAN_DB_SCHEMA='${SCHEMA}'"
    echo "# 3) 冒烟 HTTP 侧：四个地址都要跟着偏移走"
    echo "export SMOKE_WEB='http://127.0.0.1:$((8080+OFF))'"
    echo "export SMOKE_AUTH='http://127.0.0.1:$((8081+OFF))'"
    echo "export SMOKE_LOAN='http://127.0.0.1:$((8085+OFF))'"
    echo "export SMOKE_CREDIT='http://127.0.0.1:$((8083+OFF))'"
    echo "# 4) Feign 本地直连：与 SMOKE_* / 服务端口一致（run-microservices.sh 里是未偏移的默认值）"
    echo "export FLOORPLAN_CLIENTS_LOAN_URL='http://127.0.0.1:$((8085+OFF))'"
    echo "export FLOORPLAN_CLIENTS_CREDIT_URL='http://127.0.0.1:$((8083+OFF))'"
    echo "export FLOORPLAN_CLIENTS_COLLATERAL_URL='http://127.0.0.1:$((8086+OFF))'"
    echo "#"
    echo "# 起服务：每个进程单独给 SERVER_PORT（一个 shell 里只有一个 SERVER_PORT，不能全局 export），"
    echo "#          并且必须 env -u SERVER__PORT，否则宿主注入的端口会把它顶掉。"
    echo "#   env -u SERVER__PORT SERVER_PORT=$((8080+OFF)) java -jar web/target/floorplan-web-1.0.0-SNAPSHOT.jar > logs/${SCHEMA}/web.log 2>&1 &"
    echo "#   env -u SERVER__PORT SERVER_PORT=$((8081+OFF)) java -jar auth-service/target/floorplan-auth-service-1.0.0-SNAPSHOT.jar > logs/${SCHEMA}/auth.log 2>&1 &"
    echo "#   env -u SERVER__PORT SERVER_PORT=$((8085+OFF)) java -jar loan-service/target/floorplan-loan-service-1.0.0-SNAPSHOT.jar > logs/${SCHEMA}/loan.log 2>&1 &"
    ;;

  check)
    check_name "$SCHEMA"
    n=$(dbsql "" "SELECT count(*) FROM information_schema.tables WHERE table_schema='${SCHEMA}' AND table_type='BASE TABLE'")
    v26=$(dbsql "$SCHEMA" "SELECT count(*) FROM flyway_schema_history WHERE version IN ('25','26') AND success = true")
    # gen_salt 可达性判的是「当前 search_path 会话里能不能解析到这个函数名」，
    # 它装在 public，靠 search_path 第二段兜住；解析不到就说明 V5 之后必然中断。
    salt=$(dbsql "$SCHEMA" "SELECT count(*) FROM pg_proc p JOIN pg_namespace ns ON ns.oid=p.pronamespace WHERE p.proname='gen_salt' AND current_schemas(false) @> ARRAY[ns.nspname]")
    echo "表数=${n}/${PUBLIC_TABLES}   V25+V26 记录=${v26}/2   gen_salt 可达=${salt}"
    if [ "$n" != "$PUBLIC_TABLES" ] || [ "$v26" != "2" ]; then
      echo "[不通过] ${SCHEMA} 迁移不完整（表数或 V26 不达标）——这个 schema 不能用，"
      echo "          不带 schema 的表名会穿透到 public，隔离当场失效。重迁：drop 后 init 再起 web。"
      # 表数「多于」基准时，上面那句结论是错的：不是没迁完，而是主干基准涨了（USED 每包 +N 张）。
      # 不分开提示的话，执行的人会 drop 掉一个其实正常的 schema，或者去改默认值 67 —— 那是主干侧基准。
      if [ "${n:-0}" -gt "${PUBLIC_TABLES}" ] 2>/dev/null; then
        echo "          [提示] 实际 ${n} > 基准 ${PUBLIC_TABLES}，多半是主干涨了而非 schema 残。"
        echo "          USED 并行期基准按包是 73/84/89/94/96（今天主干仍 67），"
        echo "          按当期值设 FLOORPLAN_PUBLIC_TABLES 再 check，不要改本脚本默认值。"
        echo "          判因与阶梯见 docs/并行开发-W1环境隔离.md §2、"
        echo "          docs/二手车库存垫资改造-04-数据库设计.md §2.1。"
      fi
      exit 1
    fi
    echo "[通过] ${SCHEMA} 可作为该 agent 的独立工作 schema"
    ;;

  drop)
    check_name "$SCHEMA"
    n=$(dbsql "" "SELECT count(*) FROM information_schema.tables WHERE table_schema='${SCHEMA}' AND table_type='BASE TABLE'")
    echo "即将删除 schema=${SCHEMA}（内含 ${n} 张表）。这是不可逆操作。"
    printf "确认请输入 schema 名：%s " "$SCHEMA"
    read -r ans
    [ "$ans" = "$SCHEMA" ] || { echo "[取消] 输入不匹配，未删除任何东西"; exit 1; }
    dbsql "" "DROP SCHEMA IF EXISTS ${SCHEMA} CASCADE"
    echo "[完成] ${SCHEMA} 已删除（CASCADE 只作用于该 schema 内的对象，public 不受影响）"
    ;;

  psql)
    check_name "$SCHEMA"
    [ $# -ge 3 ] || usage
    dbsql "$SCHEMA" "${*:3}"
    ;;

  *)
    usage
    ;;
esac
