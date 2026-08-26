# GP-STORE backend on Hostinger VPS (Docker Compose + Traefik)

**This is the only production runbook.** Production is this Hostinger VPS
stack. Do not use Render. Do not run systemd + Nginx next to this stack.

```
Internet (HTTPS :443)
   → Traefik (Let's Encrypt for api.gpstore.co.in)
   → Spring Boot container  (8081, context-path /v1, Docker network only)
   → PostgreSQL 17 container (5432, Docker network only)
   → Redis container        (6379, Docker network only)
```

Public ports on the VPS: **22, 80, 443**. Never 5432, 6379, or 8081.

Canonical files:

| What | Path |
|---|---|
| Compose | `backend/docker-compose.yml` |
| Env template (placeholders) | `backend/.env.example` |
| Image | `backend/Dockerfile` |
| Legacy systemd/Nginx (do not enable) | `deploy/hostinger/` |

Laptop Compose (published 5432/6379/8081): `docker compose -f docker-compose.dev.yml up`.
Never use that file, or the repo-root `docker-compose.yml`, on the VPS.

Java 21, Spring Boot 3.5.3, Maven Wrapper `./mvnw`.

Production API:

- `https://api.gpstore.co.in/v1/api/health`
- `https://api.gpstore.co.in/v1/actuator/health`
- Cashfree notify: `https://api.gpstore.co.in/v1/api/payments/webhooks/cashfree`

Flutter production builds must use `https://api.gpstore.co.in/v1`.

---

## Live audit (2026-08-26)

Public checks from this agent against `api.gpstore.co.in`:

| Check | Result |
|---|---|
| DNS `api.gpstore.co.in` | **A `187.127.173.192`** (Hostinger VPS) |
| `http://api.gpstore.co.in/v1/api/health` | **301** → HTTPS |
| `https://api.gpstore.co.in/v1/api/health` | **200** `GP-STORE Backend Running Successfully!` |
| TLS certificate | **CN=`api.gpstore.co.in`**, issuer **Let's Encrypt YR2**, SAN `api.gpstore.co.in` |
| `https://api.gpstore.co.in/v1/api/health/ready` | **200** `{"status":"ready"}` |
| Production database | Docker Compose Postgres 17 on this VPS (`gpstore_pg_data`). Do not `DROP` / `flyway clean` / `docker compose down -v`. |

Traefik must be **v3.6.1+** on Docker Engine 29 (this repo pins `traefik:v3.6.7`). Older Traefik spoke Docker API 1.24 and never discovered labels, so ACME never ran.

---

## 1. Hostinger VPS requirement

- KVM 2 (2 vCPU / 8 GB RAM) or larger, **India** region
- Ubuntu 24.04 LTS
- Public IPv4 (write it down; DNS needs it)
- Firewall: **22, 80, 443 only**

## 2. Docker

```bash
sudo apt-get update
sudo apt-get install -y docker.io docker-compose-v2 git curl ufw
sudo usermod -aG docker "$USER"   # log out and back in
sudo systemctl enable --now docker
docker compose version
```

Do **not** install Nginx, certbot, or a host Redis for this layout. Traefik and the Redis container own those jobs. `deploy/hostinger/install.sh` installs the competing stack; do not run it.

## 3. Firewall

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
```

## 4. Environment file (no secrets in git)

```bash
git clone https://github.com/dguptaup0001000-oss/gp-store.git
cd gp-store/backend
cp .env.example .env
nano .env
```

Replace every `CHANGE_ME`. Keep `API_DOMAIN=api.gpstore.co.in`.

Required:

| Name | Meaning |
|---|---|
| `API_DOMAIN` | `api.gpstore.co.in` |
| `ACME_EMAIL` | Let's Encrypt contact |
| `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | Compose Postgres (new empty volume unless you restore a dump) |
| `JWT_SECRET` | 64+ random characters, **not** the repo default |
| `CORS_ALLOWED_ORIGINS` | Browser origins, comma-separated HTTPS, not `*` |
| `REDIS_PASSWORD` | Redis `requirepass` |
| `STORE_LATITUDE` / `STORE_LONGITUDE` | Shop pin |

Compose sets `DB_URL=jdbc:postgresql://postgres:5432/${DB_NAME}` and `REDIS_HOST=redis`. Do not set those to localhost.

`SPRING_PROFILES_ACTIVE=prod` → `APP_PRODUCTION=true`, `DDL_AUTO=validate`, forwarded-header trust.

