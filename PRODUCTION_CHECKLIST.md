# GP-STORE production checklist

Live architecture: **Render** (Spring Boot Docker web service) + **Supabase**
(PostgreSQL) + **Redis** (Render Key Value or equivalent). The Flutter apps
are built by GitHub Actions; they are not hosted on Railway.

This file is the operator checklist. Deploy how-to with placeholders:
`backend/DEPLOYMENT.md`. Flyway / empty-database CI:
`backend/src/main/resources/db/migration/README.md`.

## Code vs dashboard

| Change | Where it lives | Who applies it |
|---|---|---|
| Java / Flutter / Flyway SQL / CI workflows | this git repository | merge to `main` |
| `DDL_AUTO`, `JWT_SECRET`, `DB_*`, Redis, Cashfree, SMS, Firebase, Cloudinary | Render / vendor dashboards | a person with dashboard access |
| Supabase schema beyond what Flyway already applied | not done from this repo | never rewrite `flyway_schema_history` by hand |

This repository does **not** set Render or Supabase environment variables.
Do not put passwords, JWT secrets, database URLs with credentials, Cashfree
keys, Firebase JSON, or SMS tokens in git.

## Required GitHub CI on `main` (before flipping `DDL_AUTO`)

1. Job **`schema-migrate`** in `.github/workflows/ci.yml` is **green on `main`**.
   That job boots an empty Postgres with Flyway V2–current, then boots again
   with `DDL_AUTO=validate`.
2. Job **`build-and-test`** is green (`FLYWAY_ENABLED=false`, existing contract).
3. Flutter **`build-apk`** is green if you ship the Android artifacts.

There is **no V1** Flyway file. Do not add one. Production history already
has V2 onward.

## Manual Render step: `DDL_AUTO=validate`

**Not a code migration.** Hibernate in production must not alter tables on
deploy. After `schema-migrate` is green on `main` and a deploy of that commit
has booted successfully:

1. Render → GP-STORE web service → **Environment**.
2. Set `DDL_AUTO` to `validate` (create the variable if it is missing).
3. Leave `FLYWAY_ENABLED=true`. Do not change `DB_URL`, pool size, Tomcat
   threads, or JVM flags as part of this step.
4. Save and wait for the service to restart.
5. Confirm `/v1/actuator/health` is healthy.

### Rollback if the new deploy does not start

`validate` never writes to the database. A failed boot leaves customer data
as it was.

1. Render → Environment → set `DDL_AUTO` back to `update`, or **delete** the
   variable so the app uses its default.
2. Redeploy / restart.
3. Do **not** rewrite Flyway history and do **not** restore
   `backend/docs/production-schema-reference.sql` as a bootstrap script
   (it is a 2026-08-19 snapshot and is missing later columns).

## Environment variables (names only — values stay in the dashboard)

Required for a real shop:

- `DB_URL` — JDBC URL to **your** Supabase Postgres with `sslmode=require`.
  Use `jdbc:postgresql://db.<project-ref>.supabase.co:5432/postgres?sslmode=require`
  (direct) or the transaction pooler URL with `prepareThreshold=0` if you
  outgrow the direct connection ceiling.
- `DB_USERNAME`, `DB_PASSWORD`
- `FLYWAY_ENABLED=true`
- `JWT_SECRET` — long random string; never the repo default
- `APP_PRODUCTION=true`
- `REDIS_HOST`, `REDIS_PORT`, and `REDIS_PASSWORD` if the instance has one
- `CORS_ALLOWED_ORIGINS` — real frontend origin(s), comma-separated
- `STORE_LATITUDE`, `STORE_LONGITUDE`

Optional, fail-closed if unset: Cashfree, SMS/OTP, Firebase, Cloudinary.

**Do not raise** `DB_POOL_MAX_SIZE` (default 10), `TOMCAT_MAX_THREADS`
(default 40), or JVM memory to “fix” 502s. Those ceilings were set on
purpose. Latency is not solved by a bigger pool on the same Render plan.

## CORS (code, already in this repo when Phase 3 is merged)

Browser checkout sends `Idempotency-Key`. Allowed request headers are
exactly `Authorization`, `Content-Type`, and `Idempotency-Key`. Native
Android apps do not use CORS.

## What this checklist does not do

- Change production secrets.
- Enable Mapbox or PostGIS.
- Increase Hikari / Tomcat / JVM ceilings.
- Run 1,000+ VU load tests against the live Render URL. Use
  `load-tests/run-staged-capacity.sh` locally/staging, smallest stage first.
- Merge or rewrite Flyway V2–V22.
