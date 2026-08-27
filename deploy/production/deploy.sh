#!/usr/bin/env bash
# GP-STORE production deploy. Idempotent. Fail-fast. Never destroys volumes.
#
# Usage: deploy.sh <40-char-sha>
#
# Does NOT destroy Compose volumes. Postgres and Redis stay up.
# Traefik is not restarted. Only the backend image is built, then replaced.

set -Eeuo pipefail

umask 077

DEPLOY_ROOT="${DEPLOY_ROOT:-/opt/gp-store}"
STATE_DIR="${STATE_DIR:-/var/lib/gp-store}"
COMPOSE_DIR="${DEPLOY_ROOT}/backend"
COMPOSE_FILE="${COMPOSE_DIR}/docker-compose.yml"
STATE_FILE="${STATE_DIR}/deployment-state"
LOCK_FILE="${STATE_DIR}/deploy.lock"
HEALTH_URL="http://127.0.0.1:8081/v1/actuator/health"
API_HEALTH_URL="http://127.0.0.1:8081/v1/api/health"
READY_URL="http://127.0.0.1:8081/v1/api/health/ready"
VERSION_URL="http://127.0.0.1:8081/v1/api/version"
PUBLIC_VERSION_URL="${PUBLIC_VERSION_URL:-https://api.gpstore.co.in/v1/api/version}"
PUBLIC_HEALTH_URL="${PUBLIC_HEALTH_URL:-https://api.gpstore.co.in/v1/actuator/health}"
API_HOST="${API_DOMAIN:-api.gpstore.co.in}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-180}"
PUBLIC_SHA_TIMEOUT_SECONDS="${PUBLIC_SHA_TIMEOUT_SECONDS:-45}"
REDIS_HEALTH_TIMEOUT_SECONDS="${REDIS_HEALTH_TIMEOUT_SECONDS:-90}"
REQUIRE_ORIGIN_MAIN="${REQUIRE_ORIGIN_MAIN:-1}"
IMAGE_NAME="gp-store-backend"

TARGET_SHA="${1:-${TARGET_SHA:-}}"
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
REPLACED=0
ROLLBACK_ATTEMPTED=0
PREV_SHA=""
PREV_IMAGE=""

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() {
  log "ERROR: $*"
  dump_diagnostics || true
  if [ "${REPLACED:-0}" -eq 1 ] && [ "${ROLLBACK_ATTEMPTED:-0}" -eq 0 ]; then
    rollback || true
  fi
  exit 1
}

refuse_destructive_compose() {
  if printf '%s' "$*" | grep -Eq -- 'down[[:space:]]+(-v|--volumes)\b'; then
    die "Refusing a compose command that would destroy volumes: $*"
  fi
}

compose() {
  refuse_destructive_compose docker compose "$@"
  docker compose -f "$COMPOSE_FILE" --project-directory "$COMPOSE_DIR" "$@"
}

json_field() {
  python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get(sys.argv[1],""))' "$1"
}

is_full_sha() {
  [[ "$1" =~ ^[0-9a-fA-F]{40}$ ]]
}

backend_curl() {
  compose exec -T backend curl -fsS "$@"
}

write_state() {
  local sha="$1"
  local image="$2"
  local status="$3"
  umask 077
  mkdir -p "$STATE_DIR"
  cat > "${STATE_FILE}.tmp" <<EOF
CURRENT_SHA=${sha}
CURRENT_IMAGE=${image}
LAST_STATUS=${status}
LAST_SUCCESSFUL_DEPLOYMENT=${4:-}
LAST_SUCCESSFUL_SHA=${5:-}
UPDATED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
  mv "${STATE_FILE}.tmp" "$STATE_FILE"
}

load_state() {
  if [ -f "$STATE_FILE" ]; then
    # shellcheck disable=SC1090
    source "$STATE_FILE"
  fi
}

running_backend_id() {
  compose ps -q backend 2>/dev/null || true
}

