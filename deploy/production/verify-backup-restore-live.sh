#!/usr/bin/env bash
# Live VPS backup/restore verification. Read-mostly.
#
# Allowed writes:
#   - one fresh pg_dump via the existing backup sidecar into gpstore_pg_backups
#   - INSERT into ops_backup_runs (sidecar heartbeat), if that table exists
#   - create/destroy throwaway container gpstore-restore-drill only
#
# Forbidden: compose down, volume prune, pg_restore into production,
# restart of gpstore-* services, git reset, deploy.sh, editing .env.
set -Eeuo pipefail

DEPLOY_ROOT="${DEPLOY_ROOT:-/opt/gp-store}"
COMPOSE_DIR="${DEPLOY_ROOT}/backend"
COMPOSE_FILE="${COMPOSE_DIR}/docker-compose.yml"
DRILL_NAME="gpstore-restore-drill"
DRILL_DB="gpstore_restore_probe"
DRILL_USER="gpstore"
DRILL_PASSWORD="gpstore-restore-drill-not-production"

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
section() { printf '\n======== %s ========\n' "$*"; }

die() {
  log "STOP: $*"
  docker rm -fv "$DRILL_NAME" >/dev/null 2>&1 || true
  exit 1
}

compose() {
  if printf '%s' "$*" | grep -Eq -- 'down[[:space:]]+(-v|--volumes)\b'; then
    die "refusing a compose down -v command"
  fi
  # Never attach this script's stdin to a container (bash -s / ssh).
  docker compose -f "$COMPOSE_FILE" --project-directory "$COMPOSE_DIR" "$@" </dev/null
}

docker() {
  if [ "${1:-}" = exec ]; then
    command docker "$@" </dev/null
  else
    command docker "$@"
  fi
}

prod_fingerprint() {
  compose exec -T postgres sh -c \
    'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -Atc "SELECT current_database() || chr(9) || pg_database_size(current_database())::text || chr(9) || (SELECT count(*)::text FROM information_schema.tables WHERE table_schema = '\''public'\'');"'
}

section "0. preflight (no production writes)"
log "CMD: test compose layout and production health"
[ -d "$DEPLOY_ROOT/.git" ] || die "no git checkout at $DEPLOY_ROOT"
[ -f "$COMPOSE_FILE" ] || die "missing $COMPOSE_FILE"
command -v docker >/dev/null || die "docker missing"
docker compose version >/dev/null || die "docker compose v2 missing"

HEAD="$(git -C "$DEPLOY_ROOT" rev-parse HEAD)"
log "RESULT: VPS HEAD=$HEAD"
log "CMD: curl -fsS --max-time 15 https://api.gpstore.co.in/v1/api/health"
HEALTH="$(curl -fsS --max-time 15 https://api.gpstore.co.in/v1/api/health || true)"
log "RESULT: health=${HEALTH}"
printf '%s' "$HEALTH" | grep -q "GP-STORE Backend Running Successfully" \
  || die "production /api/health is not OK; refusing to continue"

