# Production monitoring (lightweight)

GP-STORE does not require a paid APM. Operators use Compose healthchecks,
the admin ops API, and `deploy/production/check-health.sh`.

## Public (no secrets)

| Check | How |
|---|---|
| Backend up | `https://api.gpstore.co.in/v1/api/health` |
| Ready (Postgres + Redis) | `https://api.gpstore.co.in/v1/api/health/ready` |
| Actuator | `https://api.gpstore.co.in/v1/actuator/health` (`show-details=when-authorized`) |

Uptime monitors should hit **`/v1/api/health`** (cheap) or **`/v1/actuator/health`**.
Do not hammer `/ready` more than once every few seconds; it may `SELECT 1`.

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
| Memory | `docker stats`; backend `mem_limit` 1536m |
| Backup failure | `ops/status` backups.healthy=false; sidecar logs |
| Deploy failure | GitHub Actions Deploy Production; VPS `/var/lib/gp-store/deployment-state` |

Prometheus scrape (`/v1/actuator/prometheus`) is **admin-only**. Do not
make it public.

MSG91, Firebase, and Cashfree remaining unset is an operator configuration
gap, not a monitoring false-green: those integrations fail closed at use time.
