#!/bin/bash
#
# Solo docker 更新重启脚本 (auto-update + health check + log management)
#
# 1. 参数可通过环境变量覆盖（见下方默认值），不设置时与历史行为保持一致。
# 2. 可加入 crontab，每日凌晨运行实现自动更新，例如：
#       0 4 * * *  SOLO_NAME=solo /path/to/scripts/docker-restart.sh >> /var/log/solo-cron.log 2>&1
# 3. 每次启动会做健康检查；异常时自动转储最近日志，便于排查。
#
set -uo pipefail   # 不使用 -e：失败需自行处理并继续，更适合 cron

# ----------------------------------------------------------------------------- 可配置参数
SOLO_NAME="${SOLO_NAME:-solo}"
SOLO_IMAGE="${SOLO_IMAGE:-b3log/solo:latest}"
SOLO_NETWORK="${SOLO_NETWORK:-host}"
SOLO_PORT="${SOLO_PORT:-8080}"
SERVER_SCHEME="${SERVER_SCHEME:-http}"
SERVER_HOST="${SERVER_HOST:-localhost}"

RUNTIME_DB="${RUNTIME_DB:-MYSQL}"
JDBC_USERNAME="${JDBC_USERNAME:-root}"
JDBC_PASSWORD="${JDBC_PASSWORD:-123456}"
JDBC_DRIVER="${JDBC_DRIVER:-com.mysql.cj.jdbc.Driver}"
JDBC_URL="${JDBC_URL:-jdbc:mysql://127.0.0.1:3306/solo?useUnicode=yes&characterEncoding=UTF-8&useSSL=false&serverTimezone=UTC}"
JDBC_MIN_CONNS="${JDBC_MIN_CONNS:-5}"
JDBC_MAX_CONNS="${JDBC_MAX_CONNS:-10}"
JDBC_TABLE_PREFIX="${JDBC_TABLE_PREFIX:-b3_solo}"

# 健康检查
HEALTH_RETRIES="${HEALTH_RETRIES:-12}"     # 重试次数
HEALTH_INTERVAL="${HEALTH_INTERVAL:-5}"    # 每次间隔(秒)

# 日志管理
LOG_DIR="${LOG_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/logs}"
LOG_MAX_SIZE="${LOG_MAX_SIZE:-10m}"        # 容器内单文件上限(json-file)
LOG_MAX_FILE="${LOG_MAX_FILE:-3}"          # 容器内日志文件个数(json-file)
LOG_RETENTION="${LOG_RETENTION:-7}"        # 磁盘日志快照保留个数

# ----------------------------------------------------------------------------- 工具
log() { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }

ensure_log_dir() { mkdir -p "$LOG_DIR" 2>/dev/null || true; }

# 转储容器日志到带时间戳的文件，并按 LOG_RETENTION 滚动清理。
save_logs() {
  ensure_log_dir
  local ts out
  ts="$(date '+%Y%m%d-%H%M%S')"
  out="${LOG_DIR}/${SOLO_NAME}-${ts}.log"
  docker logs --tail=500 "$SOLO_NAME" > "$out" 2>&1 || true
  log "已转储日志: $out"
  # 仅保留最新的 LOG_RETENTION 份
  local n=0 f
  ls -1t "${LOG_DIR}/${SOLO_NAME}-"*.log 2>/dev/null | while IFS= read -r f; do
    n=$((n + 1)); [ "$n" -gt "$LOG_RETENTION" ] && rm -f "$f"
  done || true
}

# 健康检查：优先用容器自带 HEALTHCHECK 状态，回退到本地 HTTP 探测。
health_check() {
  local i status
  for ((i = 1; i <= HEALTH_RETRIES; i++)); do
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$SOLO_NAME" 2>/dev/null || echo absent)"
    case "$status" in
      healthy)
        log "健康检查通过 (容器健康状态: healthy)"; return 0 ;;
      none)
        # 镜像未内置 HEALTHCHECK：回退到 HTTP 探测
        if command -v curl >/dev/null 2>&1 && \
           curl -fsS --max-time 5 -o /dev/null "http://127.0.0.1:${SOLO_PORT}/manifest.json"; then
          log "健康检查通过 (HTTP 探测 :${SOLO_PORT})"; return 0
        fi ;;
      absent)
        log "容器 ${SOLO_NAME} 不存在" ;;
    esac
    log "健康检查未通过 (第 ${i}/${HEALTH_RETRIES} 次, 状态: ${status})，${HEALTH_INTERVAL}s 后重试"
    sleep "$HEALTH_INTERVAL"
  done
  log "健康检查失败：${SOLO_NAME} 在 $((HEALTH_RETRIES * HEALTH_INTERVAL))s 内未就绪"
  save_logs
  return 1
}

restart_solo() {
  docker stop "$SOLO_NAME" >/dev/null 2>&1
  docker rm "$SOLO_NAME"   >/dev/null 2>&1
  docker run --detach --name "$SOLO_NAME" --network="$SOLO_NETWORK" \
    --restart=unless-stopped \
    --log-driver=json-file --log-opt max-size="$LOG_MAX_SIZE" --log-opt max-file="$LOG_MAX_FILE" \
    --env RUNTIME_DB="$RUNTIME_DB" \
    --env JDBC_USERNAME="$JDBC_USERNAME" \
    --env JDBC_PASSWORD="$JDBC_PASSWORD" \
    --env JDBC_DRIVER="$JDBC_DRIVER" \
    --env JDBC_URL="$JDBC_URL" \
    --env JDBC_MIN_CONNS="$JDBC_MIN_CONNS" \
    --env JDBC_MAX_CONNS="$JDBC_MAX_CONNS" \
    --env JDBC_TABLE_PREFIX="$JDBC_TABLE_PREFIX" \
    "$SOLO_IMAGE" \
    --listen_port="$SOLO_PORT" --server_scheme="$SERVER_SCHEME" --server_host="$SERVER_HOST"
}

update_solo() {
  log "拉取 Solo 镜像中..."
  local isUpdate
  isUpdate=$(docker pull "$SOLO_IMAGE" | grep "Downloaded")
  if [[ -z $isUpdate ]]; then
    log "Solo 已是最新版本"
  else
    log "检测到新版本，重启中..."
    restart_solo >/dev/null 2>&1
    if health_check; then
      log "已更新并重启 Solo"
    else
      log "更新后健康检查失败，请执行 'docker logs ${SOLO_NAME}' 查看详情"
    fi
  fi
}

# 检查当前容器状态：正常则尝试升级，异常则重新部署并校验。
update_and_test_service() {
  local isRunning
  isRunning=$(docker ps --filter "name=^/${SOLO_NAME}$" --filter "status=running" -q)
  if [[ -z $isRunning ]]; then
    log "Solo 状态异常，尝试重新部署"
    docker pull "$SOLO_IMAGE"
    restart_solo
    if ! health_check; then
      log "重新部署后健康检查失败，请执行 'docker logs ${SOLO_NAME}' 查看详情"
    fi
  else
    update_solo
  fi
}

update_and_test_service
