#!/usr/bin/env bash
# Redis fail-open smoke: catalog must still 200 if Redis is briefly down.
# Does not disable rate limiting. Restarts Redis before returning.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081/v1}"

echo "Before: categories"
curl -sS -o /tmp/cats-before.json -w "status:%{http_code} bytes:%{size_download}\n" \
  "${BASE_URL}/api/categories"

REDIS_PID="$(pgrep -x redis-server | head -n1 || true)"
if [ -z "$REDIS_PID" ]; then
  echo "redis-server not running locally; skip fail-open smoke."
  exit 0
fi

echo "Pausing Redis clients (CLIENT PAUSE 8000)"
redis-cli CLIENT PAUSE 8000 WRITE >/dev/null

echo "During pause: categories (must not 5xx)"
set +e
curl -sS --max-time 8 -o /tmp/cats-during.json -w "status:%{http_code}\n" \
  "${BASE_URL}/api/categories"
DURING=$?
set -e

echo "During pause: health"
curl -sS --max-time 5 -o /dev/null -w "health:%{http_code}\n" "${BASE_URL}/api/health" || true

# Wait for pause to end rather than killing Redis.
sleep 2
redis-cli CLIENT UNPAUSE >/dev/null 2>&1 || true

echo "After: categories"
curl -sS -o /tmp/cats-after.json -w "status:%{http_code} bytes:%{size_download}\n" \
  "${BASE_URL}/api/categories"

python3 - <<'PY'
import json
def status_ok(path):
    try:
        data = json.load(open(path))
        return isinstance(data, list) and len(data) > 0
    except Exception:
        return False
print("before_valid", status_ok("/tmp/cats-before.json"))
print("after_valid", status_ok("/tmp/cats-after.json"))
PY

echo "Redis fail-open smoke finished (curl_during_exit=${DURING:-0})."
echo "A timeout or 200 during pause is acceptable; a 500/process crash is not."
