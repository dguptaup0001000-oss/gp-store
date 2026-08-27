# GP-STORE PostgreSQL backups

Production dumps run in the Compose `backup` sidecar (`postgres:17` image,
same major version as the database). They do **not** depend on the Spring
Boot container staying healthy.

## What is written

- Custom-format compressed dumps: `gpstore-YYYYMMDDThhmmssZ.dump`
- SHA-256 sidecar: `gpstore-….dump.sha256`
- `LATEST` — filename of the last **successful** dump only
- `status.txt` — last **attempt** metadata (`status=SUCCESS` or `FAILURE`)
- Failed runs stay as `*.partial` (deleted on failure) and never replace `LATEST`

A failed attempt **overwrites** `status.txt` with `status=FAILURE`. The sidecar
healthcheck and `evaluate-backup-status.sh` then go unhealthy immediately. An
older SUCCESS file is not a substitute.

Retention: 14 days (`BACKUP_RETENTION_DAYS`). The file named in `LATEST` is
never deleted even if it is older than the retention window.

The dump volume `gpstore_pg_backups` is **on the VPS**. That is not off-box
storage. Copy dumps off the machine.

## Sidecar healthcheck

`backup.sh health` (Compose healthcheck every 2 minutes):

- `status.txt` must exist
- `status=` must be `SUCCESS` (a failed attempt is unhealthy even if `LATEST`
  still points at a good dump)
- the SUCCESS dump file must exist
- that file must be newer than **26 hours** (`BACKUP_HEALTH_MAX_MINUTES=1560`)

This is intentionally not 48 hours and does not treat “any dump file exists”
as healthy.

`GET /v1/api/admin/ops/backups` (ADMIN JWT) uses the same rules against
`ops_backup_runs`: last attempt FAILURE, missing, or SUCCESS older than 26h
is unhealthy. Backend `/actuator/health` does **not** include backup status,
so a failed dump pages operators without taking the shop off Traefik.

Hourly GitHub Actions workflow **Backup alert** SSHs, copies `status.txt`,
and fails the run (GitHub emails watchers) when the last attempt is FAILURE
or stale. It does not print secrets.

## Off-box copy (required)

Two supported destinations. Neither may be this VPS disk.

### A. GitHub Actions encrypted artifact (implemented)

Workflow **Off-box backup** (`.github/workflows/offbox-backup.yml`):

1. SSHs to the VPS and runs `backup.sh once` (fresh dump).
2. Pulls the dump + checksum + `status.txt` onto the GitHub runner.
3. Verifies `sha256sum -c` and `pg_restore --list`.
4. Encrypts with `gpg --symmetric --cipher-algo AES256` using GitHub Actions
   environment secret `BACKUP_GPG_PASSPHRASE` (environment **production**).
5. Uploads **only** the `.gpg` plus metadata as a 90-day artifact.
6. Downloads that same artifact back from GitHub storage (proves it left the VPS).
7. Decrypts with the same secret, checks sha256, and restores into a
   **throwaway** Postgres on the runner (`gpstore_offbox_probe`).
8. Verifies `flyway_schema_history` and public table count.
9. Drops the throwaway database. Never restores into production `gpstore`.
10. Deletes plaintext from the runner.

A failed pull, encrypt, upload, decrypt, checksum, or isolated restore fails
the workflow (GitHub emails watchers) and writes `ALERT=UPLOAD_FAILED` /
`ALERT=RESTORE_FAILED` / `ALERT=FAILED` via `evaluate-offbox-result.sh`.

| Item | Value |
|---|---|
| Destination | GitHub Actions artifact store (not `/dev/sda1`) |
| Authentication | `PROD_*` SSH secrets + `GITHUB_TOKEN` (artifact upload) |
| Encryption | GPG AES256, passphrase in `BACKUP_GPG_PASSPHRASE` |
| Retention | 90 days (artifact); 14 days on the VPS volume |
| Restore | workflow decrypts + `backup-restore-drill.sh` into `gpstore_offbox_probe` |
| Verification | `sha256sum -c` + isolated restore + Flyway history |

Add `BACKUP_GPG_PASSPHRASE` under Settings → Secrets and variables → Actions
→ Environment **production**. It must be a long random passphrase for
`gpg --symmetric --cipher-algo AES256`. It is **not** the database password.
Until it exists, the workflow verifies the pull then **fails closed** rather
than uploading a plaintext customer database.

CI (`schema-migrate` job) also encrypts the CI dump with a **job-ephemeral**
passphrase (never the production secret), decrypts it, and isolated-restores
it. That proves the mechanism. It is not a substitute for a production Off-box
backup run.

### B. Operator second host (rsync/scp)

On the VPS, after at least one successful dump:

```bash
# /etc/gpstore/backup-offbox.env (mode 600), one line:
# BACKUP_OFFBOX_TARGET=user@other-host:/safe/gpstore-backups
# optional: BACKUP_OFFBOX_GPG_PASSPHRASE=...

sudo cp deploy/production/gpstore-backup-offbox.service.example /etc/systemd/system/gpstore-backup-offbox.service
sudo cp deploy/production/gpstore-backup-offbox.timer.example /etc/systemd/system/gpstore-backup-offbox.timer
sudo systemctl daemon-reload
sudo systemctl enable --now gpstore-backup-offbox.timer
```

One-shot:

```bash
BACKUP_OFFBOX_TARGET=user@other-host:/safe/gpstore-backups \
  ./deploy/production/backup-offbox-sync.sh
```

The script refuses local paths and `localhost` / `127.0.0.1`. It verifies
remote size after `scp`.

Wrappers (same scripts):

- `scripts/backup/postgres-backup.sh once|daemon|health`
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

Decrypt an off-box artifact first:

```bash
gpg --batch --decrypt --passphrase-file /safe/passphrase \
  -o gpstore-YYYYMMDDThhmmssZ.dump gpstore-YYYYMMDDThhmmssZ.dump.gpg
sha256sum -c gpstore-YYYYMMDDThhmmssZ.dump.sha256
```

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
