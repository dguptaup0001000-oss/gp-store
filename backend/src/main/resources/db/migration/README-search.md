# Search setup (now automatic - see V5__add_search_trigram_indexes.sql)

The search endpoint uses PostgreSQL's `pg_trgm` extension for fast,
typo-tolerant, ranked search (the same category of technique real search
features use - not a full LIKE '%x%' table scan).

This used to require a manual one-time SQL step, which is exactly the kind
of thing that's easy to skip or forget - and turned out to be the real cause
of a multi-second search latency found via load testing (search still
returns correct results without the indexes below, it just does a full
table scan to compute them, twice per request). `V5__add_search_trigram_indexes.sql`
now runs this automatically as a real Flyway migration - nothing manual left
to do. The SQL is kept below only as a reference for what that migration does.

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
- Most managed Postgres providers (RDS, Supabase, Neon) allow
  `CREATE EXTENSION pg_trgm` without extra permissions. Self-hosted Postgres
  may need a superuser to run it once.
- If you skip this step, the search endpoint will fail with a Postgres error
  the first time it's called (undefined function `similarity`/undefined
  operator `%`) - it's a hard requirement, not an optimization.
- This is unrelated to the ddl-auto/Flyway migration discussion from before -
  extensions and indexes aren't something `ddl-auto=update` manages.
