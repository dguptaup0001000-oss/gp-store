#!/usr/bin/env bash
# Stage a marketplace-sized load up a ladder of concurrency, then hold at the
# top, recording what the SERVER was doing at every step.
#
# This is run-staged-capacity.sh's ladder applied to marketplace-traffic.js,
# with two things added that only matter at these sizes: a resource sample per
# stage (the JVM and Postgres are invisible to k6), and a soak at the top,
# because problems that need time - a leak, a pool that never recovers, a GC
# that falls behind - do not appear in a sixty-second window.
#
#   BASE_URL=http://127.0.0.1:8081/v1 APP_PID=1234 OUT_DIR=/tmp/run \
#     STAGES="100 250 500 1000 2000 3000 4000" SOAK_VUS=4000 SOAK_TIME=5m \
#     ./run-marketplace-capacity.sh
#
# ESCALATION STOPS AT THE FIRST UNHEALTHY STAGE, unless CONTINUE_ON_FAIL=1.
# Set that only when the point of the run is to MEASURE the shape of the
# degradation - what a broken rung costs at twice and four times the load -
# rather than to find the ceiling. The first broken rung is still reported as
# the ceiling either way; continuing does not make it a passing run, and the
# script still exits non-zero.
# The ladder exists to find a
# ceiling, and climbing past a broken rung measures nothing except how much
# worse it gets. A stage is unhealthy on the gates in marketplace-traffic.js:
# 502, unexpected 503, 500, unexpected 4xx, network error, or p95/p99 over
# budget. Deliberate refusals - 429 and shed 503 - are counted and reported
# but do not stop the ladder, because refusing work under pressure is the
# application behaving correctly.
set -uo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8081/v1}"
STAGES="${STAGES:-100 250 500 1000 2000 3000 4000}"
HOLD_TIME="${HOLD_TIME:-60s}"
RAMP_TIME="${RAMP_TIME:-20s}"
SOAK_VUS="${SOAK_VUS:-0}"
SOAK_TIME="${SOAK_TIME:-5m}"
SOAK_RAMP="${SOAK_RAMP:-60s}"
SHOPPERS="${SHOPPERS:-40}"
APP_PID="${APP_PID:-}"
DB="${DB:-gpstore_loadtest}"
OUT_DIR="${OUT_DIR:-./capacity-run}"
HERE="$(cd "$(dirname "$0")" && pwd)"

command -v k6 >/dev/null 2>&1 || { echo "k6 is not installed." >&2; exit 1; }
mkdir -p "$OUT_DIR"

# base compatibility mode drops the core-js bundle from every VU. At four
# thousand VUs that is the difference between fitting in memory and not.
K6_FLAGS=(--no-color --compatibility-mode=base)

run_stage() {
  local label="$1" vus="$2" hold="$3" ramp="$4"
  local csv="$OUT_DIR/resources-${label}.csv"
  local json="$OUT_DIR/summary-${label}.json"
  local log="$OUT_DIR/k6-${label}.log"

  echo "======== STAGE ${label}: ${vus} VUs, ramp ${ramp}, hold ${hold} ========"
  local sampler=""
  if [ -n "$APP_PID" ]; then
    python3 "$HERE/sample-resources.py" --pid "$APP_PID" --db "$DB" \
      --out "$csv" --interval 2 &
    sampler=$!
  fi

  BASE_URL="$BASE_URL" VUS="$vus" HOLD_TIME="$hold" RAMP_TIME="$ramp" \
    SHOPPERS="$SHOPPERS" STAGE="$label" \
    k6 run "${K6_FLAGS[@]}" --summary-trend-stats="avg,min,med,p(95),p(99),max" --summary-export "$json" \
      "$HERE/marketplace-traffic.js" 2>&1 | tee "$log"
  local rc=${PIPESTATUS[0]}

  [ -n "$sampler" ] && { kill "$sampler" 2>/dev/null; wait "$sampler" 2>/dev/null; }

  python3 "$HERE/summarise-stage.py" --label "$label" --summary "$json" \
    --resources "$csv" --k6-exit "$rc" >> "$OUT_DIR/stages.txt"
  tail -20 "$OUT_DIR/stages.txt"
  return $rc
}

CONTINUE_ON_FAIL="${CONTINUE_ON_FAIL:-0}"
failed_at=""
for vus in $STAGES; do
  if ! run_stage "$vus" "$vus" "$HOLD_TIME" "$RAMP_TIME"; then
    [ -z "$failed_at" ] && failed_at="$vus"
    [ "$CONTINUE_ON_FAIL" = "1" ] || break
    echo "(CONTINUE_ON_FAIL: climbing past a broken rung to measure it, not to pass it)"
  fi
  sleep 15   # let the pool and the page cache settle between rungs
done

if [ -n "$failed_at" ] && [ "$CONTINUE_ON_FAIL" != "1" ]; then
  echo
  echo "STOPPED: the first stage that broke a gate was ${failed_at} VUs."
  echo "That is the measured ceiling for THIS target on THIS hardware under"
  echo "these gates. It is not a statement about production capacity, which"
  echo "runs different hardware, a different instance count and a proxy."
  echo "Do not raise the pool size or the thread count because of this line."
  exit 1
fi

if [ -n "$failed_at" ]; then
  echo
  echo "CEILING: the first stage that broke a gate was ${failed_at} VUs."
  echo "Stages above it were measured, not passed."
fi

if [ "$SOAK_VUS" -gt 0 ]; then
  run_stage "soak-${SOAK_VUS}" "$SOAK_VUS" "$SOAK_TIME" "$SOAK_RAMP" || {
    echo "SOAK BROKE A GATE - the ladder held at this level but the hold did not."
    exit 1
  }
fi

echo
if [ -n "$failed_at" ]; then
  echo "Run complete. It did NOT pass: ${failed_at} VUs broke a gate."
  exit 1
fi
echo "All requested stages passed: $STAGES"
echo "This measures this machine, with client and server sharing its cores."
echo "It is not a claim about how many real customers production can serve."
