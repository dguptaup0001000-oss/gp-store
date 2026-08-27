#!/usr/bin/env bash
# Run concurrent COD order stages against a TEST target. Default localhost.
# Do not set BASE_URL to production.
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8081/v1}"
HOLD_TIME="${HOLD_TIME:-20s}"
STAGES="${STAGES:-10 25 50 100}"

case "$BASE_URL" in
  *api.gpstore.co.in*|*gpstore.co.in*)
    echo "Refusing to place load-test orders against production." >&2
    exit 1
    ;;
esac

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6 is not installed." >&2
  exit 1
fi

if [ ! -f "$(dirname "$0")/accounts.json" ]; then
  echo "accounts.json is missing. Seed test accounts first (see load-tests/README.md)." >&2
  exit 1
fi

failed_at=""
for vus in $STAGES; do
  echo "======== CONCURRENT ORDERS ${vus} VUs ========"
  if ! BASE_URL="$BASE_URL" VUS="$vus" HOLD_TIME="$HOLD_TIME" \
      k6 run "$(dirname "$0")/concurrent-orders.js"; then
    failed_at="$vus"
    break
  fi
done

if [ -n "$failed_at" ]; then
  echo "STOPPED: first failing concurrent-order stage was ${failed_at} VUs."
  exit 1
fi

echo "All requested concurrent-order stages passed: $STAGES"
echo "Integrity (lost/duplicate/negative stock) is ConcurrentOrderLoadTest in CI."