Optional vendor keys: Cashfree, MSG91, Firebase, Cloudinary. Production never uses the mock OTP provider. Missing MSG91 credentials do not block boot; SMS OTP send fails closed until they are set.

## 5. Database — do not destroy shop data

Compose Postgres 17 is the **production database** (named volume `gpstore_pg_data`). Restarting containers does **not** wipe it.

**Do not** run `flyway clean`, `DROP DATABASE`, or `docker compose down -v` on a volume that has shop data.

If you already have a dump:

```bash
# After `docker compose up -d` and postgres is healthy:
# custom format (current backups):
docker compose exec -T postgres \
  pg_restore --clean --if-exists --no-owner --no-acl -U "$DB_USERNAME" -d "$DB_NAME" \
  < gpstore-YYYYMMDDThhmmssZ.dump
# legacy gzip SQL:
gunzip -c gpstore-YYYY-MM-DD.sql.gz | docker compose exec -T postgres psql -U "$DB_USERNAME" -d "$DB_NAME"
```

Store dump files **off** the VPS. Restoring overwrites the volume — do not run it twice by accident.

Flyway V2–current (including `pg_trgm` search indexes in `V5`) runs on first boot of an empty database. There is no manual `CREATE INDEX` step.

Shop data already lives on `gpstore_pg_data`. Do not restore a dump over it unless you intend to overwrite.

## 6. DNS

Required record (already in place as of 2026-08-26):

```
api.gpstore.co.in  A  187.127.173.192
```

Do **not** change nameservers, the apex/root `gpstore.co.in` record, or MX records.

Confirm from several resolvers:

```bash
dig +short api.gpstore.co.in A
# must print 187.127.173.192
```

If you use Cloudflare, grey-cloud (DNS only) until the certificate issues, then Full (strict).

HTTP-01 ACME needs port 80 on that IP. Traefik cannot issue Let's Encrypt if `api` does not resolve to this VPS.

## 7. Start the stack

From `gp-store/backend` on the VPS, after `.env` is filled and DNS is the VPS A record:

```bash
docker compose config    # must succeed; published ports must be 80 and 443 only
docker compose up -d --build
docker compose ps
curl -fsS https://api.gpstore.co.in/v1/api/health
ss -lntp   # must NOT list 5432, 6379, or 8081 on 0.0.0.0
```

Traefik owns 80/443. The Spring Boot container does not bind them.

## 8. Health

Inside the backend container:

```
GET http://127.0.0.1:8081/v1/actuator/health
GET http://127.0.0.1:8081/v1/api/health
```

Docker `HEALTHCHECK` uses actuator. Prometheus/metrics remain ADMIN JWT only.

## 9. HTTPS

Traefik obtains and renews the certificate. HTTP redirects to HTTPS.

## 10. Logs

```bash
cd ~/gp-store/backend
docker compose logs -f backend
docker compose logs -f traefik
docker compose logs -f postgres
docker compose logs -f redis
```

## 11. Restart

```bash
docker compose restart backend
docker compose up -d
```

`restart: unless-stopped` survives VPS reboot if Docker is enabled (section 2).

## 12. Update / redeploy

**Normal path:** push to `main`. GitHub Actions workflow **Deploy Production**
SSHs to the VPS and runs `deploy/production/deploy.sh`. Do not SSH just to
`git pull` / `docker compose build`.

One-time secrets and VPS layout: **[deploy/production/README.md](../deploy/production/README.md)**.

Emergency on the VPS (same checks as CI):

```bash
cd /opt/gp-store   # or $DEPLOY_ROOT
git fetch origin
./deploy/production/deploy.sh "$(git rev-parse origin/main)"
```

Do **not** run `docker compose down -v`. That deletes shop data.

## 13. Backup

See **[deploy/production/BACKUPS.md](../deploy/production/BACKUPS.md)** for the
full restore procedure.

The `backup` Compose service dumps Postgres every 6 hours as custom-format
`gpstore-YYYYMMDDThhmmssZ.dump` files into the `gpstore_pg_backups` volume,
keeps 14 days, and never deletes the latest successful file. Failed dumps
are not promoted to `LATEST`. Each run inserts a row into `ops_backup_runs`.
Admins can read status at `GET /v1/api/admin/ops/backups` (JWT, ADMIN role).

That volume is still on this VPS until you copy it off-box:

