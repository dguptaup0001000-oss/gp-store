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
| **Domain not resolving** | every public check fails at once while the VPS is healthy; see below |

### When the whole shop is unreachable but nothing on the box is wrong

This happened on 2026-09-26. The registry record for `gpstore.co.in` was
changed at 10:00 UTC to `ns1/ns2.verification-hold.suspended-domain.com` - a
registrar hold, not an expiry; the domain's paid term ran to 2027-06-28 - and
those nameservers answer `A 127.0.0.1` with a 30-second TTL for every name in
the zone. The backend was perfectly healthy throughout and the deploy that ran
during the incident succeeded.

It presents as two different-looking failures with one cause, so recognise
both:

* `curl: (6) Could not resolve host: api.gpstore.co.in` - a resolver that
  refuses to query nameservers whose addresses are private (Google's public
  resolver returns REFUSED with EDE 22 "At delegation gpstore.co.in").
* `curl: (7) Failed to connect ... after 10 ms` or a smoke test reporting
  `000` for everything instantly - a resolver that DOES return `127.0.0.1`, so
  the client dials its own loopback. Ten milliseconds is the tell: a real
  outage times out, it does not refuse instantly.

Confirm it in one request, from anywhere, without needing the VPS:

    curl -s 'https://dns.google/resolve?name=api.gpstore.co.in&type=A'
    curl -s 'https://rdap.org/domain/gpstore.co.in' | python3 -m json.tool

Look at `nameservers` and the `last changed` event in the RDAP output. If the
nameservers are a hold/parking service, no code change and no re-run will help
and **customers cannot reach the app either** - the fix is with the registrar
(currently OVI HOSTING PVT LTD / HostingRaja). `verify-public-release-sha.sh`
detects and names this case rather than reporting a flaky network.

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
