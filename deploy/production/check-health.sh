#!/usr/bin/env bash
# Production health from the VPS. Uses Compose exec — backend 8081 and Redis
# are not published on the host.
#
# Usage (on the VPS):
#   API_HOST=api.gpstore.co.in ./deploy/production/check-health.sh
set -Eeuo pipefail

DEPLOY_ROOT="${DEPLOY_ROOT:-/opt/gp-store}"
COMPOSE_DIR="${COMPOSE_DIR:-$DEPLOY_ROOT/backend}"
COMPOSE_FILE="${COMPOSE_FILE:-$COMPOSE_DIR/docker-compose.yml}"
API_HOST="${API_HOST:-api.gpstore.co.in}"

compose() {
  docker compose -f "$COMPOSE_FILE" --project-directory "$COMPOSE_DIR" "$@"
}

echo "== Compose =="
compose ps

echo
echo "== JVM via docker exec (not host :8081) =="
compose exec -T backend curl -fsS --max-time 5 http://127.0.0.1:8081/v1/api/health
echo

echo "== Readiness (Postgres SELECT 1 + Redis PING) =="
compose exec -T backend curl -fsS --max-time 8 http://127.0.0.1:8081/v1/api/health/ready
echo

echo "== Actuator =="
compose exec -T backend curl -fsS --max-time 8 http://127.0.0.1:8081/v1/actuator/health
echo

echo "== HTTPS via Traefik =="
curl -fsS --max-time 8 "https://${API_HOST}/v1/api/health"
echo

echo "== Redis (Compose network only) =="
compose exec -T redis sh -c 'REDISCLI_AUTH="$(tr -d "\r\n" < /run/secrets/redis_password)" redis-cli ping'
echo

echo "== Backup volume disk =="
compose exec -T backup df -h /backups
echo

echo "== Container memory / CPU (no secrets) =="
ids="$(compose ps -q)"
# shellcheck disable=SC2086
docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}' $ids
