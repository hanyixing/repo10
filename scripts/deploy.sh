#!/bin/bash
#
# Solo - A small and beautiful blogging system written in Java.
# Copyright (c) 2010-present, b3log.org
#
# Solo is licensed under Mulan PSL v2.
#
# Solo production deployment script with backup, health check, and rollback.
#
# Usage:
#   ./scripts/deploy.sh                          # Deploy latest version
#   ./scripts/deploy.sh --version v4.4.0         # Deploy specific version
#   ./scripts/deploy.sh --rollback               # Rollback to previous version
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Configuration
DEPLOY_DIR="${DEPLOY_DIR:-/opt/solo}"
BACKUP_DIR="${DEPLOY_DIR}/backups"
LOG_DIR="${DEPLOY_DIR}/logs"
LOG_FILE="${LOG_DIR}/deploy.log"
MAX_BACKUPS=5
SOLO_CONTAINER_NAME="${SOLO_CONTAINER_NAME:-solo}"
SOLO_IMAGE_NAME="${SOLO_IMAGE_NAME:-b3log/solo}"
HEALTH_CHECK_URL="http://localhost:${SOLO_LISTEN_PORT:-8080}/health"
HEALTH_CHECK_RETRIES=15
HEALTH_CHECK_INTERVAL=5
STOP_TIMEOUT=30

# Parse command line arguments
ACTION="deploy"
VERSION="latest"

while [[ $# -gt 0 ]]; do
    case $1 in
        --version)
            VERSION="$2"
            shift 2
            ;;
        --rollback)
            ACTION="rollback"
            shift
            ;;
        --help|-h)
            echo "Usage: $0 [--version VERSION] [--rollback] [--help]"
            echo ""
            echo "Options:"
            echo "  --version VERSION  Deploy a specific version (default: latest)"
            echo "  --rollback         Rollback to the previous version"
            echo "  --help, -h         Show this help message"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Ensure directories exist
ensure_dirs() {
    mkdir -p "$BACKUP_DIR" "$LOG_DIR"
}

# Logging functions
log() {
    local msg="[$(date '+%Y-%m-%d %H:%M:%S')] $*"
    echo "$msg"
    echo "$msg" >> "$LOG_FILE"
}

log_error() {
    local msg="[$(date '+%Y-%m-%d %H:%M:%S')] ERROR: $*"
    echo "$msg" >&2
    echo "$msg" >> "$LOG_FILE"
}

# Health check function
check_health() {
    log "Waiting for Solo to become healthy..."
    for ((i = 1; i <= HEALTH_CHECK_RETRIES; i++)); do
        if curl -sf "$HEALTH_CHECK_URL" > /dev/null 2>&1; then
            log "Solo is healthy!"
            return 0
        fi
        log "Health check attempt $i/$HEALTH_CHECK_RETRIES failed, retrying in ${HEALTH_CHECK_INTERVAL}s..."
        sleep "$HEALTH_CHECK_INTERVAL"
    done

    log_error "Solo failed to become healthy after $HEALTH_CHECK_RETRIES attempts"
    return 1
}

# Backup current state
backup_current() {
    local timestamp
    timestamp=$(date '+%Y%m%d_%H%M%S')
    local backup_path="${BACKUP_DIR}/${timestamp}"
    mkdir -p "$backup_path"

    log "Creating backup at $backup_path"

    # Backup docker-compose.yml
    if [[ -f "$PROJECT_DIR/docker-compose.yml" ]]; then
        cp "$PROJECT_DIR/docker-compose.yml" "$backup_path/"
    fi

    # Backup .env
    if [[ -f "$PROJECT_DIR/.env" ]]; then
        cp "$PROJECT_DIR/.env" "$backup_path/"
    fi

    # Backup current image ID
    if docker image inspect "$SOLO_IMAGE_NAME" > /dev/null 2>&1; then
        docker image inspect "$SOLO_IMAGE_NAME" --format '{{.Id}}' > "$backup_path/image_id.txt"
    fi

    # Backup database
    if command -v mysqldump > /dev/null 2>&1; then
        local db_host="${DB_HOST:-localhost}"
        local db_port="${DB_PORT:-3306}"
        local db_name="${DB_NAME:-solo}"
        local db_user="${JDBC_USERNAME:-root}"
        local db_pass="${JDBC_PASSWORD:-}"

        if [[ -n "$db_pass" ]]; then
            log "Backing up database..."
            mysqldump -h "$db_host" -P "$db_port" -u "$db_user" -p"$db_pass" \
                "$db_name" > "$backup_path/database.sql" 2>/dev/null || {
                log "Warning: Database backup failed (non-fatal)"
            }
        fi
    fi

    echo "$backup_path"
}

