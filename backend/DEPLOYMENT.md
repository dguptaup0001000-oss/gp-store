# Deploying GP-Store to Render (app) + Supabase (database)

This project no longer uses Railway for anything - not app hosting, not the
database. Render hosts the Spring Boot app; Supabase remains the database,
unchanged from before.

Why this required almost no code changes: the app was already
platform-agnostic before this migration - it reads `PORT` from the
environment (`server.port=${PORT:8081}`) rather than hardcoding it, binds to
all network interfaces by default, and builds from a standard multi-stage
Dockerfile. Render supports all three the same way Railway did. The only
things that actually change are *where* you click to set environment
variables and *how* you get a public URL - not the application itself.

## One-time setup

1. Go to https://render.com and sign up (GitHub login is easiest).
2. **New → Web Service** → connect your GitHub account → pick this
   repository.
3. **Root Directory**: if this backend lives in a subfolder of your repo
   (e.g. `gp-store/backend`, alongside a separate `frontend` folder) rather
   than at the repo root, set that path here. Render needs to know where the
   `Dockerfile` actually is - it does not auto-discover it recursively the
   way some platforms do.
4. **Runtime**: select **Docker**. Render will detect and build the
   `Dockerfile` in that directory automatically - no build command needed.
5. **Instance type**: Render's free tier works for initial testing, but see
   "Free tier behavior" below before you rely on it for anything real.
6. **Provision Redis** - required now (`spring.cache.type=redis` in
   `application.properties`, see its doc comment): the app's caching and its
   login/checkout rate limiter both need a real Redis instance to connect
   to, and won't start correctly without one. Either:
   - Render → **New → Key Value** (Render's own managed Redis-compatible
     store) in the same project, or
   - a free-tier external provider (e.g. Upstash) if you'd rather not add
     another paid Render resource.

   Either way, note the host/port (and password, if one is set) - you'll
   need them in the next step.