capture_previous() {
  load_state
  PREV_SHA="${CURRENT_SHA:-}"
  PREV_IMAGE="${CURRENT_IMAGE:-}"
  local id
  id="$(running_backend_id)"
  if [ -n "$id" ]; then
    PREV_IMAGE="$(docker inspect --format '{{.Config.Image}}' "$id")"
    local env_sha
    env_sha="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$id" | sed -n 's/^GIT_COMMIT=//p' | head -1 || true)"
    if is_full_sha "${env_sha:-}"; then
      PREV_SHA="$env_sha"
    fi
  fi
}

dump_diagnostics() {
  log "--- compose ps ---"
  compose ps || true
  log "--- Traefik-local /api/version ---"
  curl -sS --max-time 10 --http1.1 \
    --resolve "${API_HOST}:443:127.0.0.1" \
    -w "\nhttp_code=%{http_code}\n" \
    "https://${API_HOST}/v1/api/version" || true
  log "--- Traefik-local /api/health/live ---"
  curl -sS --max-time 10 --http1.1 \
    --resolve "${API_HOST}:443:127.0.0.1" \
    -w "\nhttp_code=%{http_code}\n" \
    "https://${API_HOST}/v1/api/health/live" || true
  log "--- boot / Flyway errors ---"
  compose logs --tail=200 backend 2>/dev/null \
    | grep -E 'Flyway|checksum mismatch|APPLICATION FAILED|Refusing to start|Permission denied|IllegalStateException|Caused by: org.flywaydb' \
    || true
  log "--- backend logs (last 80) ---"
  compose logs --tail=80 backend || true
  log "--- redis logs (last 40) ---"
  compose logs --tail=40 redis || true
}

wait_for_redis() {
  local deadline=$((SECONDS + REDIS_HEALTH_TIMEOUT_SECONDS))
  while (( SECONDS < deadline )); do
    if compose exec -T redis sh -c \
      'REDISCLI_AUTH="$(tr -d "\r\n" < /run/secrets/redis_password)" redis-cli ping' \
      2>/dev/null | grep -q PONG; then
      return 0
    fi
    log "Waiting for Redis PONG"
    sleep 2
  done
  return 1
}

wait_for_health() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
  local actuator="" api_health="" ready=""
  while (( SECONDS < deadline )); do
    actuator="$(backend_curl "$HEALTH_URL" 2>/dev/null || true)"
    api_health="$(backend_curl "$API_HEALTH_URL" 2>/dev/null || true)"
    ready="$(backend_curl "$READY_URL" 2>/dev/null || true)"
    local actuator_status ready_status
    actuator_status="$(printf '%s' "$actuator" | json_field status 2>/dev/null || true)"
    ready_status="$(printf '%s' "$ready" | json_field status 2>/dev/null || true)"
    if [ "$actuator_status" = "UP" ] \
      && printf '%s' "$api_health" | grep -q "GP-STORE Backend Running Successfully" \
      && [ "$ready_status" = "ready" ]; then
      printf '%s' "$actuator"
      return 0
    fi
    log "Waiting for health (actuator=${actuator_status:-none} ready=${ready_status:-none})"
    sleep 5
  done
  return 1
}

verify_public_sha() {
  local expected="$1"
  local deadline=$((SECONDS + PUBLIC_SHA_TIMEOUT_SECONDS))
  local body="" running=""
  while (( SECONDS < deadline )); do
    body="$(curl -fsS --max-time 15 \
      -H 'Cache-Control: no-cache' -H 'Pragma: no-cache' \
      "$PUBLIC_VERSION_URL" 2>/dev/null || true)"
    running="$(printf '%s' "$body" | json_field gitCommit 2>/dev/null || true)"
    if [ "$running" = "$expected" ]; then
      printf '%s' "$body"
      return 0
    fi
    log "Public /api/version gitCommit=${running:-none} expected=$expected"
    sleep 5
  done
  return 1
}

