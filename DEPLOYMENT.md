# Deploying GP-Store to Railway

> **Current production host is Render + Supabase**, not Railway. Use
> `backend/DEPLOYMENT.md`. Schema/Flyway empty-database CI is the
> `schema-migrate` job in `.github/workflows/ci.yml` (see
> `backend/src/main/resources/db/migration/README.md`). This Railway page is
> left in place so old links do not 404; it is not the live runbook.

Why Railway: it auto-detects the `Dockerfile` already in this repo, gives you
managed Postgres with one click, and its GitHub integration auto-redeploys on
every push - no GitHub Actions deploy scripting needed for this part.

## One-time setup

1. Go to https://railway.app and sign up (GitHub login is easiest).
2. **New Project → Deploy from GitHub repo** → pick this repository.
   Railway will detect the `Dockerfile` and build from it automatically.
3. **Add a Postgres database**: in the same project, click **+ New → Database
   → PostgreSQL**. Railway provisions it and gives you connection details
   automatically as project variables.
4. Set these environment variables on your backend service (Railway →
   your service → **Variables** tab). Reference the Postgres plugin's
   variables directly instead of retyping them:
   ```
   DB_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
   DB_USERNAME=${{Postgres.PGUSER}}
   DB_PASSWORD=${{Postgres.PGPASSWORD}}
   JWT_SECRET=<generate a real random 64+ character string - never reuse the dev default>
   CORS_ALLOWED_ORIGINS=<your real frontend domain, once you have one>
   STORE_LATITUDE=<your actual shop's latitude>
   STORE_LONGITUDE=<your actual shop's longitude>
   ```
   (`${{Postgres.PGHOST}}` etc. is Railway's syntax for referencing another
   service's variables - it auto-fills once you type `${{` and pick Postgres.)

5. Railway assigns a public URL automatically (Settings → Networking →
   **Generate Domain**). That's your live API base URL.

6. **One-time step after your first successful deploy** (same reason as
   local Docker - the products table doesn't exist until the app has started
   once): open the Postgres service's **Data** tab or connect via `psql` and run:
   ```sql
   CREATE EXTENSION IF NOT EXISTS pg_trgm;
   CREATE INDEX IF NOT EXISTS idx_products_name_trgm ON products USING GIN (name gin_trgm_ops);
   CREATE INDEX IF NOT EXISTS idx_products_brand_trgm ON products USING GIN (brand gin_trgm_ops);
   ```

## After that

Every push to your main branch auto-redeploys - Railway rebuilds the
Dockerfile and restarts the service. No separate "deploy" job needed in
`.github/workflows/ci.yml`; that file's job is just to catch bugs via
build/test *before* Railway ever sees the code.

## Cost expectation

Railway is usage-based after the free trial credit runs out. For one kirana
store's traffic (not a real e-commerce giant's), this should be a small
monthly cost - track it in Railway's dashboard early on so there are no
surprises, and set a usage alert/limit in your account billing settings.

## What this does NOT cover

- A custom domain (e.g. `api.yourstore.com`) - Railway supports this, but
  it's a DNS decision only you can make once you own a domain.
- Scaling to multiple instances - not needed at your current single-store
  scale, and multiple instances would need the Redis-backed rate limiting /
  caching upgrade noted elsewhere before it'd work correctly.
