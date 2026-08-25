#!/usr/bin/env bash
# Run browse-only capacity stages in order. Stop at the first failure.
# Default: 10 → 25 → 50 → 100 VUs against localhost. Higher stages are
# opt-in because they are how the previous production run produced 502s.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081/v1}"
HOLD_TIME="${HOLD_TIME:-20s}"
STAGES="${STAGES:-10 25 50 100}"

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6 is not installed. Install from https://k6.io/docs/get-started/installation/" >&2
  exit 1
fi

echo "Target: $BASE_URL"
echo "Stages: $STAGES (hold $HOLD_TIME each)"
echo "Pass: p95 < 2s, p99 < 4s, zero 502 / unexpected 503 / network errors"
echo "Catalog 503 (pool shed) is counted separately and does not fail a stage."
echo

failed_at=""
for vus in $STAGES; do
  echo "======== STAGE ${vus} VUs ========"
  if ! BASE_URL="$BASE_URL" VUS="$vus" HOLD_TIME="$HOLD_TIME" k6 run "$(dirname "$0")/staged-capacity.js"; then
    failed_at="$vus"
    break
  fi
done

if [ -n "$failed_at" ]; then
  echo
  echo "STOPPED: first failing stage was ${failed_at} concurrent VUs."
  echo "That is the measured ceiling for this target under these gates."
  echo "Do not raise Hikari pool size because this stage failed. Fix hold time,"
  echo "queries, or instance count with evidence first."
  exit 1
fi

echo
echo "All requested stages passed: $STAGES"
echo "This is not a claim about 1 lakh users. It is only these VU counts."
