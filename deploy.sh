#!/usr/bin/env bash
#
# Solo - multi-environment deployment script (dev / test / prod)
# -----------------------------------------------------------------------------
# Renders application-<env>.yml into a .env file, then orchestrates the
# MySQL + Solo stack via docker-compose. Provides health checks, log
# management and database backups for safe production operation.
#
# Usage:
#   ./deploy.sh <env> <command> [options]
#
#   env       dev | test | prod
#   command   up | down | restart | status | logs | build | backup | health | config
#             (default: up)
#
# Options:
#   -y, --yes        skip confirmation prompts (required for destructive ops in CI)
#   -v, --volumes    with `down`: also remove the database volume (DESTROYS DATA)
#   -f, --follow     with `logs`: follow log output
#       --save       with `logs`: snapshot logs to ./logs/ instead of streaming
#
# Production secrets (prod only) MUST be exported beforehand:
#   export JDBC_PASSWORD='...'
#   export MYSQL_ROOT_PASSWORD='...'
#
set -euo pipefail

# ----------------------------------------------------------------------------- paths
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"
ENV_FILE="${SCRIPT_DIR}/.env"
LOG_DIR="${SCRIPT_DIR}/logs"
BACKUP_DIR="${SCRIPT_DIR}/backups"
LOG_RETENTION=10      # keep the newest N log snapshots / backups

