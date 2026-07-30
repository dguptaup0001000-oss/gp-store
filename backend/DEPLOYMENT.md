# Deploying GP-Store to Railway (app) + Supabase (database)

Why Railway for the app: it auto-detects the `Dockerfile` already in this
repo, and its GitHub integration auto-redeploys on every push - no GitHub
Actions deploy scripting needed for this part.

Why Supabase for the database: this project no longer uses Railway
PostgreSQL. The database is Supabase PostgreSQL - Railway here only hosts
the Spring Boot app itself and connects out to Supabase over the internet.

## One-time setup

1. Go to https://railway.app and sign up (GitHub login is easiest).
2. **New Project → Deploy from GitHub repo** → pick this repository.
   Railway will detect the `Dockerfile` and build from it automatically.
3. **Database: Supabase, not Railway Postgres.** Railway PostgreSQL is no
   longer used for this project. Instead, create (or use your existing)
   project at https://supabase.com, then go to **Project Settings →
   Database → Connection string → JDBC** to get your host, port, database
   name, and username (`postgres` by default).
4. Set these environment variables on your backend service (Railway →
   your service → **Variables** tab), using your real Supabase values:
   ```
   DB_URL=jdbc:postgresql://db.<your-project-ref>.supabase.co:5432/postgres?sslmode=require
   DB_USERNAME=postgres
   DB_PASSWORD=<your Supabase database password>
   JWT_SECRET=<generate a real random 64+ character string - never reuse the dev default>
   CORS_ALLOWED_ORIGINS=<your real frontend domain, once you have one>
   STORE_LATITUDE=<your actual shop's latitude>
   STORE_LONGITUDE=<your actual shop's longitude>
   ```
   `?sslmode=require` is required - Supabase's direct connection expects an
   encrypted connection, and without it the connection will be refused or
   silently fall back in a way that isn't guaranteed. Never commit the real
   `DB_PASSWORD` to git; set it only here, as a Railway service variable.

5. Railway assigns a public URL automatically (Settings → Networking →
   **Generate Domain**). That's your live API base URL.

6. **One-time step after your first successful deploy** (same reason as
   local Docker - the products table doesn't exist until the app has started
   once): open Supabase's **SQL Editor** (in your Supabase project dashboard)
   or connect via `psql` using the same connection details from step 3, and run:
   ```sql
   CREATE EXTENSION IF NOT EXISTS pg_trgm;
   CREATE INDEX IF NOT EXISTS idx_products_name_trgm ON products USING GIN (name gin_trgm_ops);
   CREATE INDEX IF NOT EXISTS idx_products_brand_trgm ON products USING GIN (brand gin_trgm_ops);
   ```
   Supabase allows `CREATE EXTENSION pg_trgm` without extra permissions, same
   as it did on Railway.

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