# Hits Traefik on this box, not Cloudflare. 82f567b booted Flyway V28 and
# was healthy in-container for 3 minutes; the public hostname still served
# f229fd9 (edge cache / hairpin DNS) and the deploy rolled back a working
# API. Local TLS to 127.0.0.1:443 is the path that must match.
# Do not use curl -f: a Traefik 404 body is the diagnosis (router missing
# .service when two services exist on the backend container).
verify_traefik_sha() {
  local expected="$1"
  local deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
  local body="" running="" http_code=""
  local url="https://${API_HOST}/v1/api/version"
  while (( SECONDS < deadline )); do
    body="$(curl -sS --max-time 15 --http1.1 \
      --resolve "${API_HOST}:443:127.0.0.1" \
      -w '\n%{http_code}' \
      "$url" 2>/dev/null || printf '\n000')"
    http_code="${body##*$'\n'}"
    body="${body%$'\n'*}"
    running="$(printf '%s' "$body" | json_field gitCommit 2>/dev/null || true)"
    if [ "$running" = "$expected" ]; then
      printf '%s' "$body"
      return 0
    fi
    log "Traefik-local /api/version http=${http_code:-000} gitCommit=${running:-none} expected=$expected body=$(printf '%s' "$body" | tr '\n' ' ' | cut -c1-160)"
    sleep 5
  done
  return 1
}

read_running_commit() {
  backend_curl "$VERSION_URL" | json_field gitCommit
}

report_optional_config() {
  log "Optional configuration (missing values do not fail this deploy):"
  compose exec -T backend sh -c '
    set_or_unset() {
      eval "val=\${$1-}"
      if [ -n "$val" ]; then echo "  $1=set"; else echo "  $1=unset"; fi
    }
    echo "  MSG91_ENABLED=${MSG91_ENABLED:-}"
    set_or_unset MSG91_AUTH_KEY
    set_or_unset MSG91_OTP_TEMPLATE_ID
    echo "  CASHFREE_ENVIRONMENT=${CASHFREE_ENVIRONMENT:-}"
    set_or_unset CASHFREE_WEBHOOK_SECRET
    echo "  FIREBASE_PUSH_ENABLED=${FIREBASE_PUSH_ENABLED:-}"
  ' || log "Could not inspect optional config inside the container"
}

rollback() {
  ROLLBACK_ATTEMPTED=1
  echo
  echo "===================================="
  echo "DEPLOYMENT FAILED"
  echo "ROLLBACK STARTED"
  echo "===================================="
  if [ -z "${PREV_IMAGE:-}" ]; then
    echo "CRITICAL:"
    echo "DEPLOYMENT FAILED"
    echo "ROLLBACK FAILED"
    echo "No previous backend image is recorded."
    return 1
  fi
  if ! docker image inspect "$PREV_IMAGE" >/dev/null 2>&1; then
    echo "CRITICAL:"
    echo "DEPLOYMENT FAILED"
    echo "ROLLBACK FAILED"
    echo "Previous image missing: $PREV_IMAGE"
    return 1
  fi
  local rollback_tag="$PREV_SHA"
  if ! is_full_sha "${rollback_tag:-}"; then
    rollback_tag="$(printf '%s' "$PREV_IMAGE" | awk -F: '{print $NF}')"
  fi
  log "Restoring image $PREV_IMAGE (sha=${rollback_tag:-unknown})"
  if [ -n "${PREV_SHA:-}" ] && is_full_sha "$PREV_SHA"; then
    git -C "$DEPLOY_ROOT" fetch origin --prune
    git -C "$DEPLOY_ROOT" checkout main
    git -C "$DEPLOY_ROOT" reset --hard "$PREV_SHA"
  fi
  python3 "$COMPOSE_DIR/docker/redis/materialize-password-file.py" "$COMPOSE_DIR" \
    || log "Could not rematerialize Redis password file during rollback"
  export BACKEND_IMAGE_TAG="${rollback_tag}"
  export GIT_COMMIT="${PREV_SHA:-unknown}"
  # Redis may be crash-looping independently of the backend image.
  compose up -d redis || true
  wait_for_redis || log "Redis still not healthy after rollback recreate"
  compose up -d --no-deps --no-build backend
  local health
  if ! health="$(wait_for_health)"; then
    echo "CRITICAL:"
    echo "DEPLOYMENT FAILED"
    echo "ROLLBACK FAILED"
    echo "Previous image did not become healthy."
    return 1
  fi
  local running
  running="$(read_running_commit || true)"
  write_state "${PREV_SHA:-unknown}" "$PREV_IMAGE" "rollback" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${PREV_SHA:-}"
  echo "DEPLOYMENT FAILED"
  echo "ROLLBACK SUCCESSFUL"
  echo "Health: $health"
  echo "Version: ${running:-unknown}"
  return 1
}

