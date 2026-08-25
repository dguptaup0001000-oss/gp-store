# GP-STORE backend on Hostinger VPS (Docker Compose + Traefik)

**This is the only production runbook.** Do not use Railway. Do not use Render.
Do not run systemd + Nginx next to this stack.

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

## Live audit (2026-08-25, from this agent — not the VPS)

These checks were run from the cloud agent. They are **not** Hostinger verification.

| Check | Result |
|---|---|
| DNS `api.gpstore.co.in` | **CNAME `s1z20khv.up.railway.app`** → `69.46.46.88` |
| `https://api.gpstore.co.in/v1/api/health` | **404** `Application not found` (Railway) |
| TLS certificate | **CN=`*.up.railway.app`**, does not match `api.gpstore.co.in` |
| SSH to Hostinger | **not available** in this environment |
| Production database location | **unknown** (Railway app is gone; data may still exist in Railway Postgres, Supabase, or a dump). Do not `DROP`/`flyway clean`. |

Until DNS is an **A record to the Hostinger VPS IPv4**, Let's Encrypt on Traefik cannot issue a certificate for `api.gpstore.co.in`, and the Flutter APK will keep hitting a dead Railway hostname.

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

Compose Postgres is the **intended** production database (named volume `gpstore_pg_data`). Restarting containers does **not** wipe it.

This agent **does not** know where today's customer/order/product rows live. The Railway HTTP app is already 404. Possible sources: Railway PostgreSQL, Supabase, a laptop dump.

**Do not** run `flyway clean`, `DROP DATABASE`, or `docker compose down -v` on a volume that has shop data.

If you already have a dump:

```bash
# After `docker compose up -d` and postgres is healthy, BEFORE pointing customers at DNS:
gunzip -c gpstore-YYYY-MM-DD.sql.gz | docker compose exec -T postgres psql -U "$DB_USERNAME" -d "$DB_NAME"
```

If you still have access to the old Postgres (Railway or Supabase), dump **before** deleting that project:

```bash
pg_dump -h <old-host> -U <old-user> -d <old-db> --no-owner --no-acl | gzip > gpstore-$(date +%F).sql.gz
```

Store that file **off** the VPS. Restoring overwrites the volume — do not run it twice by accident.

Flyway V2–current (including `pg_trgm` search indexes in `V5`) runs on first boot of an empty database. There is no manual `CREATE INDEX` step.

This runbook does **not** execute a dump/restore. That needs your approval and access to the current database.

## 6. DNS (must happen before Traefik can get a cert)

At your DNS host, **delete** the Railway CNAME:

```
api.gpstore.co.in  CNAME  s1z20khv.up.railway.app   ← remove this
```

Create:

```
api.gpstore.co.in  A  <Hostinger VPS public IPv4>
```

Wait until this is true from several resolvers:

```bash
dig +short api.gpstore.co.in A
# must print the VPS IPv4, not s1z20khv.up.railway.app / 69.46.46.88
```

If you use Cloudflare, grey-cloud (DNS only) until the certificate issues, then Full (strict).

HTTP-01 ACME needs port 80 on that IP. Do not `docker compose up` for TLS until DNS points here, or Let's Encrypt will fail (or certify the wrong host).

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

Keep the previous image until health is confirmed:

```bash
cd gp-store
git pull origin main
cd backend
docker compose build backend
docker compose up -d backend
curl -fsS https://api.gpstore.co.in/v1/api/health
```

## 13. Backup

```bash
docker compose exec -T postgres pg_dump -U "$DB_USERNAME" "$DB_NAME" | gzip > gpstore-$(date +%F).sql.gz
```

## 14. Rollback

Application: previous git commit, then `docker compose build backend && docker compose up -d backend`.

Database: restore a dump from section 13. Flyway is forward-only; do not edit applied SQL.

```bash
gunzip -c gpstore-YYYY-MM-DD.sql.gz | docker compose exec -T postgres psql -U "$DB_USERNAME" -d "$DB_NAME"
```

## 15. Flutter APK

Rebuild a **production** APK only after `https://api.gpstore.co.in/v1/api/health` succeeds with a certificate for `api.gpstore.co.in`.

CI already defaults to `https://api.gpstore.co.in/v1` when `vars.API_BASE_URL` is empty. Do not point `API_BASE_URL` at Railway, Render, or localhost.

## 16. Troubleshooting

| Symptom | Check |
|---|---|
| Certificate pending / CN is `*.up.railway.app` | DNS still the Railway CNAME. Fix the A record. |
| 404 Application not found | You are still hitting Railway, not the VPS. |
| 502 | `docker compose ps` — backend healthy? `docker compose logs backend` |
| App refuses to start | `JWT_SECRET` still the repo default, or `DDL_AUTO` is not `validate` |
| Search errors `similarity` | V5 should create `pg_trgm`. Confirm Flyway in logs |
| Login rate-limit all from one IP | `RATE_LIMIT_TRUST_FORWARDED_FOR=true` is set in Compose |
| Port 80/443 already in use | leftover Nginx/systemd from `deploy/hostinger/`. Stop them. |

GitHub Actions does **not** SSH to Hostinger. There is no automatic Hostinger deploy in CI (that would need a VPS key stored as a GitHub secret — not in this repository).
