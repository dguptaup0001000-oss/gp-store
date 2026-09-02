# Disaster recovery (single VPS)

GP-STORE production is **one Hostinger VPS**. That is not multi-server HA
and it is not 99.9% availability. A disk, host, or site failure takes the
shop offline until this procedure is finished.

Do **not** restore into the live `gpstore` database as a drill.
Do **not** run `docker compose down -v`.

## What is proven vs remaining

| Capability | Status |
|---|---|
| On-VPS dump + checksum + 14-day retention | Implemented (backup sidecar) |
| Isolated restore of a dump (never into `gpstore`) | Implemented (`backup-restore-drill.sh`) |
| Encrypted off-box copy (GitHub artifact) | Implemented (workflow **Off-box backup**) |
| Decrypt + isolated restore of the **downloaded** artifact | Implemented in the same workflow |
| Second machine / multi-AZ / automatic failover | **Not available.** Remaining limitation. |
| Point-in-time recovery finer than the dump interval | **Not available.** |

## Realistic RPO / RTO

These are operator estimates for this architecture, not a vendor SLA.

| Metric | Realistic value | Why |
|---|---|---|
| **RPO** (on-VPS) | up to **6 hours** | Sidecar dumps every `BACKUP_INTERVAL_SECONDS` (default 21600) |
| **RPO** (off-box) | up to **6 hours** | Off-box workflow runs 01:20, 07:20, 13:20, 19:20 UTC, after each successful production deploy on `main`, and takes a fresh dump first |
| **RTO** (same VPS, Postgres volume intact) | **15–45 minutes** | Redeploy from `main`; no restore |
| **RTO** (same VPS, database lost, dump on volume) | **1–3 hours** | Restore latest `.dump` into a recovered Postgres, then boot |
| **RTO** (VPS gone, recover from off-box `.gpg`) | **1–2 hours** | New VPS + Docker + `.env` + `recover-onto-new-vps.sh` + DNS/TLS. The restore itself is one command and a few minutes; the hours are provisioning, secrets and DNS propagation |

If the last off-box run failed, RPO is “last successful artifact”, which can
be older. The hourly **Backup alert** workflow and the Off-box job itself
email on failure. Act on a red run the same day.

## VPS-loss recovery (from the encrypted off-box artifact)

**There is a script for steps 3-7.** `recover-onto-new-vps.sh` does the
decrypt, the checksum, the restore and the verification in one command, and
CI drills it end to end on every change — including that its guards hold, so
it cannot restore over a shop that still has orders in it. The manual list
below is what it does, kept because an operator should be able to read what a
script is about to do to their database before they run it, and because steps
1, 2 and 8 still need a person.

```bash
I_HAVE_LOST_THE_PRODUCTION_SERVER=yes \
BACKUP_GPG_PASSPHRASE_FILE=/safe/passphrase \
  ./deploy/production/recover-onto-new-vps.sh \
    gpstore-YYYYMMDDThhmmssZ.dump.gpg 127.0.0.1 5432 gpstore gpstore
```

It refuses unless you mean it, refuses a database that already holds orders,
checks everything it needs *before* touching the data, and shreds the
plaintext dump on exit. It will not provision the server, recreate
`backend/.env`, or move DNS — those need credentials a script must never
hold, and it tells you which are still outstanding.

1. Provision a replacement Ubuntu VPS. Do not reuse a compromised disk.
2. Recreate `/opt/gp-store` from `main` (`deploy/production/prepare-vps.sh` /
   `deploy.sh`). Recreate `backend/.env` from operator secrets — **do not**
   invent MSG91 / Cashfree / Firebase / Play / JWT / DB passwords.
3. Start Postgres + Redis only. Do **not** point Traefik at a backend until
   the database is restored.
4. Download the latest **Off-box backup** artifact `gpstore-offbox-backup`
   from GitHub Actions (90-day retention).
5. Decrypt on an admin workstation or the new VPS:

   ```bash
   gpg --batch --decrypt --pinentry-mode loopback \
     --passphrase-file /safe/BACKUP_GPG_PASSPHRASE \
     -o gpstore-YYYYMMDDThhmmssZ.dump \
     gpstore-YYYYMMDDThhmmssZ.dump.gpg
   sha256sum -c gpstore-YYYYMMDDThhmmssZ.dump.sha256
   ```

6. Restore into a **new** empty database, verify Flyway, then cut over.
   Never restore a drill into a live shop:

   ```bash
   ./deploy/production/backup-restore-drill.sh \
     ./gpstore-YYYYMMDDThhmmssZ.dump \
     127.0.0.1 5432 gpstore gpstore_restore_probe "$DB_PASSWORD"
   ```

   After the probe looks right, restore into the production database name
   **only** on the replacement VPS (this is the emergency path, not a drill).

7. Start backend. Confirm `GET /v1/api/health` 200, `GET /v1/api/health/ready`
   ready, catalog feed, and that admin routes still return 401 without a JWT.
8. Point DNS at the new VPS if the IP changed. Wait for TLS.

## What you must keep offline

- `BACKUP_GPG_PASSPHRASE` (GitHub environment **production**)
- VPS `backend/.env` values (DB, JWT, Redis, optional MSG91/Cashfree/Firebase)
- SSH key used by `PROD_SSH_PRIVATE_KEY`

Losing the GPG passphrase makes off-box artifacts unreadable. Losing only
the VPS is recoverable from the artifact **if** the passphrase still exists
in GitHub.

## Multi-server HA

A second always-on replica (another VPS, managed Postgres with HA, or a
cloud region) is **not** in place. Do not claim 99.9% HA until that exists.
The mitigations on one VPS are: Compose restart policies, Traefik health on
`/actuator/health` (not backup status), 6-hour dumps, encrypted off-box
copies, and documented restore.
