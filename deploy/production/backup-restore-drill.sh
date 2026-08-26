#!/bin/sh
# Restore a GP-STORE pg_dump into an isolated Postgres and verify it.
#
# Usage:
#   backup-restore-drill.sh <dump.dump|dump.sql.gz> <target-host> <target-port> <user> <db> <password>
#
# The target database is dropped and recreated. Never point this at production.
set -eu

DUMP="${1:?dump path required}"
HOST="${2:?host required}"
PORT="${3:?port required}"
USER="${4:?user required}"
DB="${5:?database required}"
export PGPASSWORD="${6:?password required}"

[ -f "$DUMP" ] || { echo "dump not found: $DUMP" >&2; exit 1; }

case "$HOST" in
  *gpstore.co.in*)
    echo "Refusing to restore against production host $HOST" >&2
    exit 1
    ;;
esac

case "$DB" in
  gpstore|postgres|template0|template1)
    echo "Refusing to drop/restore database '$DB' (looks like production or a system database)." >&2
    echo "Use a dedicated drill name such as gpstore_restore_probe." >&2
    exit 1
    ;;
esac

if [ -f "${DUMP}.sha256" ]; then
  echo "checking sha256 for $DUMP"
  (cd "$(dirname "$DUMP")" && sha256sum -c "$(basename "$DUMP").sha256")
fi

echo "restoring $DUMP into ${HOST}:${PORT}/${DB}"

psql -h "$HOST" -p "$PORT" -U "$USER" -d postgres -v ON_ERROR_STOP=1 \
  -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '${DB}' AND pid <> pg_backend_pid();" \
  >/dev/null 2>&1 || true
psql -h "$HOST" -p "$PORT" -U "$USER" -d postgres -v ON_ERROR_STOP=1 \
  -c "DROP DATABASE IF EXISTS ${DB};"
psql -h "$HOST" -p "$PORT" -U "$USER" -d postgres -v ON_ERROR_STOP=1 \
  -c "CREATE DATABASE ${DB};"

case "$DUMP" in
  *.dump)
    pg_restore --list "$DUMP" >/dev/null
    pg_restore -h "$HOST" -p "$PORT" -U "$USER" -d "$DB" --no-owner --no-acl "$DUMP"
    ;;
  *.sql.gz)
    gunzip -c "$DUMP" | psql -h "$HOST" -p "$PORT" -U "$USER" -d "$DB" -v ON_ERROR_STOP=1 >/tmp/restore-drill.log
    ;;
  *)
    echo "unrecognised dump format: $DUMP (want .dump or .sql.gz)" >&2
    exit 1
    ;;
esac

psql -h "$HOST" -p "$PORT" -U "$USER" -d "$DB" -v ON_ERROR_STOP=1 -c "SELECT 1 FROM flyway_schema_history LIMIT 1;" \
  >/dev/null || {
  echo "restore produced no flyway_schema_history" >&2
  exit 1
}

tables="$(psql -h "$HOST" -p "$PORT" -U "$USER" -d "$DB" -Atc \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public';")"
echo "restore ok: ${tables} public tables"
[ "$tables" -ge 5 ] || { echo "too few tables after restore: $tables" >&2; exit 1; }
