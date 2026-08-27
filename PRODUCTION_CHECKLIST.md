# GP-STORE production checklist

Live architecture: **Hostinger VPS** (Traefik → Spring Boot Docker, Postgres
and Redis on the Docker network only). The Flutter apps are built by GitHub
Actions; they talk only to the HTTPS API host.

Exact commands: [`backend/HOSTINGER_DEPLOYMENT.md`](backend/HOSTINGER_DEPLOYMENT.md).
Deploy file inventory: [`deploy/hostinger/MIGRATION_INVENTORY.md`](deploy/hostinger/MIGRATION_INVENTORY.md).
Flyway / empty-database CI: `backend/src/main/resources/db/migration/README.md`.

## Code vs server

| Change | Where it lives | Who applies it |
|---|---|---|
| Java / Flutter / Flyway SQL / CI workflows | this git repository | merge to `main` (backend auto-deploys when `PROD_*` GitHub secrets exist) |
| `DDL_AUTO`, `JWT_SECRET`, `DB_*`, Redis, Cashfree, SMS, Firebase, Cloudinary | `backend/.env` on the VPS (never git) plus vendor dashboards | a person with SSH / dashboard access |
| Postgres schema beyond what Flyway already applied | not done from this repo | never rewrite `flyway_schema_history` by hand |

This repository does **not** set VPS environment variable **values**. Do not
put passwords, JWT secrets, database URLs with credentials, Cashfree keys,
Firebase JSON, or SMS tokens in git.

## Required GitHub CI on `main` (before flipping `DDL_AUTO`)

1. Job **`schema-migrate`** in `.github/workflows/ci.yml` is **green on `main`**.
   That job boots an empty Postgres with Flyway V2–current, then boots again
   with `DDL_AUTO=validate`.
2. Job **`build-and-test`** is green (`FLYWAY_ENABLED=false`, existing contract).
3. Flutter **`build-apk`** is green if you ship the Android artifacts.

There is **no V1** Flyway file. Do not add one. Production history already
has V2 onward.

## Manual VPS step: `DDL_AUTO=validate`

**Not a code migration.** Hibernate in production must not alter tables on
deploy. After `schema-migrate` is green on `main` and a deploy of that commit
has booted successfully:

1. SSH to the VPS → edit `backend/.env` (Compose reads it).
2. Set `DDL_AUTO=validate` (create the line if it is missing).
3. Leave `FLYWAY_ENABLED=true`. Do not change pool size, Tomcat
   threads, or JVM flags as part of this step.
4. `docker compose up -d backend`
5. Confirm `GET /v1/api/health` and `GET /v1/actuator/health`.

### Rollback if the new deploy does not start

`validate` never writes to the database. A failed boot leaves customer data
as it was.

1. Set `DDL_AUTO` back to `update`, or comment the line so the app uses its
   default.
2. `docker compose up -d backend`
3. Do **not** rewrite Flyway history and do **not** restore
   `backend/docs/production-schema-reference.sql` as a bootstrap script
   (it is a 2026-08-19 snapshot and is missing later columns).

## Environment variables (names only — values stay on the VPS)

Required for a real shop — see `backend/.env.example`:

- `API_DOMAIN`, `ACME_EMAIL`
- `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` (Compose Postgres, not public)
- `FLYWAY_ENABLED=true`, `DDL_AUTO=validate`, `SPRING_PROFILES_ACTIVE=prod`
- `JWT_SECRET` — long random string; never the repo default
- `APP_PRODUCTION=true`
- `REDIS_PASSWORD` (Compose sets `REDIS_HOST=redis`)
- `CORS_ALLOWED_ORIGINS` — real frontend origin(s), comma-separated, not `*`
- `STORE_LATITUDE`, `STORE_LONGITUDE`

Optional, fail-closed if unset: Cashfree, SMS/OTP, Firebase, Cloudinary.

**Defaults:** `DB_POOL_MAX_SIZE=20`, `TOMCAT_MAX_THREADS=80` on one VPS
instance with **local** Postgres. Do not raise those further to “fix” 502s
from a browse flood. Do not drop them back to 10/40 if you still need 100
concurrent checkouts — that combination sheds. Latency is not solved by a
100-connection pool on the same 2 vCPU VPS.

## CORS (code, already in this repo)

Browser checkout sends `Idempotency-Key`. Allowed request headers are
exactly `Authorization`, `Content-Type`, and `Idempotency-Key`. Native
Android apps do not use CORS.

## What this checklist does not do

- Change production secrets.
- Enable Mapbox or PostGIS.
- Increase Hikari / Tomcat / JVM ceilings past the documented 20 / 80 defaults.
- Run 1,000+ VU load tests against the live API URL. Use
  `load-tests/browse-cart-checkout.js` locally/staging, smallest stage first.
- Merge or rewrite Flyway V2–V22.
- Require leftover third-party PaaS GitHub status checks. Production is the
  Hostinger VPS.
- Treat **Render** as a production dependency. It is removed.

Release Android/web builds must pass `--dart-define=APP_ENV=production` (CI
does this). The coded production API is `https://api.gpstore.co.in/v1`.
GitHub `vars.API_BASE_URL` must stay that URL or empty (CI uses the same
default). Do not set it to Render, localhost, or any other host.

As of 2026-08-26 `api.gpstore.co.in` is **A `187.127.173.192`** with a Let's
Encrypt certificate. Confirm `https://api.gpstore.co.in/v1/api/health` returns
200 before treating an APK as production-ready.
See `backend/HOSTINGER_DEPLOYMENT.md`.