# ----------------------------------------------------------------------------- logging
if [[ -t 1 ]]; then
  C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'; C_BLU=$'\033[34m'; C_RST=$'\033[0m'
else
  C_RED=''; C_GRN=''; C_YEL=''; C_BLU=''; C_RST=''
fi
info() { printf '%s[INFO]%s %s\n'  "$C_BLU" "$C_RST" "$*"; }
ok()   { printf '%s[ OK ]%s %s\n'  "$C_GRN" "$C_RST" "$*"; }
warn() { printf '%s[WARN]%s %s\n'  "$C_YEL" "$C_RST" "$*" >&2; }
err()  { printf '%s[FAIL]%s %s\n'  "$C_RED" "$C_RST" "$*" >&2; }
die()  { err "$*"; exit 1; }

usage() { sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

# ----------------------------------------------------------------------------- yaml reader
# yml_get <section> <key> : echo the value of section.key from $CONFIG_FILE.
# Supports the strict 2-level "section -> key: value" layout used by our
# application-<env>.yml files. Strips inline comments and surrounding quotes.
yml_get() {
  local section="$1" key="$2"
  awk -v section="$section" -v key="$key" '
    { sub(/\r$/, "") }                                   # tolerate CRLF
    /^[^[:space:]#][^:]*:[[:space:]]*$/ {                # top-level "section:" header
      hdr = $0; sub(/:.*/, "", hdr); insec = (hdr == section); next
    }
    insec && $0 ~ ("^[[:space:]]+" key ":") {
      line = $0
      sub("^[[:space:]]+" key ":[[:space:]]*", "", line) # drop "  key: "
      sub(/[[:space:]]+#.*$/, "", line)                  # drop trailing comment
      gsub(/^"|"$/, "", line)                            # drop surrounding quotes
      print line; exit
    }
  ' "$CONFIG_FILE"
}

# resolve <value> : expand ${VAR} placeholders from the environment.
resolve() {
  if command -v envsubst >/dev/null 2>&1; then printf '%s' "$1" | envsubst; else printf '%s' "$1"; fi
}

# ----------------------------------------------------------------------------- compose detection
detect_compose() {
  if docker compose version >/dev/null 2>&1; then
    COMPOSE=(docker compose)
  elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE=(docker-compose)
  else
    die "Neither 'docker compose' nor 'docker-compose' is available. Install Docker Compose."
  fi
}
# dc <args...> : run compose against this env's project + files.
dc() { "${COMPOSE[@]}" -p "$PROJECT" -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"; }

# ----------------------------------------------------------------------------- preflight
preflight() {
  command -v docker >/dev/null 2>&1 || die "docker is not installed or not on PATH."
  docker info >/dev/null 2>&1 || die "Docker daemon is not reachable. Is Docker running?"
  [[ -f "$CONFIG_FILE" ]]  || die "Config not found: $CONFIG_FILE"
  [[ -f "$COMPOSE_FILE" ]] || die "Compose file not found: $COMPOSE_FILE"
  if [[ "$ENV" == "prod" ]]; then
    require_prod_secrets
  fi
}

require_prod_secrets() {
  command -v envsubst >/dev/null 2>&1 || die "envsubst (gettext) is required to resolve prod secrets."
  local missing=()
  [[ -n "${JDBC_PASSWORD:-}" ]]       || missing+=("JDBC_PASSWORD")
  [[ -n "${MYSQL_ROOT_PASSWORD:-}" ]] || missing+=("MYSQL_ROOT_PASSWORD")
  if (( ${#missing[@]} > 0 )); then
    die "Missing prod secrets: ${missing[*]}. Export them before deploying prod."
  fi
  local v
  for v in "$JDBC_PASSWORD" "$MYSQL_ROOT_PASSWORD"; do
    case "$v" in
      ""|solo-dev|solo-test|123456|root|password|admin|changeme)
        die "Refusing to deploy prod with weak/default secret value: '$v'." ;;
    esac
  done
  local host; host="$(yml_get server host)"
  [[ "$host" == "your-domain.com" ]] && warn "server.host is still 'your-domain.com' in $CONFIG_FILE — set your real domain."
  ok "Production secrets present."
}

# ----------------------------------------------------------------------------- .env rendering
generate_env() {
  info "Rendering ${ENV_FILE##*/} from ${CONFIG_FILE##*/}"
  cat > "$ENV_FILE" <<EOF
# Generated by deploy.sh for env='${ENV}' on $(date '+%Y-%m-%d %H:%M:%S'). DO NOT EDIT BY HAND.
# Source of truth: ${CONFIG_FILE##*/}
RUNTIME_DB=$(yml_get database runtimeDb)
JDBC_USERNAME=$(yml_get database username)
JDBC_PASSWORD=$(yml_get database password)
JDBC_DRIVER=$(yml_get database driver)
JDBC_URL=$(yml_get database url)
JDBC_MIN_CONNS=$(yml_get database minConns)
JDBC_MAX_CONNS=$(yml_get database maxConns)
JDBC_TABLE_PREFIX=$(yml_get database tablePrefix)
SERVER_SCHEME=$(yml_get server scheme)
SERVER_HOST=$(yml_get server host)
SOLO_HOST_PORT=$(yml_get server port)
JAVA_OPTS=$(yml_get jvm javaOpts)
SOLO_LOG_LEVEL=$(yml_get logging level)
SOLO_IMAGE=$(yml_get solo image)
SOLO_CONTAINER=$(yml_get solo containerName)
SOLO_MEM_LIMIT=$(yml_get solo memoryLimit)
SOLO_CPU_LIMIT=$(yml_get solo cpuLimit)
SOLO_RESTART=$(yml_get solo restartPolicy)
MYSQL_IMAGE=$(yml_get mysql image)
MYSQL_CONTAINER=$(yml_get solo containerName)-mysql
MYSQL_ROOT_PASSWORD=$(yml_get mysql rootPassword)
MYSQL_DATABASE=$(yml_get mysql database)
MYSQL_MEM_LIMIT=$(yml_get mysql memoryLimit)
MYSQL_CPU_LIMIT=$(yml_get mysql cpuLimit)
LOG_MAX_SIZE=$(yml_get log maxSize)
LOG_MAX_FILE=$(yml_get log maxFile)
GIT_COMMIT=$(git -C "$SCRIPT_DIR" rev-parse --short HEAD 2>/dev/null || echo 0)
EOF
  # prod: resolve ${JDBC_PASSWORD} / ${MYSQL_ROOT_PASSWORD} placeholders from the environment.
  if [[ "$ENV" == "prod" ]]; then
    local tmp; tmp="$(mktemp)"; envsubst < "$ENV_FILE" > "$tmp"; mv "$tmp" "$ENV_FILE"
  fi
  chmod 600 "$ENV_FILE" 2>/dev/null || true
  ok "Wrote $ENV_FILE"
}

# ----------------------------------------------------------------------------- health
wait_healthy() {
  local name="$1" timeout="${2:-180}" elapsed=0 status
  info "Waiting for '$name' to become healthy (timeout ${timeout}s)..."
  while (( elapsed < timeout )); do
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$name" 2>/dev/null || echo missing)"
    case "$status" in
      healthy|running) ok "'$name' is ${status}."; return 0 ;;
      missing) ;;  # container not created yet
      *) ;;        # starting / unhealthy -> keep polling
    esac
    sleep 5; elapsed=$(( elapsed + 5 ))
    printf '   ...%ss elapsed (status: %s)\r' "$elapsed" "$status"
  done
  printf '\n'
  err "'$name' did not become healthy within ${timeout}s. Last 50 log lines:"
  dc logs --tail=50 "${name%%-mysql}" 2>/dev/null || docker logs --tail=50 "$name" 2>/dev/null || true
  return 1
}

health_report() {
  local svc
  printf '%-22s %-10s %s\n' "CONTAINER" "HEALTH" "STATE"
  for svc in "$SOLO_CONTAINER" "${SOLO_CONTAINER}-mysql"; do
    local h s
    h="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}n/a{{end}}' "$svc" 2>/dev/null || echo "-")"
    s="$(docker inspect -f '{{.State.Status}}' "$svc" 2>/dev/null || echo "absent")"
    printf '%-22s %-10s %s\n' "$svc" "$h" "$s"
  done
}

# ----------------------------------------------------------------------------- log management
ensure_dirs() { mkdir -p "$LOG_DIR" "$BACKUP_DIR"; }

rotate() {  # rotate <dir> <glob> : keep newest $LOG_RETENTION, delete the rest
  local dir="$1" glob="$2" f n=0
  ls -1t "$dir"/$glob 2>/dev/null | while IFS= read -r f; do
    n=$((n + 1)); (( n > LOG_RETENTION )) && rm -f "$f"
  done || true
}

save_logs() {
  ensure_dirs
  local ts out; ts="$(date '+%Y%m%d-%H%M%S')"; out="${LOG_DIR}/solo-${ENV}-${ts}.log"
  info "Saving logs to $out"
  dc logs --no-color --tail=all > "$out" 2>&1 || true
  rotate "$LOG_DIR" "solo-${ENV}-*.log"
  ok "Logs saved ($(wc -l < "$out" 2>/dev/null || echo 0) lines)."
}

# ----------------------------------------------------------------------------- commands
cmd_up() {
  preflight; ensure_dirs; generate_env
  info "Starting stack (project: $PROJECT)"
  dc up -d --remove-orphans
  wait_healthy "${SOLO_CONTAINER}-mysql" 90 || die "MySQL failed health check."
  wait_healthy "$SOLO_CONTAINER" 180 || die "Solo failed health check."
  ok "Deployment of '$ENV' complete."
  health_report
}

cmd_down() {
  preflight; generate_env
  local extra=()
  if [[ "$WITH_VOLUMES" == "yes" ]]; then
    confirm "This will REMOVE the MySQL data volume for '$ENV' (DATA LOSS). Continue?"
    extra+=(--volumes)
  else
    confirm "Stop and remove the '$ENV' stack (data volume preserved)?"
  fi
  dc down "${extra[@]}"
  ok "Stack '$ENV' stopped."
}

cmd_restart() {
  preflight; generate_env
  info "Recreating stack with current config"
  dc up -d --remove-orphans
  dc restart
  wait_healthy "$SOLO_CONTAINER" 180 || die "Solo failed health check after restart."
  ok "Restarted '$ENV'."
}

cmd_status() { generate_env; dc ps; echo; health_report; }

cmd_logs() {
  generate_env
  if [[ "$LOG_SAVE" == "yes" ]]; then save_logs; return; fi
  if [[ "$LOG_FOLLOW" == "yes" ]]; then dc logs -f --tail=200; else dc logs --tail=200; fi
}

cmd_build() { preflight; generate_env; info "Building images"; dc build; ok "Build complete."; }

cmd_backup() {
  preflight; ensure_dirs; generate_env
  local db pass ts out
  db="$(yml_get mysql database)"
  pass="$(resolve "$(yml_get mysql rootPassword)")"
  ts="$(date '+%Y%m%d-%H%M%S')"; out="${BACKUP_DIR}/${db}-${ENV}-${ts}.sql.gz"
  info "Backing up database '$db' -> $out"
  dc exec -T mysql sh -c "exec mysqldump -uroot -p\"$pass\" --single-transaction --routines --databases \"$db\"" \
    | gzip > "$out"
  [[ -s "$out" ]] || die "Backup is empty — check that MySQL is running."
  rotate "$BACKUP_DIR" "${db}-${ENV}-*.sql.gz"
  ok "Backup written ($(du -h "$out" | cut -f1))."
}

cmd_health() {
  generate_env
  health_report
  local port; port="$(yml_get server port)"
  info "Probing http://localhost:${port}/manifest.json"
  if curl -fsS -o /dev/null --max-time 5 "http://localhost:${port}/manifest.json"; then
    ok "HTTP endpoint healthy."
  else
    warn "HTTP endpoint not reachable on :${port} (may still be starting)."
  fi
}

cmd_config() { generate_env; dc config; }

confirm() {
  [[ "$ASSUME_YES" == "yes" ]] && return 0
  [[ -t 0 ]] || die "Refusing destructive action in non-interactive mode without --yes."
  local reply; read -r -p "$* [y/N] " reply
  [[ "$reply" =~ ^[Yy]$ ]] || die "Aborted by user."
}

# ----------------------------------------------------------------------------- arg parsing
ENV=""; COMMAND="up"
ASSUME_YES="no"; WITH_VOLUMES="no"; LOG_FOLLOW="no"; LOG_SAVE="no"
POSITIONAL=()
while (( $# > 0 )); do
  case "$1" in
    -h|--help) usage 0 ;;
    -y|--yes) ASSUME_YES="yes" ;;
    -v|--volumes) WITH_VOLUMES="yes" ;;
    -f|--follow) LOG_FOLLOW="yes" ;;
    --save) LOG_SAVE="yes" ;;
    -*) die "Unknown option: $1 (try --help)" ;;
    *) POSITIONAL+=("$1") ;;
  esac
  shift
done
(( ${#POSITIONAL[@]} >= 1 )) && ENV="${POSITIONAL[0]}"
(( ${#POSITIONAL[@]} >= 2 )) && COMMAND="${POSITIONAL[1]}"

case "$ENV" in
  dev|test|prod) ;;
  "") err "Missing <env>."; usage 1 ;;
  *) die "Invalid env '$ENV' (expected dev|test|prod)." ;;
esac
CONFIG_FILE="${SCRIPT_DIR}/application-${ENV}.yml"
PROJECT="solo-${ENV}"

detect_compose

case "$COMMAND" in
  up)      cmd_up ;;
  down)    cmd_down ;;
  restart) cmd_restart ;;
  status)  cmd_status ;;
  logs)    cmd_logs ;;
  build)   cmd_build ;;
  backup)  cmd_backup ;;
  health)  cmd_health ;;
  config)  cmd_config ;;
  *) die "Unknown command '$COMMAND' (try --help)." ;;
esac
