#!/usr/bin/env bash
# Controlled staged mix (browse + cart + paced checkout).
#
# Default target is localhost. Do not point this at the live shop.
# Pass/fail uses the existing k6 thresholds in browse-cart-checkout.js —
# those numbers are not relaxed here.
#
# Profile (override with env): warmup 2m, ramp 5m, hold 10m, ramp-down 2m.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8081/v1}"
STAGES="${STAGES:-750}"
WARMUP_TIME="${WARMUP_TIME:-2m}"
WARMUP_VUS="${WARMUP_VUS:-20}"
RAMP_TIME="${RAMP_TIME:-5m}"
HOLD_TIME="${HOLD_TIME:-10m}"
RAMP_DOWN_TIME="${RAMP_DOWN_TIME:-2m}"
CHECKOUT_RATE="${CHECKOUT_RATE:-4}"
# Checkout holds only after warmup+ramp so orders are not placed during cache fill.
CHECKOUT_START="${CHECKOUT_START:-7m}"
RESULTS_DIR="${RESULTS_DIR:-$ROOT/results}"

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6 is not installed." >&2
  exit 1
fi

mkdir -p "$RESULTS_DIR"

echo "Target: $BASE_URL"
echo "Stages: $STAGES"
echo "Profile: warmup $WARMUP_TIME @ ${WARMUP_VUS} VU, ramp $RAMP_TIME, hold $HOLD_TIME, down $RAMP_DOWN_TIME"
echo "Checkout: ${CHECKOUT_RATE}/min during hold (CHECKOUT_START=${CHECKOUT_START})"
echo "Existing gates (unchanged): catalog p95 1.0–1.5s, search/detail p95 2s,"
echo "  cart p95 1.5–2s, preview p95 3s, place-order p95 4s / p99 8s,"
echo "  zero 502, zero unexpected 503, zero network errors."
echo "Catalog pool-shed 503 is counted separately and does not fail a stage."
echo

failed_at=""
for vus in $STAGES; do
  browse=$(( vus * 80 / 100 ))
  cart=$(( vus - browse ))
  if [ "$cart" -lt 1 ] && [ "$vus" -ge 10 ]; then
    cart=1
    browse=$(( vus - cart ))
  fi
  summary="$RESULTS_DIR/stage-${vus}.json"
  echo "======== STAGE ${vus} VUs (browse=${browse} cart=${cart}) ========"
  if ! BASE_URL="$BASE_URL" \
      BROWSE_VUS="$browse" \
      CART_VUS="$cart" \
      CHECKOUT_RATE="$CHECKOUT_RATE" \
      WARMUP_TIME="$WARMUP_TIME" \
      WARMUP_VUS="$WARMUP_VUS" \
      RAMP_TIME="$RAMP_TIME" \
      HOLD_TIME="$HOLD_TIME" \
      RAMP_DOWN_TIME="$RAMP_DOWN_TIME" \
      CHECKOUT_START="$WARMUP_TIME" \
      SUMMARY_PATH="$summary" \
      k6 run "$ROOT/browse-cart-checkout.js"; then
    failed_at="$vus"
    echo "Stage ${vus} failed. JSON: $summary"
    break
  fi
  echo "Stage ${vus} passed. JSON: $summary"
done

if [ -n "$failed_at" ]; then
  echo
  echo "STOPPED: first failing stage was ${failed_at} concurrent VUs."
  echo "Do not raise DB_POOL_MAX_SIZE or TOMCAT_MAX_THREADS because this failed."
  exit 1
fi

echo
echo "All requested stages passed: $STAGES"
echo "This is not a claim about 10,000 production shoppers on one Render instance."