on_err() {
  local exit_code=$?
  if [ "$ROLLBACK_ATTEMPTED" -eq 1 ]; then
    exit "$exit_code"
  fi
  if [ "$REPLACED" -eq 1 ]; then
    rollback || true
  else
    log "Build/pre-replace failed; leaving the running backend untouched."
    echo "===================================="
    echo "DEPLOYMENT FAILED"
    echo "===================================="
  fi
  exit "$exit_code"
}

trap on_err ERR

if [ -z "$TARGET_SHA" ]; then
  die "Usage: $0 <40-character-git-sha>"
fi
if ! is_full_sha "$TARGET_SHA"; then
  die "TARGET_SHA must be the full 40-character git SHA, got: $TARGET_SHA"
fi
if [ ! -d "$DEPLOY_ROOT/.git" ]; then
  die "No git repository at $DEPLOY_ROOT. Run deploy/production/prepare-vps.sh once."
fi
if [ ! -f "$COMPOSE_FILE" ]; then
  die "Missing $COMPOSE_FILE"
fi
if [ ! -f "$COMPOSE_DIR/.env" ]; then
  die "Missing $COMPOSE_DIR/.env (production secrets). This file is never in git."
fi
command -v docker >/dev/null || die "docker is not installed"
docker compose version >/dev/null || die "docker compose v2 is required"
command -v python3 >/dev/null || die "python3 is required for JSON parsing"

python3 "$COMPOSE_DIR/docker/redis/materialize-password-file.py" "$COMPOSE_DIR" \
  || die "Could not materialize Redis password file from backend/.env"

mkdir -p "$STATE_DIR"
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  die "Another deployment holds $LOCK_FILE"
fi

TARGET_IMAGE="${IMAGE_NAME}:${TARGET_SHA}"
ENDED_AT=""

echo "===================================="
echo "GP-STORE PRODUCTION DEPLOYMENT"
echo "===================================="
echo "Repository: $DEPLOY_ROOT"
echo "Branch: main"
echo "Target SHA: $TARGET_SHA"
echo "Target Image: $TARGET_IMAGE"
echo "Deployment start: $STARTED_AT"
echo

capture_previous
echo "Previous SHA: ${PREV_SHA:-none}"
echo "Previous image: ${PREV_IMAGE:-none}"
echo

echo "[1/8] Fetching source"
git -C "$DEPLOY_ROOT" remote get-url origin >/dev/null
git -C "$DEPLOY_ROOT" fetch origin --prune

echo "[2/8] Verifying SHA"
REMOTE_MAIN="$(git -C "$DEPLOY_ROOT" rev-parse origin/main)"
if [ "$REQUIRE_ORIGIN_MAIN" = "1" ] && [ "$TARGET_SHA" != "$REMOTE_MAIN" ]; then
  die "TARGET_SHA $TARGET_SHA is not origin/main ($REMOTE_MAIN). Refusing to deploy an arbitrary working tree."
fi
# Untracked backend/.env is preserved: reset --hard does not delete it.
# Never git clean.
if git -C "$DEPLOY_ROOT" show-ref --verify --quiet refs/heads/main; then
  git -C "$DEPLOY_ROOT" checkout main
else
  git -C "$DEPLOY_ROOT" checkout -b main origin/main
fi
git -C "$DEPLOY_ROOT" reset --hard origin/main
WORKING_SHA="$(git -C "$DEPLOY_ROOT" rev-parse HEAD)"
if [ "$WORKING_SHA" != "$TARGET_SHA" ]; then
  die "HEAD $WORKING_SHA != GITHUB_SHA $TARGET_SHA. Stopping before build."
fi
log "HEAD is $WORKING_SHA on branch $(git -C "$DEPLOY_ROOT" branch --show-current)"

python3 "$COMPOSE_DIR/docker/redis/materialize-password-file.py" "$COMPOSE_DIR" \
  || die "Could not materialize Redis password file after checkout"

echo "[3/8] Building image"
if docker image inspect "$TARGET_IMAGE" >/dev/null 2>&1; then
  log "Image $TARGET_IMAGE already exists; rebuilding to match this tree"
