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
6. Set these environment variables (Render → your service → **Environment**
   tab), using your real Supabase values:
   ```
   DB_URL=jdbc:postgresql://db.ckkksweijbdccvvmamid.supabase.co:5432/postgres?sslmode=require
   DB_USERNAME=postgres
   DB_PASSWORD=<your Supabase database password>
   FLYWAY_ENABLED=true
   JWT_SECRET=<a real random 64+ character string - never reuse the local dev default>
   CORS_ALLOWED_ORIGINS=<your real frontend domain, once you have one>
   STORE_LATITUDE=<your actual shop's latitude>
   STORE_LONGITUDE=<your actual shop's longitude>
   ```
   Do **not** set `DDL_AUTO=validate` yet - there's no Flyway baseline
   migration file in this project yet (see
   `src/main/resources/db/migration/README.md`), so `validate` mode would
   fail against an empty database. Leave `DDL_AUTO` unset (defaults to
   `update`) until you've generated and committed a real baseline migration.

   `?sslmode=require` in `DB_URL` is required - Supabase's direct connection
   expects an encrypted connection. Never commit the real `DB_PASSWORD` to
   git; set it only here, as a Render environment variable.

7. Render assigns a public URL automatically once the first deploy
   succeeds - something like `https://gp-store-backend.onrender.com`. Find
   it at the top of your service's dashboard page.

8. **One-time step after your first successful deploy** (the `products`
   table doesn't exist until the app has started at least once and Hibernate
   has created the schema): open Supabase's **SQL Editor** in your Supabase
   project dashboard, or connect via `psql` using the connection details
   from step 6, and run:
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
- Scaling to multiple instances - not needed at your current single-store
  scale, and multiple instances would need the Redis-backed rate limiting /
  caching upgrade noted elsewhere before it'd work correctly.
