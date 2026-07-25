# One-time setup for instant search

The new search endpoint uses PostgreSQL's `pg_trgm` extension for fast,
typo-tolerant, ranked search (the same category of technique real search
features use - not a full LIKE '%x%' table scan). This needs to be enabled
**once** on your database - it is NOT something Hibernate/JPA can do for you
automatically, and I'm not running it for you since I don't have DB access.

Run this once against your Postgres database (via psql, a GUI tool, or your
hosting provider's SQL console):

```sql
-- Enables fuzzy/similarity matching (handles typos like "shampu" -> "shampoo")
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Speeds up trigram search on the columns customers actually search by.
-- Without these indexes, search still WORKS, it's just a full table scan -
-- fine at 200 products, painfully slow at 20,000.
CREATE INDEX IF NOT EXISTS idx_products_name_trgm ON products USING GIN (name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_products_brand_trgm ON products USING GIN (brand gin_trgm_ops);
```

Notes:
- Most managed Postgres providers (RDS, Supabase, Neon, Render, Railway) allow
  `CREATE EXTENSION pg_trgm` without extra permissions. Self-hosted Postgres
  may need a superuser to run it once.
- If you skip this step, the search endpoint will fail with a Postgres error
  the first time it's called (undefined function `similarity`/undefined
  operator `%`) - it's a hard requirement, not an optimization.
- This is unrelated to the ddl-auto/Flyway migration discussion from before -
  extensions and indexes aren't something `ddl-auto=update` manages.