7. Set these environment variables (Render → your service → **Environment**
   tab), using your real Supabase and Redis values:
   ```
   DB_URL=jdbc:postgresql://db.ckkksweijbdccvvmamid.supabase.co:5432/postgres?sslmode=require
   DB_USERNAME=postgres
   DB_PASSWORD=<your Supabase database password>
   FLYWAY_ENABLED=true
   JWT_SECRET=<a real random 64+ character string - never reuse the local dev default>
   CORS_ALLOWED_ORIGINS=<your real frontend domain, once you have one>
   STORE_LATITUDE=<your actual shop's latitude>
   STORE_LONGITUDE=<your actual shop's longitude>
   REDIS_HOST=<host from step 6>
   REDIS_PORT=<port from step 6, usually 6379>
   REDIS_PASSWORD=<password from step 6, if any - leave unset if none>
   ```
   Optional - only if admins should be able to upload product/variant
   photos directly instead of pasting an already-hosted image URL: create a
   free Cloudinary account (https://cloudinary.com), then set
   ```
   CLOUDINARY_CLOUD_NAME=<from Cloudinary Dashboard -> Product Environment Credentials>
   CLOUDINARY_API_KEY=<same page>
   CLOUDINARY_API_SECRET=<same page - keep this one secret, same as JWT_SECRET/DB_PASSWORD>
   ```
   Left unset, the admin app's image field still works fine as a
   manually-pasted URL - the upload button just shows a clear "not
   configured" error instead of crashing anything.

   `DDL_AUTO=validate` and `APP_PRODUCTION=true` are both required in
   production now:

   ```
   DDL_AUTO=validate
   APP_PRODUCTION=true
   ```

   `DDL_AUTO=validate` stops Hibernate from silently altering the production
   schema on deploy; every schema change goes through a Flyway migration
   instead. An earlier version of this doc said not to set it, on the
   assumption that a Flyway baseline file was needed first - that was wrong.
   Flyway has been enabled in production all along (V2-V6 applied), and
   `validate` only compares the live schema to the JPA entities; it has no
   dependency on a baseline file. See
   `src/main/resources/db/migration/README.md`.

   `APP_PRODUCTION=true` makes the app refuse to start if `JWT_SECRET` is
   missing, too short for HS256, or still the development default committed
   in `application.properties`. That default is public in this repository, so
   an instance running on it lets anyone who reads the source forge a token
   for any customer and any role, including ADMIN, with nothing in the logs
   looking wrong. Failing at boot is recoverable in minutes; running on it is
   an authentication bypass that could go unnoticed indefinitely.

   This repository does **not** set Render environment variables. Setting
   `DDL_AUTO=validate` remains a dashboard change. Do it only after the
   `schema-migrate` CI job (below) is green on `main`. Flipping that variable
   does not rewrite customer data.

## Empty-database / Flyway CI

Root `.github/workflows/ci.yml` has two test jobs:

- **`build-and-test`** — existing contract: `FLYWAY_ENABLED=false`,
  `DDL_AUTO=update`, plus the three objects Flyway would otherwise create
  (`pg_trgm`, `order_number_seq`, `shedlock`).
- **`schema-migrate`** — clean Postgres, `FLYWAY_ENABLED=true`: first boot
  with `DDL_AUTO=update` (Hibernate then Flyway V2 through current), second
  boot with `DDL_AUTO=validate`. Publishing the container image waits for
  **both** jobs.

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
3. Push. Flyway runs the new migration before Hibernate validates, so the
   order is always migrate-then-check.
4. Watch the deploy log. A failed `validate` means the app does not start and
   the previous version keeps serving - `validate` never writes, so a failure
   here is a startup check, not a data risk.

   `?sslmode=require` in `DB_URL` is required - Supabase's direct connection
   expects an encrypted connection. Never commit the real `DB_PASSWORD` to
   git; set it only here, as a Render environment variable.

8. Render assigns a public URL automatically once the first deploy
   succeeds - something like `https://gp-store-backend.onrender.com`. Find
   it at the top of your service's dashboard page.

9. **One-time step after your first successful deploy** (the `products`
   table doesn't exist until the app has started at least once and Hibernate
   has created the schema): open Supabase's **SQL Editor** in your Supabase
   project dashboard, or connect via `psql` using the connection details
   from step 7, and run:
   ```sql
   CREATE EXTENSION IF NOT EXISTS pg_trgm;
   CREATE INDEX IF NOT EXISTS idx_products_name_trgm ON products USING GIN (name gin_trgm_ops);
   CREATE INDEX IF NOT EXISTS idx_products_brand_trgm ON products USING GIN (brand gin_trgm_ops);
   ```

## Health checks

Render can poll a health check path and hold back traffic until it returns
healthy. Set this in Render → your service → **Settings → Health Check
Path**:
```
/v1/actuator/health
```
(`/v1` because of `server.servlet.context-path=/v1` - check
`application.properties` if that ever changes.) This endpoint is already
public in `SecurityConfig` - no code change needed.

## Free tier behavior (this is genuinely different from Railway)

Render's free web services **spin down after ~15 minutes of no traffic** and
take roughly 30-60 seconds to cold-start on the next incoming request.
Railway's free trial credit didn't do this - it stayed warm until the credit
ran out. Practically: if you test the API, wait 20 minutes, then test again,
the first request will hang for up to a minute before responding. That's
expected, not a bug. If this matters for your launch (customers hitting a
slow first request), you'll want a paid instance type, which stays warm.

## After that

Every push to your main branch auto-redeploys - Render rebuilds the
Dockerfile and restarts the service, same auto-deploy model Railway used.
`.github/workflows/ci.yml` still only catches bugs via build/test *before*
Render ever sees the code - no separate deploy job needed there.

## What this does NOT cover

- A custom domain (e.g. `api.yourstore.com`) - Render supports this under
  **Settings → Custom Domains**, but it's a DNS decision only you can make
  once you own a domain.
- Scaling to multiple instances - caching, rate limiting, and scheduled-job
  locking (ShedLock, see `config.SchedulerLockConfig`) are all
  correctness-safe across multiple instances now, but actually running more
  than one (load balancer setup, health-check-aware rollout, etc.) is still
  outside this doc's scope.
