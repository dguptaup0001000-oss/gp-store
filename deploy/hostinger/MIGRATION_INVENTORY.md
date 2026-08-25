# Render → Hostinger migration inventory

Audit of every Render / `onrender.com` production dependency found in this
repository, and what replaced it. Flutter widget `RenderBox` / `RenderFlex`
and English “render” (draw) are **not** hosting dependencies and were left
alone.

This file is the Phase 2 inventory. It is not a runbook; operators use
[`HOSTINGER_DEPLOYMENT.md`](../../HOSTINGER_DEPLOYMENT.md).

| File | Reference | Used for | Replacement | Affects |
|---|---|---|---|---|
| `frontend/lib/core/config/app_environment.dart` | production/staging default `https://gp-store.onrender.com/v1`; 45s Render cold-start timeout | Flutter production API host | Documented host `https://api.gpstore.co.in/v1`; 15s timeout; still overridable via `--dart-define=API_BASE_URL` | Flutter |
| `frontend/lib/core/api/api_client.dart` | comment: production still points at Render | documentation | comment only | Flutter |
| `frontend/web/account-deletion.html` | hardcoded `https://gp-store.onrender.com/v1/api/store-info` | support-contact fetch on GitHub Pages | same documented production host | Flutter/web |
| `.github/workflows/build-and-deploy.yml` | five fallbacks to `https://gp-store.onrender.com/v1` | APK/AAB/web CI when `vars.API_BASE_URL` unset | `https://api.gpstore.co.in/v1`; GitHub var still wins | Flutter CI |
| `frontend/.github/workflows/build-and-deploy.yml` | comments naming Render | zip-shipped duplicate workflow | comments | Flutter CI |
| `.github/workflows/load-test.yml` | default BASE_URL onrender | accidental production load | default `http://127.0.0.1:8081/v1` | load tests |
| `.github/workflows/load-test-distributed.yml` | default BASE_URL onrender; “Render CPU” copy | same | localhost default; “VPS CPU” copy | load tests |
| `backend/DEPLOYMENT.md` | entire Render how-to | production deploy | pointer to Hostinger runbook + Flyway notes | deployment |
| `DEPLOYMENT.md`, `PRODUCTION_CHECKLIST.md`, `SETUP.md` | Render as production | operator docs | Hostinger VPS + Supabase | docs |
| `frontend/SETUP.md`, `frontend/FIREBASE_SETUP.md`, `docs/MSG91_OTP_SETUP.md` | Render env tabs / onrender URLs | operator docs | VPS env file | docs |
| `tools/catalog/README.md` | curl examples onrender | catalog seed | `$API_BASE_URL` | docs |
| `backend/.env.example`, `.env.example` | “Render's environment settings” | secrets location | VPS `/opt/gpstore/env.production` | env |
| `backend/src/main/resources/application.properties` | comments: Render instance, SIGTERM, stdout, 0.5 CPU, health | comments only | VPS / systemd wording | backend comments |
| `backend/Dockerfile` | comments: Render OOM/502 | comments; JVM flags stay | platform-neutral OOM wording | backend comments |
| `backend/src/main/java/.../PoolSaturationFilter.java`, `TwoLevelCache.java`, `HealthController.java`, `OutboxEvent.java` | comments mentioning Render 502 / redeploy | comments only | proxy / process-restart wording | comments |
| tests `ResourceCeilingTest`, `CorsIdempotencyPreflightTest` | comments / display names | comments only | proxy / load-balancer wording | tests (names) |
| `backend/src/main/resources/db/migration/README.md` | `DDL_AUTO` in Render dashboard | operator step | VPS env file | docs |
| `load-tests/*` | onrender BASE_URL examples | load-test how-to | localhost examples | load tests |
| `render.yaml` / `render.yml` | — | not present | nothing to delete | — |

**Not changed (not Render hosting):** Flutter `RenderBox`/`RenderFlex`/`isRenderable`, Java “Rendered as a pin”, `pubspec.yaml` “Renders Google's model-viewer”.

**Database:** Canonical production Postgres is the Docker `postgres` service in `backend/docker-compose.yml` (volume `gpstore_pg_data`). Existing shop data may still live in Railway Postgres, Supabase, or a dump — **do not** drop or `flyway clean` it. Dump/restore is a manual operator step; see `backend/HOSTINGER_DEPLOYMENT.md`.

**DNS (2026-08-25):** `api.gpstore.co.in` still CNAME'd to `s1z20khv.up.railway.app` (Railway 404, cert `*.up.railway.app`). Replace that with an A record to the Hostinger VPS before Traefik can issue a real certificate.

**Not invented:** production API is `https://api.gpstore.co.in/v1`. Do not ship a Hostinger SDK in the APK.
