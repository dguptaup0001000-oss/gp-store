# Load testing GP-Store

Phase 2 of the scale roadmap: measure the real breaking point instead of guessing.

## What's here

- `seed-accounts.js` - plain Node script, run once, creates dedicated `loadtest_vu_*`
  accounts + addresses (slowly, respecting the 20/min auth rate limit). Never sends
  SMS. Not a k6 script.
- `snapshot-fixtures.js` - records known shop categories and variants that actually
  have inventory. k6 must not invent product IDs.
- `browse-cart-checkout.js` - mix test with unique VU identities, classified errors,
  endpoint metrics, 502 kill switch, and COD idempotency replay.
- `integrity-cart-order.js` - cart last-write-wins + place-order idempotency (not a flood).
- `generate-stage-report.py` / `monitor-instance.sh` - Section 26 report + CPU/RSS/pg/redis.
- `accounts.json` / `fixtures.json` - generated, gitignored (tokens and local IDs).
- `phone-loadtest.js` - a self-contained, no-k6 alternative for running the same
  browse/cart/checkout scenarios from a phone (Termux) instead of a computer. See its
  header - k6 doesn't have a real Termux/Android build, so this trades k6's proper
  metrics for something `pkg install nodejs` can actually run.

## Quickstart (computer, using k6)

```bash
# 1. Install k6: https://k6.io/docs/get-started/installation/

# 2. Seed dedicated VU accounts (takes ~9 min for 160 — deliberately slow)
cd load-tests
BASE_URL=http://localhost:8081/v1 COUNT=160 CART_COUNT=150 node seed-accounts.js
node snapshot-fixtures.js

# 3. Correctness probes, then the mix (localhost only — not the live shop)
node integrity-cart-order.js
BASE_URL=http://localhost:8081/v1 BROWSE_VUS=50 CART_VUS=15 k6 run browse-cart-checkout.js
```

