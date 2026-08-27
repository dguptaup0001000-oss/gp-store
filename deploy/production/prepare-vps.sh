#!/usr/bin/env bash
# One-time VPS preparation for GitHub Actions auto-deploy.
# Run as root (or a user that can write /opt/gp-store and /var/lib/gp-store).
# Does not start, stop, or recreate production containers.
# Does not touch Postgres/Redis volumes.

set -Eeuo pipefail

DEPLOY_ROOT="${DEPLOY_ROOT:-/opt/gp-store}"
STATE_DIR="${STATE_DIR:-/var/lib/gp-store}"
REPO_URL="${REPO_URL:-https://github.com/dguptaup0001000-oss/gp-store.git}"
DEPLOY_USER="${DEPLOY_USER:-${SUDO_USER:-$USER}}"

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { log "ERROR: $*"; exit 1; }

command -v git >/dev/null || die "install git first"
command -v docker >/dev/null || die "install docker first"
docker compose version >/dev/null || die "install docker compose v2 first"

if [ "$(id -u)" -ne 0 ]; then
  die "Run as root so /opt/gp-store and /var/lib/gp-store can be created"
fi

mkdir -p "$STATE_DIR"
chmod 750 "$STATE_DIR"

if [ -d "$DEPLOY_ROOT/.git" ]; then
  log "Git repository already present at $DEPLOY_ROOT"
else
  if [ -e "$DEPLOY_ROOT" ] && [ -n "$(ls -A "$DEPLOY_ROOT" 2>/dev/null || true)" ]; then
    die "$DEPLOY_ROOT exists and is not a git checkout. Move it aside or set DEPLOY_ROOT."
  fi
  log "Cloning $REPO_URL into $DEPLOY_ROOT"
  git clone "$REPO_URL" "$DEPLOY_ROOT"
fi

if [ ! -f "$DEPLOY_ROOT/backend/.env" ]; then
  log "WARNING: $DEPLOY_ROOT/backend/.env is missing."
  log "Copy secrets from the current production checkout. Do not commit them."
  log "If Compose already runs from another directory, copy that backend/.env here"
  log "OR set DEPLOY_ROOT to the existing checkout instead of moving data."
fi

if docker compose version >/dev/null; then
  log "docker compose: $(docker compose version)"
fi

log "Existing named volumes (must keep Postgres/Redis data):"
docker volume ls --format '{{.Name}}' | grep -E 'gpstore_pg_data|gpstore_redis_data|traefik' || log "No gpstore volumes visible yet"

if curl -fsS --max-time 15 https://api.gpstore.co.in/v1/api/health >/dev/null; then
  log "Current public API health: OK"
else
  log "WARNING: could not reach https://api.gpstore.co.in/v1/api/health from this host"
fi

chown -R "$DEPLOY_USER":"$DEPLOY_USER" "$DEPLOY_ROOT" "$STATE_DIR" || true

if [ -f "$DEPLOY_ROOT/deploy/production/apply-host-tuning.sh" ]; then
  bash "$DEPLOY_ROOT/deploy/production/apply-host-tuning.sh" \
    "$DEPLOY_ROOT/deploy/production/sysctl-gpstore.conf" \
    || log "WARNING: host sysctl tuning did not apply"
fi

AUTH_KEYS="/home/${DEPLOY_USER}/.ssh/authorized_keys"
if [ "$DEPLOY_USER" = "root" ]; then
  AUTH_KEYS="/root/.ssh/authorized_keys"
fi
log "Add the GitHub Actions deploy public key to ${AUTH_KEYS}"
log "GitHub secrets required: PROD_HOST PROD_USER PROD_SSH_PRIVATE_KEY (optional PROD_PORT, PROD_APP_DIR)"
log "This script did not start Docker services and did not change volumes."
log "After GitHub secrets are set, push to main (or Actions → Deploy Production → Run workflow)."
