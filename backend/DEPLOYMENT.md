# Deploying GP-Store on Hostinger (Docker Compose)

Operator checklist: repo-root `PRODUCTION_CHECKLIST.md`.

**This backend is deployed on a Hostinger VPS with Docker Compose and
Traefik.** Exact commands: [`HOSTINGER_DEPLOYMENT.md`](HOSTINGER_DEPLOYMENT.md).

Render is not used.

The app is already platform-agnostic: `server.port=${PORT:8081}`, secrets
from the environment, Flyway for schema. Compose sets
`DB_URL=jdbc:postgresql://postgres:5432/${DB_NAME}` on the Docker network.
Postgres is not published to the host.

Redis is required (`spring.cache.type=redis`, rate limits). Compose runs it
privately with `requirepass`.

## Empty-database / Flyway CI

Root `.github/workflows/ci.yml` has two test jobs:

- **`build-and-test`** — `FLYWAY_ENABLED=false`, `DDL_AUTO=update`
- **`schema-migrate`** — clean Postgres, `FLYWAY_ENABLED=true`

There is no V1 migration; do not add one. Search trigram indexes are
`V5__add_search_trigram_indexes.sql` — no manual SQL after first boot.

## Health

```
GET /v1/api/health
GET /v1/actuator/health
```

GitHub Actions builds APKs and runs tests. It does not SSH to Hostinger.
