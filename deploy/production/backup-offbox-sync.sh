#!/usr/bin/env bash
# Copy Postgres dumps OFF this VPS. Refuses any destination that is still
# this machine's disk.
#
#   BACKUP_OFFBOX_TARGET=user@other-host:/safe/gpstore-backups \
#     ./deploy/production/backup-offbox-sync.sh
#
# Optional encryption (never printed):
#   BACKUP_OFFBOX_GPG_PASSPHRASE   gpg --symmetric AES256 before scp
#
# BACKUP_OFFBOX_TARGET must already be reachable with the operator's SSH
# keys. This script never stores a password and never logs the target or
# passphrase.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT/backend/docker-compose.yml}"
TARGET="${BACKUP_OFFBOX_TARGET:-}"
GPG_PASS="${BACKUP_OFFBOX_GPG_PASSPHRASE:-}"

if [[ -z "$TARGET" ]]; then
  echo "BACKUP_OFFBOX_TARGET is unset. Example: user@offsite:/var/backups/gpstore" >&2
  echo "Refusing to pretend the only copy on this VPS is off-box." >&2
  exit 1
fi

# Local paths, docker volumes, and this host are the same failure domain as
# Postgres. Off-box means a different machine.
if [[ "$TARGET" != *@*:* ]]; then
  echo "BACKUP_OFFBOX_TARGET must be user@host:/path on another machine." >&2
  echo "A local path is still this VPS disk." >&2
  exit 1
fi
host_part="${TARGET%%:*}"
remote_host="${host_part#*@}"
this_host="$(hostname -f 2>/dev/null || hostname)"
if [[ -n "$remote_host" ]] && [[ "$remote_host" == "$this_host" || "$remote_host" == "localhost" || "$remote_host" == "127.0.0.1" ]]; then
  echo "BACKUP_OFFBOX_TARGET points at this host. That is not off-box." >&2
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

local_sha="$(sha256sum "$WORKDIR/$LATEST" | awk '{print $1}')"
docker compose -f "$COMPOSE_FILE" exec -T backup cat "/backups/${LATEST}.sha256" \
  > "$WORKDIR/${LATEST}.sha256" 2>/dev/null || true
if [[ -s "$WORKDIR/${LATEST}.sha256" ]]; then
  expected="$(awk '{print $1}' "$WORKDIR/${LATEST}.sha256")"
  if [[ -n "$expected" && "$expected" != "$local_sha" ]]; then
    echo "Local copy sha256 does not match sidecar sidecar checksum. Not uploading." >&2
    exit 1
  fi
else
  printf '%s  %s\n' "$local_sha" "$LATEST" > "$WORKDIR/${LATEST}.sha256"
fi

docker compose -f "$COMPOSE_FILE" exec -T backup cat /backups/status.txt \
  > "$WORKDIR/status.txt" 2>/dev/null || true

upload="$WORKDIR/$LATEST"
remote_name="$LATEST"
if [[ -n "$GPG_PASS" ]]; then
  passfile="$(mktemp)"
  printf '%s' "$GPG_PASS" > "$passfile"
  chmod 600 "$passfile"
  gpg --batch --yes --symmetric --cipher-algo AES256 \
    --passphrase-file "$passfile" \
    -o "$WORKDIR/${LATEST}.gpg" "$WORKDIR/$LATEST"
  rm -f "$passfile"
  upload="$WORKDIR/${LATEST}.gpg"
  remote_name="${LATEST}.gpg"
fi

scp "$upload" "$TARGET/"
scp "$WORKDIR/${LATEST}.sha256" "$TARGET/"
if [[ -s "$WORKDIR/status.txt" ]]; then
  scp "$WORKDIR/status.txt" "$TARGET/${LATEST}.status.txt" || true
fi

# Verify the remote object exists and matches size. Do not print the target.
remote_bytes="$(ssh "${TARGET%%:*}" "wc -c < '${TARGET#*:}/$remote_name'" | tr -d ' \r')"
local_bytes="$(wc -c < "$upload" | tr -d ' ')"
if [[ "$remote_bytes" != "$local_bytes" ]]; then
  echo "Remote size $remote_bytes != local $local_bytes. Off-box copy incomplete." >&2
  exit 1
fi

echo "Copied $remote_name off-box (${local_bytes} bytes, sha256=${local_sha})"
