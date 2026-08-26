#!/bin/sh
# PostgreSQL backup for GP-STORE.
#
# Modes:
#   once     take one backup and exit (CI restore drill, cron one-shot)
#   daemon   take one backup, then sleep BACKUP_INTERVAL_SECONDS (default 6h)
#
# Writes pg_dump custom-format compressed files to BACKUP_DIR (default
# /backups). Never deletes the last SUCCESS dump. Failed/partial files stay
# named *.partial and are never promoted to LATEST.
set -eu

MODE="${1:-once}"
BACKUP_DIR="${BACKUP_DIR:-/backups}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
INTERVAL="${BACKUP_INTERVAL_SECONDS:-21600}"
PGHOST="${PGHOST:-postgres}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-${DB_USERNAME:-gpstore}}"
PGDATABASE="${PGDATABASE:-${DB_NAME:-gpstore}}"
export PGPASSWORD="${PGPASSWORD:-${DB_PASSWORD:-}}"

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

record() {
  status="$1"
  filename="$2"
  bytes="${3:-0}"
  sha="${4:-}"
  detail="${5:-}"
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 \
    -c "INSERT INTO ops_backup_runs (taken_at, filename, bytes, sha256, status, detail)
        SELECT now(), '$filename', $bytes, NULLIF('$sha',''), '$status', NULLIF('$detail','')
        WHERE EXISTS (
          SELECT 1 FROM information_schema.tables
          WHERE table_schema = 'public' AND table_name = 'ops_backup_runs'
        );" >/dev/null 2>&1 || log "WARN could not record backup heartbeat (table missing?)"
}

take_backup() {
  mkdir -p "$BACKUP_DIR"
  chmod 700 "$BACKUP_DIR" 2>/dev/null || true
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  filename="gpstore-${stamp}.dump"
  dest="${BACKUP_DIR}/${filename}"
  tmp="${dest}.partial"

  avail="$(df -Pk "$BACKUP_DIR" 2>/dev/null | awk 'NR==2 {print $4}')"
  if [ "${avail:-0}" -lt 51200 ]; then
    record FAILURE "$filename" 0 "" "disk almost full (${avail:-0} KiB free)"
    log "ERROR not enough free space in ${BACKUP_DIR} (${avail:-0} KiB)"
    return 1
  fi

  log "starting backup ${filename}"
  rm -f "$tmp"
  if ! pg_dump -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
      --format=custom --compress=9 --no-owner --no-acl -f "$tmp"; then
    rm -f "$tmp"
    record FAILURE "$filename" 0 "" "pg_dump failed"
    log "ERROR pg_dump failed"
    return 1
  fi

  if ! pg_restore --list "$tmp" >/dev/null 2>&1; then
    rm -f "$tmp"
    record FAILURE "$filename" 0 "" "pg_restore --list failed integrity check"
    log "ERROR dump failed integrity check"
    return 1
  fi

  bytes="$(wc -c < "$tmp" | tr -d ' ')"
  if [ "${bytes:-0}" -lt 100 ]; then
    rm -f "$tmp"
    record FAILURE "$filename" "$bytes" "" "dump too small"
    log "ERROR dump too small (${bytes} bytes)"
    return 1
  fi

  sha="$(sha256sum "$tmp" | awk '{print $1}')"
  mv "$tmp" "$dest"
  printf '%s  %s\n' "$sha" "$filename" > "${dest}.sha256"
  printf '%s\n' "$filename" > "${BACKUP_DIR}/LATEST"
  printf 'taken_at=%s\nbytes=%s\nsha256=%s\nfilename=%s\nstatus=SUCCESS\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$bytes" "$sha" "$filename" \
    > "${BACKUP_DIR}/status.txt"
  record SUCCESS "$filename" "$bytes" "$sha" "ok"
  log "wrote ${dest} (${bytes} bytes sha256=${sha})"

  newest="$(cat "${BACKUP_DIR}/LATEST")"
  find "$BACKUP_DIR" -maxdepth 1 \( -name 'gpstore-*.dump' -o -name 'gpstore-*.sql.gz' \) \
    -mtime "+${RETENTION_DAYS}" ! -name "$newest" -print | while read -r gone; do
      log "expired ${gone}"
      rm -f "$gone" "${gone}.sha256"
    done
  return 0
}

case "$MODE" in
  once)
    take_backup
    ;;
  daemon)
    log "backup daemon interval=${INTERVAL}s retention=${RETENTION_DAYS}d"
    while true; do
      take_backup || log "backup attempt failed; will retry after interval"
      sleep "$INTERVAL"
    done
    ;;
  *)
    log "usage: backup.sh once|daemon"
    exit 2
    ;;
esac
