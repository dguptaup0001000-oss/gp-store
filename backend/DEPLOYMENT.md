# Deploying GP-Store (app on Hostinger VPS + database on Supabase)

Operator checklist (what is code vs a server/env-file click, and the
`DDL_AUTO=validate` step): see the repo-root `PRODUCTION_CHECKLIST.md`.

Exact Ubuntu / Java 21 / Redis / Nginx / systemd / HTTPS commands:
**[`HOSTINGER_DEPLOYMENT.md`](../HOSTINGER_DEPLOYMENT.md)**.

This project does not use Railway or Render for app hosting. The database
stays on **Supabase**.

Why this required almost no application-code changes: the app was already
platform-agnostic - it reads `PORT` from the environment
(`server.port=${PORT:8081}`) rather than hardcoding it, binds to all
network interfaces by default (Nginx is what should be public), and builds
from a standard multi-stage Dockerfile or `./mvnw package`. The things that
change are *where* environment variables live (`/opt/gpstore/env.production`)
and *how* you get a public URL (your DNS → Nginx :443).

## One-time setup (summary)

Follow `HOSTINGER_DEPLOYMENT.md` numbered steps 1–20. In short:

1. Ubuntu LTS VPS, India, SSH keys, UFW 22/80/443 only.
2. `openjdk-21-jre-headless` (plus JDK if you compile on the VPS).
3. Redis bound to `127.0.0.1` with `requirepass`; never public.
4. Nginx reverse-proxy to `127.0.0.1:8081`; HTTP → HTTPS.
5. Copy `deploy/hostinger/env.production.example` to
   `/opt/gpstore/env.production` (mode 0600) and fill real values.
6. `cd backend && ./mvnw -B clean package -DskipTests` then install the jar
   as `/opt/gpstore/backend.jar`.
7. `deploy/hostinger/run-backend.sh` + `gpstore-backend.service`
   (`Restart=always`, enabled on boot).

Environment variable names (never commit values):

```
DB_URL=jdbc:postgresql://db.<project-ref>.supabase.co:5432/postgres?sslmode=require
DB_USERNAME=postgres
DB_PASSWORD=<your Supabase database password>
FLYWAY_ENABLED=true
DDL_AUTO=validate
APP_PRODUCTION=true
JWT_SECRET=<a real random 64+ character string - never reuse the local dev default>
CORS_ALLOWED_ORIGINS=<your real frontend domain, once you have one>
STORE_LATITUDE=<your actual shop's latitude>
STORE_LONGITUDE=<your actual shop's longitude>
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=<same as Redis requirepass>
RATE_LIMIT_TRUST_FORWARDED_FOR=true
```

Optional Cloudinary / MSG91 / Firebase / Cashfree: see
`deploy/hostinger/env.production.example`. Production OTP SMS uses MSG91.
Production refuses to boot on the local mock provider or with a missing Auth
Key. Do not put MSG91 credentials in Flutter. Operator steps:
`docs/MSG91_OTP_SETUP.md`.

This repository does **not** set VPS environment variable values. Setting
`DDL_AUTO=validate` remains a change in `/opt/gpstore/env.production`. Do it
only after the `schema-migrate` CI job (below) is green on `main`. Flipping
that variable does not rewrite customer data.

`APP_PRODUCTION=true` makes the app refuse to start if `JWT_SECRET` is
missing, too short for HS256, or still the development default committed
in `application.properties`.

`?sslmode=require` in `DB_URL` is required. Never commit the real
`DB_PASSWORD` to git.

## Empty-database / Flyway CI

Root `.github/workflows/ci.yml` has two test jobs:

- **`build-and-test`** — existing contract: `FLYWAY_ENABLED=false`,
  `DDL_AUTO=update`, plus the three objects Flyway would otherwise create
  (`pg_trgm`, `order_number_seq`, `shedlock`).
- **`schema-migrate`** — clean Postgres, `FLYWAY_ENABLED=true`: first boot
  with `DDL_AUTO=update` (Hibernate then Flyway V2 through current), second
  boot with `DDL_AUTO=validate`.

There is no V1 migration; do not add one. Local commands and the empty-database
procedure: `src/main/resources/db/migration/README.md`.

## Schema migration procedure

Since `DDL_AUTO=validate`, the database is never changed by deploying code
alone. To change the schema:

1. Add a new migration file under
   `backend/src/main/resources/db/migration/`, named `V<n>__description.sql`
   with `<n>` higher than every existing file. Never edit an already-applied
   migration - Flyway records a checksum per file and refuses to start if one
   changes.
2. Update the matching JPA entity in the same commit. `validate` compares the
   live schema against the entities at startup, so a migration without its
   entity change (or the reverse) fails the deploy rather than corrupting
   anything.
3. Push. On the VPS, pull, rebuild the jar, restart systemd. Flyway runs the
   new migration before Hibernate validates, so the order is always
   migrate-then-check.
4. Watch `journalctl -u gpstore-backend`. A failed `validate` means the app
   does not start - `validate` never writes, so a failure here is a startup
   check, not a data risk.

## Health checks

Nginx and uptime monitors should poll the cheap liveness path:

```
GET /v1/api/health
```

(`/v1` because of `server.servlet.context-path=/v1`.) This endpoint is
already public in `SecurityConfig`. Use `/v1/api/health/ready` only when you
intentionally want a database check; do not hammer it.

## After that

GitHub Actions builds APKs and runs tests. It does **not** SSH to Hostinger.
Updating the running jar is step 18 in `HOSTINGER_DEPLOYMENT.md`.

## What this does NOT cover

- Buying the VPS, DNS, or Cloudflare — only you can do that.
- Migrating Supabase data — we are not doing that.
- Scaling to multiple VPS instances — caching, rate limiting, and ShedLock
  are correctness-safe across instances, but a second origin is outside
  this doc.
