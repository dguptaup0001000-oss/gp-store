#!/usr/bin/env bash
# Local and public health checks for GP-STORE on the VPS.
# Usage: API_HOST=api.gpstore.co.in ./check-health.sh
set -euo pipefail

API_HOST="${API_HOST:-api.gpstore.co.in}"

echo "== JVM via localhost (bypasses Nginx) =="
curl -fsS --max-time 5 "http://127.0.0.1:8081/v1/api/health"
echo

echo "== Readiness (may SELECT 1; do not use as a 1s uptime ping) =="
curl -fsS --max-time 8 "http://127.0.0.1:8081/v1/api/health/ready"
echo

echo "== HTTPS via Nginx =="
curl -fsS --max-time 8 "https://${API_HOST}/v1/api/health"
echo

echo "== Redis (localhost only) =="
redis-cli -h 127.0.0.1 ping