```bash
# systemd timer (recommended) — see deploy/production/gpstore-backup-offbox.*.example
BACKUP_OFFBOX_TARGET=user@other-host:/safe/gpstore-backups \
  ./deploy/production/backup-offbox-sync.sh
# or copy by hand:
docker compose exec -T backup ls -l /backups
# scp the latest .dump (and .sha256) to another machine you control.
```

Restore drill (isolated database named `gpstore_restore_probe`, never `gpstore`):

```bash
deploy/production/backup-restore-drill.sh /path/to/gpstore-....dump \
  127.0.0.1 5432 gpstore gpstore_restore_probe 'the-password'
```

Emergency restore onto production is a last resort and replaces live data:

```bash
docker compose exec -T postgres \
  pg_restore --clean --if-exists --no-owner --no-acl -U "$DB_USERNAME" -d "$DB_NAME" \
  < gpstore-YYYYMMDDThhmmssZ.dump
```

Do **not** treat a manual `pg_dump` one-liner as the backup system. The sidecar
must be running (`docker compose ps backup`).

## 13b. Redis password file

`REDIS_PASSWORD` stays in `backend/.env` (gitignored). `deploy.sh` copies it
to `backend/.secrets/redis_password` (mode 600) so Compose can mount a Docker
secret. Redis and the backend containers do **not** list `REDIS_PASSWORD` in
`environment:`, so `docker inspect` does not show the password.

Manual compose from `backend/`:

```bash
python3 docker/redis/materialize-password-file.py .
docker compose up -d
```

## 13c. Catalog compatibility

`GET /v1/api/products` is still a paged, capped JSON **array** (default 20,
max 50, sellable products only). It sends `Deprecation: true` and
`Link: </v1/api/products/feed>; rel="successor-version"`.

The Flutter app uses `GET /v1/api/products/feed` (Spring Data page, stable
id sort, totals). Do not delete the array endpoint while any old client
still calls it.

## 13d. Monitoring

See **[deploy/production/MONITORING.md](../deploy/production/MONITORING.md)**.
On the VPS: `./deploy/production/check-health.sh`.

Admin: `GET /v1/api/admin/ops/status` (JWT, ADMIN) for backups, Redis PING,
backup-volume disk, and TLS expiry. Not public.

## 14. Rollback

Application: previous git commit, then `docker compose build backend && docker compose up -d backend`.

Database: restore a dump from section 13. Flyway is forward-only; do not edit applied SQL.

```bash
docker compose exec -T postgres \
  pg_restore --clean --if-exists --no-owner --no-acl -U "$DB_USERNAME" -d "$DB_NAME" \
  < gpstore-YYYYMMDDThhmmssZ.dump
```

## 15. Flutter APK

Rebuild a **production** APK only after `https://api.gpstore.co.in/v1/api/health` succeeds with a certificate for `api.gpstore.co.in`.

CI already defaults to `https://api.gpstore.co.in/v1` when `vars.API_BASE_URL` is empty. Do not point `API_BASE_URL` at Render, localhost, or any host other than `https://api.gpstore.co.in/v1`.

## 16. Troubleshooting

| Symptom | Check |
|---|---|
| Certificate is Traefik default / self-signed | `api.gpstore.co.in` A record must be `187.127.173.192`; Traefik image must be v3.6.1+; port 80 must reach Traefik for HTTP-01. |
| 404 from the public hostname | Traefik labels / backend container not healthy. `docker compose ps` and `docker compose logs traefik`. |
| 502 | `docker compose ps` — backend healthy? `docker compose logs backend` |
| App refuses to start | `JWT_SECRET` still the repo default, or `DDL_AUTO` is not `validate` |
| Search errors `similarity` | V5 should create `pg_trgm`. Confirm Flyway in logs |
| Login rate-limit all from one IP | `RATE_LIMIT_TRUST_FORWARDED_FOR=true` is set in Compose |
| Redis down, login still works but caps per JVM | AUTH/CHECKOUT/ADMIN fail closed to a local limiter; SEARCH/cart fail open (documented in `RateLimitFilter`) |
| Port 80/443 already in use | leftover Nginx/systemd from `deploy/hostinger/`. Stop them. |

GitHub Actions **does** SSH to Hostinger when secrets `PROD_HOST`,
`PROD_USER`, and `PROD_SSH_PRIVATE_KEY` are set (workflow
`deploy-production.yml`). Until those secrets exist, a green `main` build
does not change the VPS. See `deploy/production/README.md`.