Read the actual numbers from k6's summary at the end: `http_req_duration` (p50/p95/p99
latency per endpoint, broken out by the `name` tag - k6 prints all three percentiles
by default, not just the one named in `thresholds`), `http_req_failed` (error rate),
`iterations`/`http_reqs` (real throughput - requests/second, NOT the same number as
concurrent VUs, see the distinction below), and `checks` (pass rate per assertion).
The custom `orders_placed`/`orders_rate_limited`/`orders_rejected_client_error`
counters report actual checkout outcomes - `orders_placed` is what a follow-up
duplicate-order/duplicate-payment check against the database should use as its
baseline (this script can't query Postgres directly to verify that itself). Cross-
reference the whole run against Render's own metrics and Supabase's connection-count
graph for the same time window - that combination tells you what actually saturated
first (app CPU, DB connections, Redis, etc.), not just that something did.

**Concurrent users vs. requests per second - these are not the same number.**
`BROWSE_VUS=1000` means 1,000 virtual users are open at once, each pausing 1.5–4s
between actions (`thinkTime()`) - the actual request rate that produces is roughly
`VUs / average_think_time`, not 1,000 requests/second. k6's summary reports the real
achieved rate (`http_reqs` / test duration); read that number, don't infer it from the
VU count.

A previous ~5,005-VU / ~948k-request run transferred ~201 GB because the script
had almost no think time on error paths, searched 40% of the time, and treated
every VU as a request cannon. That is a **LOAD-TEST ISSUE**, not proof the API
returns 200 KB per call. `response_bytes` / `payload_bytes` on the rewritten
script is the number to read.

### Mix test (browse + cart + paced checkout)

Existing k6 thresholds in `browse-cart-checkout.js` (not relaxed by this program):

| Endpoint | Gate |
|---|---|
| categories | p95 < 1.0s |
| browse_category | p95 < 1.5s |
| search | p95 < 2.0s |
| product_detail | p95 < 2.0s |
| view_cart | p95 < 1.5s |
| add_to_cart | p95 < 2.0s |
| checkout_preview | p95 < 3.0s |
| place_order | p95 < 4.0s, p99 < 8.0s |
| order_status | p95 < 1.5s |
| 502 / unexpected 503 / network errors | count == 0 |

Recommended production SLO (not used as a silent substitute for the gates above): catalog p95 < 2s, p99 < 4s.

Localhost mix stages (do **not** point this at the live shop):

```bash
cd load-tests
chmod +x run-controlled-load.sh
BASE_URL=http://localhost:8081/v1 STAGES="750" ./run-controlled-load.sh
```

Default profile: 2m warmup @ 20 VU, 5m ramp, 10m hold, 2m ramp-down. Machine-readable JSON is written to `load-tests/results/stage-{vus}.json`. The text gate report is `stage-{vus}.report.txt` (`PASS` / `FAIL` / `INFRASTRUCTURE_LIMIT`). 429s are `RATE_LIMIT`. Catalog `503 + X-GP-Shed` is `EXPECTED_PROTECTIVE_503`, not a software defect.

Do **not** proceed to 1,000 VUs until the 750 gate is `PASS`. An `INFRASTRUCTURE_LIMIT` stop (dial timeouts with zero 5xx and idle pool) is not a reason to raise Hikari or Tomcat in git.

### Staged execution (required order)

Do **not** start at 1,000 or 5,000 VUs against one Render instance.
A previous production run at ~5,005 VUs produced **95% HTTP failures and
~325k 502s**. That is overload of a 40-thread / 10-connection container,
not a code rating, and it is **not** a reason to raise `DB_POOL_MAX_SIZE`.

Pass/fail for each stage (see `staged-capacity.js`): p95 < 2s,
p99 < 4s, **zero** 502, unexpected 503 (liveness), and network errors.
Catalog GET 503 from pool shedding is counted as `status_503_shed` and
does **not** fail the stage. Stop at the first failure; that VU count is
the measured ceiling for that target.

| Stage | VUs | How |
|---|---|---|
| A–D | 10, 25, 50, 100 | `BASE_URL=http://localhost:8081/v1 ./run-staged-capacity.sh` |
| E–G | 250, 500, 1,000 | `STAGES="250" HOLD_TIME=1m ./run-staged-capacity.sh` on staging only |

Browse-only probe (no checkout, no seeded accounts):

```bash
cd load-tests
chmod +x run-staged-capacity.sh
BASE_URL=http://localhost:8081/v1 HOLD_TIME=20s ./run-staged-capacity.sh
```

Record Hikari (`total/active/idle/waiting`), `pg_stat_activity`, CPU and
RSS in the same window. k6 cannot see the pool.

There is **no** 5k/10k/25k/50k command in `browse-cart-checkout.js`. Do not
add one. A 5,000-VU run against one small Render instance is overload
theatre, not a customer scenario.

Realistic customer mix (browse + cart + paced checkout):

```bash
BASE_URL=http://localhost:8081/v1 BROWSE_VUS=50 CART_VUS=15 CHECKOUT_RATE=4 \
  k6 run browse-cart-checkout.js
```

## Quickstart (phone, using Termux - no k6)

```bash
# In Termux (install from F-Droid, not the abandoned Play Store version):
pkg update -y && pkg install -y nodejs
curl -o phone-loadtest.js https://raw.githubusercontent.com/dguptaup0001000-oss/gp-store/main/load-tests/phone-loadtest.js
BASE_URL=https://your-backend.onrender.com/v1 node phone-loadtest.js
```

One command: it seeds its own small account pool, runs browse/cart/checkout load for 2
minutes, and prints a latency/error summary at the end. Read `phone-loadtest.js`'s
header first - a phone's own CPU and mobile/WiFi network are real ceilings too, so this
is a smoke test and a first latency/error reading, not a capacity verdict the way a
computer running real k6 would be.

## Why this can't validate "50,000 concurrent users" yet, and what would

Being straight about this rather than running something that looks like a 50k test
without actually being one:

**1. A load generator running from one machine has its own ceiling.** k6 on a single
box can realistically drive a few thousand concurrent virtual users before the
generator itself (not GP-Store) becomes the bottleneck. Actually producing 50k
concurrent load needs either k6 Cloud (distributed, paid) or several self-hosted k6
instances behind a coordinator - a generator that can't produce the load can't measure
whether the target can handle it.

**2. The login/register/checkout rate limiter caps this specific test, on purpose.**
`RateLimitFilter` limits login/register/OTP/refresh to 20 requests/60s **per
source IP**, checkout to 20/min **per customer**, and search to 60/min.
That's correct, deliberate anti-abuse behavior and shouldn't be turned off
for a test. Checkout in this script is a `constant-arrival-rate` of 4
orders/minute by default (`CHECKOUT_RATE`), which is what a small shop
actually sees - not a VU-shaped checkout flood.

**3. Testing at 50k scale against today's infrastructure would mostly measure
today's infrastructure, not the app's actual ceiling.** Backend is a single Render
instance; the database pool (even after the Phase 1 pooler switch) is sized for
today's traffic, not 50k concurrent. Running a 50k-VU test against this setup
right now would just confirm it falls over well before 50k - which is useful
information, but it's the Phase 1 gap restated, not new information. A test that's
meant to validate a 50k target needs to run against infrastructure that was already
sized with 50k in mind (Phase 3 of the roadmap): more Render instances, a Supabase
plan with a connection budget to match, autoscaling turned on.

**The practical path:** run this script now, at the scale in the Quickstart above,
against production. It's genuinely safe (tens of VUs, pre-seeded test accounts,
respects every rate limit already in place) and it answers a real question: where does
*today's* infrastructure first show stress - rising p95 latency, DB connection
saturation, Redis latency - and at roughly what request rate does that happen. That
number becomes the baseline the Phase 3 infrastructure changes are sized against, and
once those are in place, re-running this same script at higher `BROWSE_VUS`/`CART_VUS`
(or via a distributed generator) is what actually closes in on validating 50k.