# Deploy function
deploy() {
    log "=== Starting Deployment ==="
    log "Target version: $VERSION"

    # Step 1: Backup
    local backup_path
    backup_path=$(backup_current)
    log "Backup created at: $backup_path"

    # Step 2: Pull new image
    log "Pulling image: $SOLO_IMAGE_NAME:$VERSION"
    if ! docker pull "$SOLO_IMAGE_NAME:$VERSION"; then
        log_error "Failed to pull image"
        return 1
    fi

    # Step 3: Stop current container gracefully
    if docker ps --format '{{.Names}}' | grep -q "^${SOLO_CONTAINER_NAME}$"; then
        log "Stopping Solo container (timeout: ${STOP_TIMEOUT}s)..."
        docker stop --time="$STOP_TIMEOUT" "$SOLO_CONTAINER_NAME"
        docker rm "$SOLO_CONTAINER_NAME"
    fi

    # Step 4: Start new container
    log "Starting Solo container..."
    if [[ -f "$PROJECT_DIR/docker-compose.yml" ]]; then
        cd "$PROJECT_DIR"
        docker compose up -d solo
    else
        log_error "docker-compose.yml not found, cannot start service"
        rollback_to "$backup_path"
        return 1
    fi

    # Step 5: Health check
    if check_health; then
        log "Deployment successful!"
        rotate_backups
        log "=== Deployment Complete ==="
    else
        log_error "Health check failed after deployment"
        log "Rolling back to previous version..."
        rollback_to "$backup_path"
        return 1
    fi
}

# Rollback function
rollback_to() {
    local backup_path="$1"

    if [[ ! -d "$backup_path" ]]; then
        log_error "Backup directory not found: $backup_path"
        return 1
    fi

    log "=== Starting Rollback ==="

    # Stop current container
    if docker ps --format '{{.Names}}' | grep -q "^${SOLO_CONTAINER_NAME}$"; then
        log "Stopping current container..."
        docker stop --time="$STOP_TIMEOUT" "$SOLO_CONTAINER_NAME" 2>/dev/null || true
        docker rm "$SOLO_CONTAINER_NAME" 2>/dev/null || true
    fi

    # Restore config files
    if [[ -f "$backup_path/docker-compose.yml" ]]; then
        cp "$backup_path/docker-compose.yml" "$PROJECT_DIR/"
        log "Restored docker-compose.yml"
    fi

    if [[ -f "$backup_path/.env" ]]; then
        cp "$backup_path/.env" "$PROJECT_DIR/"
        log "Restored .env"
    fi

    # Restart services
    cd "$PROJECT_DIR"
    docker compose up -d solo

    if check_health; then
        log "Rollback successful!"
        log "=== Rollback Complete ==="
    else
        log_error "Rollback failed - Solo is still not healthy"
        log "Manual intervention required. Check logs: docker logs $SOLO_CONTAINER_NAME"
        return 1
    fi
}

# Rollback to latest backup
rollback() {
    local latest_backup
    latest_backup=$(ls -td "$BACKUP_DIR"/*/ 2>/dev/null | head -1)

    if [[ -z "$latest_backup" ]]; then
        log_error "No backups found to rollback to"
        return 1
    fi

    log "Rolling back to: $latest_backup"
    rollback_to "$latest_backup"
}

# Rotate backups - keep only MAX_BACKUPS
rotate_backups() {
    local count
    count=$(ls -d "$BACKUP_DIR"/*/ 2>/dev/null | wc -l)

    if [[ "$count" -gt "$MAX_BACKUPS" ]]; then
        log "Rotating backups (keeping last $MAX_BACKUPS of $count)..."
        ls -td "$BACKUP_DIR"/*/ | tail -n +"$((MAX_BACKUPS + 1))" | while read -r old_backup; do
            log "Removing old backup: $old_backup"
            rm -rf "$old_backup"
        done
    fi
}

# Main
ensure_dirs

case $ACTION in
    deploy)
        deploy
        ;;
    rollback)
        rollback
        ;;
esac