fi
(
  cd "$COMPOSE_DIR"
  BACKEND_IMAGE_TAG="$TARGET_SHA" \
    GIT_COMMIT="$TARGET_SHA" \
    APP_VERSION="0.0.1-SNAPSHOT" \
    docker compose -f "$COMPOSE_FILE" build backend
)
docker image inspect "$TARGET_IMAGE" >/dev/null || die "Build did not produce $TARGET_IMAGE"
log "Image $TARGET_IMAGE built. Current production container is still the previous version."

echo "[4/8] Database migration"
log "Flyway runs on backend startup (FLYWAY_ENABLED=true, DDL_AUTO=validate)."
log "Postgres and Redis volumes are untouched."

echo "[4b/8] Infra sidecars"
# Socket proxy, backup daemon, redis config file. Does not recreate Postgres.
# Traefik is recreated only when its Compose definition changed.
compose up -d dockerproxy
compose up -d redis backup
wait_for_redis || die "Redis did not become healthy within ${REDIS_HEALTH_TIMEOUT_SECONDS}s"
compose up -d traefik
HOST_TUNING="$DEPLOY_ROOT/deploy/production/apply-host-tuning.sh"
if [ -f "$HOST_TUNING" ]; then
  bash "$HOST_TUNING" "$DEPLOY_ROOT/deploy/production/sysctl-gpstore.conf" \
    || log "Host sysctl tuning skipped (need root once via prepare-vps.sh)"
fi

echo "[5/8] Starting backend"
REPLACED=1
(
  cd "$COMPOSE_DIR"
  BACKEND_IMAGE_TAG="$TARGET_SHA" \
    GIT_COMMIT="$TARGET_SHA" \
    APP_VERSION="0.0.1-SNAPSHOT" \
    docker compose -f "$COMPOSE_FILE" up -d --no-deps --no-build backend
)

echo "[6/8] Health check"
HEALTH_BODY="$(wait_for_health)" || die "Backend health did not become UP within ${HEALTH_TIMEOUT_SECONDS}s"
log "Health: $HEALTH_BODY"

echo "[7/8] Version verification"
VERSION_BODY="$(backend_curl "$VERSION_URL")"
RUNNING_SHA="$(printf '%s' "$VERSION_BODY" | json_field gitCommit)"
log "In-container version: $VERSION_BODY"
if [ "$RUNNING_SHA" != "$TARGET_SHA" ]; then
  die "Expected SHA $TARGET_SHA != running gitCommit $RUNNING_SHA"
fi
TRAEFIK_VERSION_BODY="$(verify_traefik_sha "$TARGET_SHA")" \
  || die "Traefik on 127.0.0.1:443 did not serve gitCommit $TARGET_SHA"
log "Traefik-local version: $TRAEFIK_VERSION_BODY"
if PUBLIC_VERSION_BODY="$(verify_public_sha "$TARGET_SHA")"; then
  log "Public version: $PUBLIC_VERSION_BODY"
else
  log "WARNING: Public $PUBLIC_VERSION_URL still stale after ${PUBLIC_SHA_TIMEOUT_SECONDS}s. In-container and Traefik-local match $TARGET_SHA; not rolling back."
  PUBLIC_VERSION_BODY="$TRAEFIK_VERSION_BODY"
fi

echo "[8/8] Deployment complete"
ENDED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
write_state "$TARGET_SHA" "$TARGET_IMAGE" "success" "$ENDED_AT" "$TARGET_SHA"
report_optional_config

echo
echo "===================================="
echo "DEPLOYMENT SUCCESS"
echo "===================================="
echo "Commit: $TARGET_SHA"
echo "Image: $TARGET_IMAGE"
echo "Health: $HEALTH_BODY"
echo "Version: $VERSION_BODY"
echo "Traefik-local version: ${TRAEFIK_VERSION_BODY:-}"
echo "Public version: ${PUBLIC_VERSION_BODY:-}"
echo "Previous SHA: ${PREV_SHA:-none}"
echo "Deployment start: $STARTED_AT"
echo "Deployment end: $ENDED_AT"
echo "===================================="
