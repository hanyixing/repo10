#!/bin/bash
#
# Solo - A small and beautiful blogging system written in Java.
# Copyright (c) 2010-present, b3log.org
#
# Solo is licensed under Mulan PSL v2.
#
# Solo docker restart script with health check and log management.
#
# Usage:
#   1. Copy .env.example to .env and configure your settings
#   2. Run: ./scripts/docker-restart.sh
#   3. Optionally add to crontab for auto-update
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Load environment variables
if [[ -f "$PROJECT_DIR/.env" ]]; then
    set -a
    # shellcheck source=/dev/null
    source "$PROJECT_DIR/.env"
    set +a
fi

# Configuration with defaults
SOLO_CONTAINER_NAME="${SOLO_CONTAINER_NAME:-solo}"
SOLO_IMAGE_NAME="${SOLO_IMAGE_NAME:-b3log/solo}"
SOLO_LISTEN_PORT="${SOLO_LISTEN_PORT:-8080}"
SOLO_SERVER_SCHEME="${SERVER_SCHEME:-http}"
SOLO_SERVER_HOST="${SERVER_HOST:-localhost}"
JDBC_USERNAME="${JDBC_USERNAME:?Error: JDBC_USERNAME is required. Please set it in .env}"
JDBC_PASSWORD="${JDBC_PASSWORD:?Error: JDBC_PASSWORD is required. Please set it in .env}"
JDBC_DRIVER="${JDBC_DRIVER:-com.mysql.cj.jdbc.Driver}"
JDBC_URL="${JDBC_URL:-jdbc:mysql://127.0.0.1:3306/solo?useUnicode=yes&characterEncoding=UTF-8&useSSL=false&serverTimezone=UTC}"
HEALTH_CHECK_URL="http://localhost:${SOLO_LISTEN_PORT}/health"
HEALTH_CHECK_RETRIES="${HEALTH_CHECK_RETRIES:-15}"
HEALTH_CHECK_INTERVAL="${HEALTH_CHECK_INTERVAL:-5}"

# Logging
log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

log_error() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] ERROR: $*" >&2
}

# Health check function
check_health() {
    local retries=$HEALTH_CHECK_RETRIES
    local interval=$HEALTH_CHECK_INTERVAL

    log "Waiting for Solo to become healthy..."
    for ((i = 1; i <= retries; i++)); do
        if curl -sf "$HEALTH_CHECK_URL" > /dev/null 2>&1; then
            log "Solo is healthy!"
            return 0
        fi
        log "Health check attempt $i/$retries failed, retrying in ${interval}s..."
        sleep "$interval"
    done

    log_error "Solo failed to become healthy after $retries attempts"
    return 1
}

# Stop and remove existing container
stop_solo() {
    if docker ps --format '{{.Names}}' | grep -q "^${SOLO_CONTAINER_NAME}$"; then
        log "Stopping Solo container..."
        docker stop "$SOLO_CONTAINER_NAME"
    fi

    if docker ps -a --format '{{.Names}}' | grep -q "^${SOLO_CONTAINER_NAME}$"; then
        log "Removing Solo container..."
        docker rm "$SOLO_CONTAINER_NAME"
    fi
}

# Start Solo container
start_solo() {
    log "Starting Solo container..."
    docker run --detach --name "$SOLO_CONTAINER_NAME" --network=host \
        --restart unless-stopped \
        --log-driver=json-file \
        --log-opt max-size=50m \
        --log-opt max-file=5 \
        --env RUNTIME_DB="MYSQL" \
        --env JDBC_USERNAME="$JDBC_USERNAME" \
        --env JDBC_PASSWORD="$JDBC_PASSWORD" \
        --env JDBC_DRIVER="$JDBC_DRIVER" \
        --env JDBC_URL="$JDBC_URL" \
        "$SOLO_IMAGE_NAME" \
        --listen_port="$SOLO_LISTEN_PORT" \
        --server_scheme="$SOLO_SERVER_SCHEME" \
        --server_host="$SOLO_SERVER_HOST"
}

# Update Solo image
update_solo() {
    log "Pulling latest Solo image..."
    local output
    output=$(docker pull "$SOLO_IMAGE_NAME" 2>&1)

    if echo "$output" | grep -q "Image is up to date"; then
        log "Solo image is already up to date"
        return 1
    else
        log "Solo image updated"
        return 0
    fi
}

# Main logic
main() {
    log "=== Solo Docker Restart ==="

    # Check if Solo is currently running
    if docker ps --format '{{.Names}}' | grep -q "^${SOLO_CONTAINER_NAME}$"; then
        log "Solo container is running, checking for updates..."

        if update_solo; then
            stop_solo
            start_solo

            if check_health; then
                log "Solo updated and restarted successfully"
            else
                log_error "Solo failed health check after update"
                log "Check logs: docker logs $SOLO_CONTAINER_NAME"
                exit 1
            fi
        else
            log "No update available, Solo is up to date"
            # Still verify health
            if ! check_health; then
                log_error "Solo is not healthy, restarting..."
                stop_solo
                start_solo
                if ! check_health; then
                    log_error "Failed to restart Solo"
                    log "Check logs: docker logs $SOLO_CONTAINER_NAME"
                    exit 1
                fi
            fi
        fi
    else
        log "Solo container is not running, starting fresh..."
        stop_solo
        docker pull "$SOLO_IMAGE_NAME" > /dev/null 2>&1 || true
        start_solo

        if check_health; then
            log "Solo started successfully"
        else
            log_error "Solo failed to start properly"
            log "Check logs: docker logs $SOLO_CONTAINER_NAME"
            exit 1
        fi
    fi

    log "=== Done ==="
}

main "$@"