log "CMD: docker compose ps"
compose ps
log "CMD: production postgres fingerprint (database, bytes, public tables)"
FP_BEFORE="$(prod_fingerprint)"
log "RESULT: fingerprint_before=${FP_BEFORE}"
PROD_DB_NAME="$(printf '%s' "$FP_BEFORE" | awk -F'\t' '{print $1}')"
[ -n "$PROD_DB_NAME" ] || die "could not read production database name"
[ "$PROD_DB_NAME" != "$DRILL_DB" ] || die "production database name unexpectedly equals drill name"
log "CMD: list production databases named like the drill (must not be required for this check)"
PROBE_ON_PROD="$(compose exec -T postgres sh -c \
  'psql -U "$POSTGRES_USER" -d postgres -v ON_ERROR_STOP=1 -Atc "SELECT coalesce(string_agg(datname, \",\"), \"\") FROM pg_database WHERE datname IN ('\''gpstore_restore_probe'\'', '\''gpstore-restore-drill'\'');"')"
log "RESULT: probe_dbs_on_production_postgres=${PROBE_ON_PROD:-none}"

section "1. scheduler / job identity"
log "CMD: docker inspect gpstore-backup-1 (command, restart, mounts; no Env)"
docker inspect gpstore-backup-1 --format \
  'Name={{.Name}} Restart={{.HostConfig.RestartPolicy.Name}} Running={{.State.Running}} Health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} Cmd={{json .Config.Cmd}} Entrypoint={{json .Config.Entrypoint}}'
log "CMD: backup container mounts"
docker inspect gpstore-backup-1 --format '{{range .Mounts}}{{.Type}} name={{.Name}} src={{.Source}} dst={{.Destination}} rw={{.RW}}{{println}}{{end}}'
log "CMD: postgres data mounts"
docker inspect gpstore-postgres-1 --format '{{range .Mounts}}{{.Type}} name={{.Name}} src={{.Source}} dst={{.Destination}} rw={{.RW}}{{println}}{{end}}'

BACKUP_VOL="$(docker inspect gpstore-backup-1 --format '{{range .Mounts}}{{if eq .Destination "/backups"}}{{.Name}}{{end}}{{end}}')"
PG_VOL="$(docker inspect gpstore-postgres-1 --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}')"
log "RESULT: backup_volume=${BACKUP_VOL}"
log "RESULT: postgres_volume=${PG_VOL}"
[ -n "$BACKUP_VOL" ] && [ -n "$PG_VOL" ] || die "could not resolve Docker volumes"
[ "$BACKUP_VOL" != "$PG_VOL" ] || die "backup volume and postgres data volume are the SAME — unsafe"

log "CMD: crontab / systemd off-box timer (read-only)"
crontab -l 2>/dev/null | grep -i backup || log "RESULT: no crontab backup lines for this user"
ls -l /etc/cron.d 2>/dev/null || true
systemctl list-timers --all 2>/dev/null | grep -i gpstore || log "RESULT: no gpstore systemd timers listed"
systemctl is-enabled gpstore-backup-offbox.timer 2>/dev/null || log "RESULT: gpstore-backup-offbox.timer not enabled (or not installed)"
systemctl is-active gpstore-backup-offbox.timer 2>/dev/null || log "RESULT: gpstore-backup-offbox.timer not active"
ls -l /etc/systemd/system/gpstore-backup-offbox.service /etc/systemd/system/gpstore-backup-offbox.timer 2>/dev/null \
  || log "RESULT: off-box unit files not installed under /etc/systemd/system"
if [ -f /etc/gpstore/backup-offbox.env ]; then
  if grep -qE '^BACKUP_OFFBOX_TARGET=.+' /etc/gpstore/backup-offbox.env; then
    log "RESULT: /etc/gpstore/backup-offbox.env exists and BACKUP_OFFBOX_TARGET is set (value not printed)"
  else
    log "RESULT: /etc/gpstore/backup-offbox.env exists but BACKUP_OFFBOX_TARGET is empty"
  fi
else
  log "RESULT: /etc/gpstore/backup-offbox.env missing"
fi

section "2. existing dumps (before fresh backup)"
log "CMD: ls -lah /backups inside backup sidecar"
compose exec -T backup sh -c 'ls -lah /backups; echo ---LATEST---; cat /backups/LATEST 2>/dev/null || echo MISSING; echo ---status.txt---; cat /backups/status.txt 2>/dev/null || echo MISSING'
log "CMD: df -h / and /backups"
df -h /
compose exec -T backup df -h /backups

section "3. fresh backup via sidecar once (writes dump volume + optional heartbeat row only)"
log "CMD: docker compose exec -T backup /bin/sh /backup.sh once"
compose exec -T backup /bin/sh /backup.sh once \
  || die "fresh backup.sh once failed — stopping before restore drill"
log "CMD: cat /backups/LATEST /backups/status.txt and ls dump"
compose exec -T backup sh -c 'ls -lah /backups; echo ---LATEST---; cat /backups/LATEST; echo; echo ---status.txt---; cat /backups/status.txt'
LATEST="$(compose exec -T backup sh -c 'tr -d "\r\n" < /backups/LATEST')"
log "RESULT: latest_filename=${LATEST}"
[[ "$LATEST" =~ ^gpstore-[0-9]{8}T[0-9]{6}Z\.dump$ ]] || die "LATEST is not a gpstore-*.dump name: ${LATEST}"

BYTES="$(compose exec -T backup sh -c "wc -c < /backups/${LATEST}" | tr -d '[:space:]')"
log "RESULT: dump_bytes=${BYTES}"
[ "${BYTES:-0}" -ge 1000 ] || die "fresh dump is too small (${BYTES} bytes)"

log "CMD: sha256sum -c on sidecar .sha256"
compose exec -T backup sh -c "cd /backups && sha256sum -c ${LATEST}.sha256" \
  || die "sha256 check failed"
log "CMD: pg_restore --list (integrity, no restore)"
compose exec -T backup sh -c "pg_restore --list /backups/${LATEST} | awk 'END{print NR, \"toc_lines\"}'" \
  || die "pg_restore --list failed on fresh dump"
TOC_HEAD="$(compose exec -T backup sh -c "pg_restore --list /backups/${LATEST} | grep -E 'TABLE DATA|TABLE ' | head -n 20")"
log "RESULT: toc_sample (first TABLE lines, no row data):"
printf '%s\n' "$TOC_HEAD"

section "4. isolated restore drill (container ${DRILL_NAME}, db ${DRILL_DB}, network none)"
log "CMD: docker rm -fv ${DRILL_NAME} if leftover from a prior attempt"
docker rm -fv "$DRILL_NAME" >/dev/null 2>&1 || true
log "CMD: docker run isolated postgres:17 mounting backup volume read-only (NOT postgres data volume)"
docker run -d --name "$DRILL_NAME" \
  --network none \
  --restart=no \
  -e POSTGRES_USER="$DRILL_USER" \
  -e POSTGRES_PASSWORD="$DRILL_PASSWORD" \
  -e POSTGRES_DB="$DRILL_DB" \
  -v "${BACKUP_VOL}:/backups:ro" \
  postgres:17 >/dev/null
log "CMD: wait for pg_isready inside drill container"
READY=0
for _ in $(seq 1 40); do
  if docker exec "$DRILL_NAME" pg_isready -U "$DRILL_USER" -d postgres </dev/null >/dev/null 2>&1; then
    READY=1
    break
  fi
  sleep 1
done
[ "$READY" -eq 1 ] || die "drill postgres did not become ready"

log "CMD: DROP/CREATE ${DRILL_DB} inside drill container only"
docker exec -e PGPASSWORD="$DRILL_PASSWORD" "$DRILL_NAME" \
  psql -U "$DRILL_USER" -d postgres -v ON_ERROR_STOP=1 \
  -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '${DRILL_DB}' AND pid <> pg_backend_pid();" \
  >/dev/null 2>&1 || true
docker exec -e PGPASSWORD="$DRILL_PASSWORD" "$DRILL_NAME" \
  psql -U "$DRILL_USER" -d postgres -v ON_ERROR_STOP=1 \
  -c "DROP DATABASE IF EXISTS ${DRILL_DB};"
docker exec -e PGPASSWORD="$DRILL_PASSWORD" "$DRILL_NAME" \
  psql -U "$DRILL_USER" -d postgres -v ON_ERROR_STOP=1 \
  -c "CREATE DATABASE ${DRILL_DB};"

log "CMD: pg_restore --no-owner --no-acl into ${DRILL_DB} (drill container only)"
set +e
docker exec -e PGPASSWORD="$DRILL_PASSWORD" "$DRILL_NAME" \
  pg_restore -U "$DRILL_USER" -d "$DRILL_DB" --no-owner --no-acl "/backups/${LATEST}"
RESTORE_RC=$?
set -e
log "RESULT: pg_restore_exit=${RESTORE_RC} (1 can be warnings; schema check is authoritative)"

log "CMD: verify flyway_schema_history and public table count in drill DB"
docker exec -e PGPASSWORD="$DRILL_PASSWORD" "$DRILL_NAME" \
  psql -U "$DRILL_USER" -d "$DRILL_DB" -v ON_ERROR_STOP=1 \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
DRILL_TABLES="$(docker exec -e PGPASSWORD="$DRILL_PASSWORD" "$DRILL_NAME" \
  psql -U "$DRILL_USER" -d "$DRILL_DB" -Atc \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';")"
DRILL_DB_CHECK="$(docker exec -e PGPASSWORD="$DRILL_PASSWORD" "$DRILL_NAME" \
  psql -U "$DRILL_USER" -d "$DRILL_DB" -Atc "SELECT current_database();")"
log "RESULT: drill_database=${DRILL_DB_CHECK} public_tables=${DRILL_TABLES}"
[ "$DRILL_DB_CHECK" = "$DRILL_DB" ] || die "drill connected to unexpected database ${DRILL_DB_CHECK}"
[ "$DRILL_TABLES" -ge 5 ] || die "too few tables after restore: ${DRILL_TABLES}"

log "CMD: docker rm -fv ${DRILL_NAME} (removes drill anonymous volumes only; named backup volume stays)"
docker rm -fv "$DRILL_NAME"
docker ps -a --filter "name=${DRILL_NAME}" --format '{{.Names}} {{.Status}}' \
  | grep -q . && die "drill container still present after rm" || log "RESULT: drill container removed"

section "5. production unchanged"
log "CMD: production fingerprint after drill"
FP_AFTER="$(prod_fingerprint)"
log "RESULT: fingerprint_after=${FP_AFTER}"
[ "$FP_BEFORE" = "$FP_AFTER" ] || die "production fingerprint changed during drill (before=${FP_BEFORE} after=${FP_AFTER})"
log "CMD: curl production health again"
HEALTH2="$(curl -fsS --max-time 15 https://api.gpstore.co.in/v1/api/health || true)"
log "RESULT: health=${HEALTH2}"
printf '%s' "$HEALTH2" | grep -q "GP-STORE Backend Running Successfully" \
  || die "production health failed after drill"
log "CMD: compose ps postgres/backup/backend (should still be running)"
compose ps postgres backup backend

section "DONE"
log "Fresh dump ${LATEST} (${BYTES} bytes) verified with sha256 and pg_restore --list."
log "Isolated restore into ${DRILL_NAME}/${DRILL_DB} had ${DRILL_TABLES} public tables."
log "Production database ${PROD_DB_NAME} fingerprint unchanged."
log "BACKUP_RESTORE_VERIFY_OK"
