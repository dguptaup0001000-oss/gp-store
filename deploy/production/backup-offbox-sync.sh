#!/usr/bin/env bash
# Copy Postgres dumps off the VPS volume. Does not invent credentials.
#
# On the VPS, after the backup sidecar has written /backups:
#   BACKUP_OFFBOX_TARGET=user@other-host:/safe/gpstore-backups \
#     ./deploy/production/backup-offbox-sync.sh
#
# BACKUP_OFFBOX_TARGET must already be reachable with the operator's SSH
# keys. This script never stores a password.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT/backend/docker-compose.yml}"
TARGET="${BACKUP_OFFBOX_TARGET:-}"

if [[ -z "$TARGET" ]]; then
  echo "BACKUP_OFFBOX_TARGET is unset. Example: user@offsite:/var/backups/gpstore" >&2
  echo "Refusing to pretend the only copy on this VPS is off-box." >&2
  exit 1
fi

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

LATEST="$(docker compose -f "$COMPOSE_FILE" exec -T backup sh -c \
  'find /backups -maxdepth 1 \( -name "gpstore-*.dump" -o -name "gpstore-*.sql.gz" \) -type f -printf "%T@ %f\n" | sort -nr | awk "{print \$2; exit}"' \
  | tr -d '\r')"
if [[ -z "$LATEST" ]]; then
  echo "No backup file found in the sidecar volume." >&2
  exit 1
fi

docker compose -f "$COMPOSE_FILE" exec -T backup cat "/backups/$LATEST" > "$WORKDIR/$LATEST"
case "$LATEST" in
  *.sql.gz)
    gzip -t "$WORKDIR/$LATEST"
    ;;
  *.dump)
    docker compose -f "$COMPOSE_FILE" exec -T backup pg_restore --list "/backups/$LATEST" >/dev/null
    ;;
esac
scp "$WORKDIR/$LATEST" "$TARGET/"
docker compose -f "$COMPOSE_FILE" exec -T backup cat "/backups/${LATEST}.sha256" \
  > "$WORKDIR/${LATEST}.sha256" 2>/dev/null || true
if [[ -s "$WORKDIR/${LATEST}.sha256" ]]; then
  scp "$WORKDIR/${LATEST}.sha256" "$TARGET/" || true
fi
echo "Copied $LATEST to $TARGET"