## Running high VU counts on GitHub Actions (distributed)

`.github/workflows/load-test.yml` runs everything on ONE runner. That is the
right tool up to roughly 1,000-3,000 VUs for this script; past that the
runner's own 2-4 vCPUs become the limit and the numbers describe the
generator rather than the backend.

`.github/workflows/load-test-distributed.yml` splits the load across several
runners instead. Inputs are TOTALS - it divides them by `shards` itself and
prints the arithmetic in the run summary.

### What one runner can actually generate

A rough working figure for this script, which does JSON parsing and think
time per iteration: **~1,000-3,000 VUs per runner**. Beyond that k6 starts
missing its own scheduling targets, and the first symptom is latency that
looks like backend slowness but is really generator queueing. k6 reports
`dropped_iterations` when this happens - if that number is non-zero, the run
did not deliver the load you asked for and the results should not be read as
backend measurements.

So: 20,000 VUs needs about 8-20 shards. GitHub's concurrency limit (20 jobs
on the free plan) is the ceiling on shard count.

### Synchronized start

Shards are scheduled independently and can start tens of seconds apart. The
workflow makes every shard wait until a common wall-clock timestamp before
starting k6, so the ramps line up. Without that, the shards peak at
different moments and the combined peak is lower than requested - which
under-reports what the backend withstood.

### Reading a sharded result

The aggregate job sums requests and error counts and reports p95 as a RANGE
across shards, deliberately not an average. Averaging percentiles across
generators is not a meaningful operation, and the worst shard is the number
that matters.

A sharded run is NOT equivalent to the same VU count from a single address:
per-IP limits (auth, and anything else keyed on IP) scale with shard count,
because each runner has its own IP. Authenticated *mutation* limits are keyed
per customer, so those do NOT scale with shards - they scale with how many
accounts were seeded.

### Before pointing large numbers at anything

Confirm you are allowed to saturate the target. Against a small instance, a
very large run produces "everything timed out", which measures the instance,
not the code - and on a shared platform it can look like abuse.
