# GP-STORE — ARM64 VPS deployment

Migration target:

```
Flutter app  ->  Caddy (TLS)  ->  Spring Boot (Docker)  ->  Supabase PostgreSQL
                                        |
                                     Redis (cache + rate limiter)
```

Supabase stays the database. Nothing about the schema, the API contract, the
business logic or the Flutter app changes.

---

## 1. Pick the region before the provider

**This matters more than CPU count.** The browse path issues 3 queries on a
cold cache. If the VPS sits 150 ms from Supabase, that is ~450 ms added to
every cold request — enough to be *slower* than the current 0.5-CPU Render
box, no matter how many cores the new one has.

Find your Supabase project's region first, then choose a VPS in the same
region or the nearest one. A cheap German VPS with a Singapore database is a
downgrade.

---

## 2. Sizing: how to choose the two numbers that matter

Only two values normally change when the box changes, and both are
environment variables — no rebuild.

### `TOMCAT_MAX_THREADS`

On Render this is **40**, and that low number was deliberate: 200 threads on
0.5 vCPU spend more time context-switching than working, which made tail
latency *worse*. That reasoning inverts once there are real cores.

A serviceable starting point for a mostly-I/O-bound API:

```
threads ≈ cores × 25
```

- 2 vCPU → ~50
- 4 vCPU → ~100

Do not jump to 200 because it is the framework default. More threads than the
database pool can feed just moves the queue from Tomcat to HikariCP.

### `DB_POOL_MAX_SIZE`

The real ceiling is **Supabase's connection budget**, not the VPS. Check your
plan's limit, then divide across everything connecting to it.

- Direct connection (port 5432) has a low hard ceiling — keep the pool small.
- **Transaction pooler / Supavisor (port 6543)** is what allows a larger
  pool. It requires `prepareThreshold=0` in the JDBC URL; without it the
  driver's server-side prepared statements break, because the pooler hands
  out a different physical connection per transaction.

20 is a reasonable start on the pooler for a single instance. Raise only
after watching `hikaricp_connections_pending` under load.

### Memory

`BACKEND_MEMORY_LIMIT` is the container's cgroup limit. The JVM reads it and
takes **70%** for the heap (`-XX:MaxRAMPercentage=70`, set in the Dockerfile).

Set it *below* the box's total RAM, leaving room for Redis, Caddy and the OS:

| Box RAM | `BACKEND_MEMORY_LIMIT` | Resulting heap |
|---|---|---|
| 2 GB | `1500m` | ~1.0 GB |
| 4 GB | `3g` | ~2.1 GB |
| 8 GB | `6g` | ~4.2 GB |

The remaining 30% inside the container is **not spare** — it is metaspace,
code cache, thread stacks (~1 MB each), GC structures and the direct byte
buffers the Postgres and Redis drivers use for network I/O. Giving the heap
90% is the standard way to get OOM-killed while the heap graph still looks
fine.

---

## 3. First-time server setup

```bash
# Docker
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker "$USER"   # log out and back in

# Firewall: only SSH and HTTPS reach the internet.
# The backend and Redis are never published to the host - see the compose
# file's `expose:` (container network only) rather than `ports:`.
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp     # required for the ACME HTTP challenge
sudo ufw allow 443/tcp
sudo ufw enable

# SSH hardening: keys only.
sudo sed -i 's/^#*PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
sudo sed -i 's/^#*PermitRootLogin.*/PermitRootLogin no/' /etc/ssh/sshd_config
sudo systemctl restart ssh
```

Point the DNS `A` (and `AAAA` if the VPS has IPv6) record for `APP_DOMAIN`
at the server **before** starting the stack. Caddy requests a certificate on
first boot; if DNS is not ready the challenge fails and repeated retries can
hit a Let's Encrypt rate limit.

---

## 4. Deploy

```bash
git clone https://github.com/dguptaup0001000-oss/gp-store.git
cd gp-store

cp deploy/.env.example deploy/.env
# Fill in every value. deploy/.env is gitignored - never commit it.
nano deploy/.env

docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d
docker compose -f deploy/docker-compose.prod.yml logs -f backend
```

### Verify

```bash
curl -fsS https://$APP_DOMAIN/v1/actuator/health        # {"status":"UP"}
curl -o /dev/null -s -w '%{http_code}\n' http://$APP_DOMAIN/v1/actuator/health   # 308 -> HTTPS
curl -s -o /dev/null -w '%{http_code}\n' https://$APP_DOMAIN/v1/actuator/prometheus  # 401/403, must NOT be 200
```

The last one matters: metrics must not be world-readable.

### Update to a new build

```bash
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env pull
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d
```

In-flight requests drain rather than dying: `server.shutdown=graceful` gives
them up to 25 s, and `stop_grace_period: 40s` stops Docker killing the
container before that finishes. Both numbers must stay consistent.

---

## 5. What to watch

All exported on `/v1/actuator/prometheus` (admin-only). The four that
distinguish the possible bottlenecks:

| Metric | Says |
|---|---|
| `jvm_memory_used_bytes{area="heap"}` vs committed | needs more RAM |
| `jvm_gc_pause_seconds` | GC thrashing — heap too small |
| `hikaricp_connections_pending` | pool too small, or slow queries |
| `tomcat_threads_busy` vs max | thread pool too small |

Rule of thumb: **pending DB connections above zero under load means the pool
is the bottleneck, not the CPU.** Raising `TOMCAT_MAX_THREADS` in that state
makes things worse, not better.

---

## 6. Re-measure after migrating — do not assume

The proven figure on the **old** infrastructure is:

> **750 concurrent browsing users, 86,203 requests, 0 errors, p95 ≈ 131 ms.**

That number belongs to a 0.5 vCPU / 512 MB Render instance. It does **not**
transfer to the new box, in either direction, until it is measured again.

Re-run the existing workflow — unchanged, so results stay comparable:

```
Actions -> "Load Test (distributed, manual)" -> Run workflow
```

Climb one rung at a time, and only continue while the previous rung is clean:

`750 -> 1,000 -> 1,500 -> 2,000 -> 3,000 -> 5,000`

Keep VUs per shard at ~250 (so `shards = total / 250`); that keeps each
generator inside its honest capacity and ensures the numbers describe the
backend rather than the runner.

Record for every rung: VUs, total requests, req/s, error rate and the outcome
census, p50/p95/p99, plus — from the VPS side — CPU peak, RAM peak, JVM heap,
GC pause, Hikari active/pending, and DB latency.

**Stop climbing** as soon as any of these appear: non-zero errors, p95 beyond
what a shopper would accept, CPU pinned at 100% through the whole hold,
memory above ~85%, or `hikaricp_connections_pending` consistently above zero.

State the capacity as the **last clean rung**, never the first failing one,
and never a number that was not actually run.
