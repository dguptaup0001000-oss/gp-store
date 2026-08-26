# GP-STORE PostgreSQL backups

Production dumps run in the Compose `backup` sidecar (`postgres:17` image,
same major version as the database). They do **not** depend on the Spring
Boot container staying healthy.

## What is written

- Custom-format compressed dumps: `gpstore-YYYYMMDDThhmmssZ.dump`
- SHA-256 sidecar: `gpstore-….dump.sha256`
- `LATEST` — filename of the last **successful** dump only
- `status.txt` — last success metadata
- Failed runs stay as `*.partial` (deleted on failure) and never replace `LATEST`

Retention: 14 days (`BACKUP_RETENTION_DAYS`). The file named in `LATEST` is
never deleted even if it is older than the retention window.

The dump volume `gpstore_pg_backups` is **on the VPS**. That is not off-box
storage. Copy dumps off the machine.

## Off-box copy (required)

On the VPS, after at least one successful dump:

```bash
# /etc/gpstore/backup-offbox.env (mode 600), one line:
# BACKUP_OFFBOX_TARGET=user@other-host:/safe/gpstore-backups

sudo cp deploy/production/gpstore-backup-offbox.service.example /etc/systemd/system/gpstore-backup-offbox.service
sudo cp deploy/production/gpstore-backup-offbox.timer.example /etc/systemd/system/gpstore-backup-offbox.timer
# Edit the service EnvironmentFile / BACKUP_OFFBOX_TARGET.
sudo systemctl daemon-reload
sudo systemctl enable --now gpstore-backup-offbox.timer
```

One-shot:

```bash
BACKUP_OFFBOX_TARGET=user@other-host:/safe/gpstore-backups \
  ./deploy/production/backup-offbox-sync.sh
```

Wrappers (same scripts):

- `scripts/backup/postgres-backup.sh once|daemon`
- `scripts/backup/postgres-restore-drill.sh`

## Restore drill (never production)

The drill **drops and recreates the named database**. It refuses `gpstore`,
`postgres`, and `template*` names.

```bash
# laptop / CI, isolated database
./deploy/production/backup-restore-drill.sh \
  /path/to/gpstore-YYYYMMDDThhmmssZ.dump \
  127.0.0.1 5432 gpstore gpstore_restore_probe 'the-password'
```

Integrity: `pg_restore --list` plus `flyway_schema_history` and a public-table
count. Optional `.sha256` is checked when present.

## Emergency restore onto production

Last resort. Replaces live data. Take a fresh dump first if the volume still
has a good file. Do **not** run `docker compose down -v`.

```bash
# custom format (current)
docker compose exec -T postgres \
  pg_restore --clean --if-exists --no-owner --no-acl -U "$DB_USERNAME" -d "$DB_NAME" \
  < gpstore-YYYYMMDDThhmmssZ.dump

# legacy gzip SQL (older files only)
gunzip -c gpstore-YYYYMMDDThhmmssZ.sql.gz | docker compose exec -T postgres \
  psql -U "$DB_USERNAME" -d "$DB_NAME"
```

Status: `GET /v1/api/admin/ops/backups` (ADMIN JWT) and
`docker compose exec -T backup cat /backups/status.txt`.
