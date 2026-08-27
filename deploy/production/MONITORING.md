# Production monitoring (lightweight)

GP-STORE does not require a paid APM. Operators use Compose healthchecks,
the admin ops API, and `deploy/production/check-health.sh`.

## Public (no secrets)

| Check | How |
|---|---|
| Backend up | `https://api.gpstore.co.in/v1/api/health` |
| Ready (Postgres + Redis) | `https://api.gpstore.co.in/v1/api/health/ready` |
| Runtime snapshot | `https://api.gpstore.co.in/v1/api/health/runtime` (heap, threads, Hikari; no secrets) |
| Actuator | `https://api.gpstore.co.in/v1/actuator/health` (`show-details=when-authorized`) |

Uptime monitors should hit **`/v1/api/health`** (cheap) or **`/v1/actuator/health`**.
Do not hammer `/ready` more than once every few seconds; it may `SELECT 1`.
`/v1/api/health/runtime` is safe to scrape every few seconds during a load test.
It cannot see Hostinger host CPU/RAM — those remain hPanel / `docker stats` over SSH.

## Admin-only (JWT, ADMIN role)

`GET /v1/api/admin/ops/status` returns:

- **backups** — last successful dump, age vs 26h window
- **redis** — PING, no credentials
- **disk** — backup volume free space (no filesystem paths)
- **tls** — certificate expiry for `api.gpstore.co.in`

`GET /v1/api/admin/ops/backups` lists recent sidecar runs.

These endpoints are not public. Do not expose them on a second unauthenticated port.

## On the VPS

```bash
cd /opt/gp-store
./deploy/production/check-health.sh
docker compose -f backend/docker-compose.yml ps
docker compose -f backend/docker-compose.yml logs --tail=80 backend
```

Compose already restarts unhealthy containers (`restart: unless-stopped`).
The backup sidecar healthcheck fails when `status.txt` is missing, the last
attempt is `FAILURE`, or the SUCCESS dump is older than 26 hours. Backend
`/actuator/health` stays independent of backups so a dump failure does not
take the shop off Traefik. GitHub **Backup alert** emails on a red run.

## What to watch

| Symptom | Signal |
|---|---|
| Backend down | public `/api/health` not 200; Compose `backend` unhealthy |
| Database down | `/api/health/ready` 503; actuator DOWN |
| Redis down | `/api/health/ready` 503; `ops/status` redis.healthy=false |
| Backup failure | `ops/status` backups.healthy=false; sidecar `backup.sh health` fails immediately on FAILURE; GitHub **Backup alert** workflow |
| Stale backup | SUCCESS dump older than 26h; sidecar unhealthy; Backup alert workflow red |
| Disk almost full | `ops/status` disk.healthy=false; `df` on `/backups` |
| Memory | `docker stats`; backend `mem_limit` 2560m |
| Backup failure | `ops/status` backups.healthy=false; sidecar logs |
| Deploy failure | GitHub Actions Deploy Production; VPS `/var/lib/gp-store/deployment-state` |

Prometheus scrape (`/v1/actuator/prometheus`) is **admin-only**. Do not
make it public. Gauges that exist without extra infrastructure:

| Metric | Meaning |
|---|---|
| `http.server.requests` (histogram) | HTTP count, error status, p50/p95/p99 |
| `hikaricp.*` | DB pool |
| `jvm.memory.*` | Heap |
| `checkout.place_order` | Place-order timer |
| `gpstore.backup.healthy` | 1 if last backup attempt is SUCCESS and fresh |
| `gpstore.backup.alert_code` | 0 HEALTHY / 1 MISSING / 2 FAILED / 3 STALE |
| `pool.shed.catalog` | Intentional catalog 503s when the pool is saturated |
| `outbox.*` | Outbox backlog |

There is **no** paid APM (Datadog/New Relic/Sentry) installed. Application
errors and order/payment webhook failures are INFO/ERROR logs on the backend
container (`docker compose logs backend`). Cashfree webhook failures log at
ERROR and return a retryable status to Cashfree.

Single-VPS loss and RTO/RPO: [`DISASTER_RECOVERY.md`](DISASTER_RECOVERY.md).
