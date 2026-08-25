# GP-STORE backend on Hostinger VPS (Docker Compose + Traefik)

Production path for this backend:

```
Internet (HTTPS :443)
   → Traefik (Let's Encrypt)
   → Spring Boot container  (8081, context-path /v1)
   → PostgreSQL container   (5432, Docker network only)
   → Redis container        (6379, Docker network only)
```

Redis is here because the application already uses it (`spring.cache.type=redis`
and login/checkout rate limits). It is not an extra product.

This file is Hostinger-only. Railway and Render are not used.

Do not put secrets in git. Copy `backend/.env.example` to `backend/.env` on
the VPS.

Replace `YOUR_API_DOMAIN` with the hostname you actually own. Do not invent one
in application code.

Java 21, Spring Boot 3.5.3, Maven Wrapper `./mvnw`.

---

## 1. Hostinger VPS requirement

- KVM 2 (2 vCPU / 8 GB RAM) or larger, **India** region
- Ubuntu 24.04 LTS (22.04 LTS is fine)
- A public IPv4 address
- Firewall: **22, 80, 443 only**. Never 5432, 6379, or 8081 on the public interface.

## 2. Docker requirement

```bash
sudo apt-get update
sudo apt-get install -y docker.io docker-compose-v2 git curl ufw
sudo usermod -aG docker "$USER"   # log out and back in
docker compose version
```

## 3. Docker Compose deployment

```bash
git clone https://github.com/dguptaup0001000-oss/gp-store.git
cd gp-store/backend
cp .env.example .env
nano .env    # fill CHANGE_ME values; set API_DOMAIN and ACME_EMAIL
docker compose config    # must succeed before up
docker compose up -d --build
```

Compose file: `backend/docker-compose.yml`.

Postgres and Redis have **no** `ports:` mapping. The backend has **no** host
port. Traefik publishes 80 and 443.

Laptop development (published ports) is `docker compose -f docker-compose.dev.yml up`.

## 4. Environment variables

See `.env.example`. Required on the VPS:

| Name | Meaning |
|---|---|
| `API_DOMAIN` | Hostname only, e.g. `api.yourshop.com` |
| `ACME_EMAIL` | Let's Encrypt contact |
| `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | Compose Postgres |
| `JWT_SECRET` | 64+ random characters, not the repo default |
| `CORS_ALLOWED_ORIGINS` | Browser origins, comma-separated HTTPS. Not `*` |
| `REDIS_PASSWORD` | Redis `requirepass` |
| `STORE_LATITUDE` / `STORE_LONGITUDE` | Shop pin |

`SPRING_PROFILES_ACTIVE=prod` turns on `application-prod.properties`
(`APP_PRODUCTION=true`, `DDL_AUTO=validate`).

Optional: Cashfree, MSG91, Firebase, Cloudinary — same names as
`application.properties`. Empty Cashfree keys disable online checkout only.

## 5. Database persistence

Named volume `gpstore_pg_data`. Flyway runs on startup (V2–current, including
`pg_trgm` search indexes in `V5__add_search_trigram_indexes.sql`). There is
**no** manual `CREATE INDEX` step after first boot.

The Postgres user in this Compose file is a superuser, so `CREATE EXTENSION`
in V5 works. Do not expose 5432.

If you already have shop data in another Postgres, dump/restore into this
volume **before** pointing customers at the new API. That restore is a
manual operator step.

## 6. Backend healthcheck

Inside the backend container:

```
GET http://127.0.0.1:8081/v1/actuator/health
GET http://127.0.0.1:8081/v1/api/health
```

(`server.servlet.context-path=/v1`). Docker `HEALTHCHECK` uses actuator.
Prometheus/metrics remain ADMIN JWT only.

From the VPS after DNS/TLS:

```bash
curl -fsS https://YOUR_API_DOMAIN/v1/api/health
```

## 7. Domain DNS

Create an **A** record:

```
YOUR_API_DOMAIN  →  <VPS public IPv4>
```

Let's Encrypt HTTP-01 needs port 80 on that IP. If you use Cloudflare, start
with **DNS only** (grey cloud) until the certificate issues, then orange-cloud
with SSL mode Full (strict).

## 8. HTTPS

Traefik obtains and renews the certificate. The Spring Boot container does
not bind 80 or 443.

HTTP is redirected to HTTPS.

Cashfree notify URL after DNS:

`https://YOUR_API_DOMAIN/v1/api/payments/webhooks/cashfree`

## 9. Logs

```bash
cd ~/gp-store/backend   # or wherever you cloned
docker compose logs -f backend
docker compose logs -f traefik
docker compose logs -f postgres
docker compose logs -f redis
```

## 10. Restart

```bash
docker compose restart backend
# or everything:
docker compose up -d
```

`restart: unless-stopped` survives VPS reboot as long as Docker starts on boot:

```bash
sudo systemctl enable --now docker
```

## 11. Update / redeploy

```bash
cd gp-store
git pull origin main
cd backend
docker compose build backend
docker compose up -d backend
curl -fsS https://YOUR_API_DOMAIN/v1/api/health
```

## 12. Database backup

```bash
docker compose exec -T postgres pg_dump -U "$DB_USERNAME" "$DB_NAME" | gzip > gpstore-$(date +%F).sql.gz
```

Store the file off the VPS. Restoring overwrites data — do not run blindly.

```bash
gunzip -c gpstore-YYYY-MM-DD.sql.gz | docker compose exec -T postgres psql -U "$DB_USERNAME" -d "$DB_NAME"
```

## 13. Rollback

1. Keep the previous image: `docker compose images`
2. Checkout the previous git tag/commit, `docker compose build backend && docker compose up -d backend`
3. Database rollback is a restore of the dump from step 12. Flyway migrations
   are forward-only; do not edit applied SQL files.

## 14. Troubleshooting

| Symptom | Check |
|---|---|
| Certificate pending | DNS A record, port 80, `docker compose logs traefik` |
| 502/gateway | `docker compose ps` — backend healthy? `docker compose logs backend` |
| App refuses to start | `JWT_SECRET` still the repo default, or MSG91 missing with `APP_PRODUCTION=true` |
| Search errors `similarity` | Should not happen; V5 creates `pg_trgm`. Confirm Flyway in logs |
| Login rate-limit all from one IP | `RATE_LIMIT_TRUST_FORWARDED_FOR=true` is set in Compose |

UFW:

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
```

Confirm `ss -lntp` does **not** list 5432, 6379, or 8081 on `0.0.0.0`.
