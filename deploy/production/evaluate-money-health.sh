#!/usr/bin/env bash
# Is any of the shop's money stuck somewhere it should not be?
#
# WHY THIS EXISTS. The backend already counts refunds in flight and how old
# the oldest is, and exposes both as Prometheus gauges. A gauge nobody
# scrapes is a number nobody reads: the metric made a stuck refund
# observable, not noticed. This is the half that reaches a person, using the
# same mechanism the backup alert already uses - a scheduled workflow whose
# failure emails whoever watches the repository. No new service, no new bill,
# no pager account to keep alive.
#
# WHAT IT WILL NOT PRINT. Counts, ages and thresholds only. No customer name,
# no email, no phone, no order number, no per-order amount - a stuck refund
# is somebody's money and their identity has no business in a CI log that
# GitHub keeps for ninety days. The SQL that feeds this is written to select
# aggregates for exactly that reason.
#
# INPUT is a small key=value file, the same shape as the backup sidecar's
# status.txt, so the two alerts read alike:
#
#   refunds_in_flight=3
#   refunds_stuck=1
#   oldest_refund_hours=91
#   refunds_failed_unread=0
#   collected_at=2026-09-02T15:00:00Z
#
# Usage:  ./evaluate-money-health.sh /path/to/money.txt
#         STUCK_AFTER_HOURS=72 ./evaluate-money-health.sh money.txt
#         MONEY_HEALTH_SELFTEST=1 ./evaluate-money-health.sh
set -euo pipefail

# Matches refund.stuck-after-hours in the backend, which is the outside of a
# normal bank settlement. Kept in sync by the self-test's own documentation
# rather than by hope: if the backend default moves, this default moves.
STUCK_AFTER_HOURS="${STUCK_AFTER_HOURS:-72}"

read_value() {
  # tail -n 1 so a file that somehow got appended to twice reads as the most
  # recent collection rather than the oldest.
  grep "^$1=" "$2" 2>/dev/null | tail -n 1 | cut -d= -f2- | tr -d '\r' || true
}

is_number() {
  [[ "$1" =~ ^[0-9]+$ ]]
}

evaluate() {
  local file="$1"

  if [[ ! -s "$file" ]]; then
    echo "ALERT=MISSING reason=money status file missing or empty"
    return 1
  fi

  local in_flight stuck oldest_hours failed_unread
  in_flight="$(read_value refunds_in_flight "$file")"
  stuck="$(read_value refunds_stuck "$file")"
  oldest_hours="$(read_value oldest_refund_hours "$file")"
  failed_unread="$(read_value refunds_failed_unread "$file")"

  # A MALFORMED FILE IS AN ALERT, NOT A PASS. The collection step runs SQL on
  # the production database over SSH; if that broke, the file is empty or
  # garbage, and treating "cannot tell" as "healthy" is how a monitor goes
  # quietly blind. Every other alert in this repo fails closed and so does
  # this one.
  if ! is_number "${in_flight:-}" || ! is_number "${stuck:-}" \
      || ! is_number "${failed_unread:-}"; then
    echo "ALERT=UNREADABLE reason=money status file did not parse"
    return 1
  fi

  if [[ "$stuck" -gt 0 ]]; then
    echo "ALERT=REFUND_STUCK stuck=$stuck oldest_hours=${oldest_hours:-unknown} threshold_hours=$STUCK_AFTER_HOURS in_flight=$in_flight"
    return 1
  fi

  # A refund the PROVIDER REJECTED is worse than a slow one: it is not coming
  # back on its own, and the customer is still owed. The reconciliation
  # records the reason on the row; this is what makes somebody read it.
  if [[ "$failed_unread" -gt 0 ]]; then
    echo "ALERT=REFUND_REJECTED rejected=$failed_unread in_flight=$in_flight"
    return 1
  fi

  echo "ALERT=HEALTHY in_flight=$in_flight oldest_hours=${oldest_hours:-0} threshold_hours=$STUCK_AFTER_HOURS"
  return 0
}

# ---------------------------------------------------------------- self-test
#
# Same convention as harden-ssh.sh and disk-guard.sh: the script can prove its
# own logic with no VPS, no database and no secret, so CI checks the decisions
# rather than only the syntax. An alert whose thresholds were never exercised
# is an alert nobody should trust.
if [[ "${MONEY_HEALTH_SELFTEST:-0}" == "1" ]]; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  failures=0

  check() {
    local name="$1" expected_code="$2" expected_alert="$3" file="$4"
    local out code
    set +e
    out="$(evaluate "$file")"
    code=$?
    set -e
    if [[ "$code" != "$expected_code" ]] || [[ "$out" != *"ALERT=$expected_alert"* ]]; then
      echo "FAIL $name: exit=$code want=$expected_code out=$out"
      failures=$((failures + 1))
    else
      echo "ok   $name -> $out"
    fi
  }

  printf 'refunds_in_flight=0\nrefunds_stuck=0\noldest_refund_hours=0\nrefunds_failed_unread=0\n' > "$tmp/quiet"
  check "a quiet shop is healthy" 0 HEALTHY "$tmp/quiet"

  printf 'refunds_in_flight=4\nrefunds_stuck=0\noldest_refund_hours=20\nrefunds_failed_unread=0\n' > "$tmp/busy"
  check "refunds in flight but none old is healthy" 0 HEALTHY "$tmp/busy"

  printf 'refunds_in_flight=4\nrefunds_stuck=1\noldest_refund_hours=91\nrefunds_failed_unread=0\n' > "$tmp/stuck"
  check "one refund past the threshold alerts" 1 REFUND_STUCK "$tmp/stuck"

  printf 'refunds_in_flight=2\nrefunds_stuck=0\noldest_refund_hours=3\nrefunds_failed_unread=1\n' > "$tmp/rejected"
  check "a rejected refund alerts even when nothing is old" 1 REFUND_REJECTED "$tmp/rejected"

  : > "$tmp/empty"
  check "an empty file is an alert, not a pass" 1 MISSING "$tmp/empty"

  printf 'refunds_in_flight=\nrefunds_stuck=oops\n' > "$tmp/garbage"
  check "an unparseable file is an alert, not a pass" 1 UNREADABLE "$tmp/garbage"

  printf 'collected_at=2026-01-01T00:00:00Z\n' > "$tmp/partial"
  check "a file missing its counts is an alert" 1 UNREADABLE "$tmp/partial"

  # The threshold has to be the one that actually decides, not decoration.
  printf 'refunds_in_flight=1\nrefunds_stuck=1\noldest_refund_hours=100\nrefunds_failed_unread=0\n' > "$tmp/threshold"
  STUCK_AFTER_HOURS=200 check "the backend decides stuck, this reports it" 1 REFUND_STUCK "$tmp/threshold"

  # No customer data may reach the output, whatever is in the file. A
  # collection bug that started emitting names must not be laundered into a
  # CI log by this script.
  printf 'refunds_in_flight=1\nrefunds_stuck=1\noldest_refund_hours=99\nrefunds_failed_unread=0\ncustomer_email=someone@example.com\n' > "$tmp/leaky"
  leak_out="$(evaluate "$tmp/leaky" || true)"
  if [[ "$leak_out" == *"someone@example.com"* ]]; then
    echo "FAIL no customer data may reach the output: $leak_out"
    failures=$((failures + 1))
  else
    echo "ok   customer data in the input never reaches the output"
  fi

  if [[ "$failures" -ne 0 ]]; then
    echo "money health self-test: $failures failure(s)"
    exit 1
  fi
  echo "money health self-test: all checks passed"
  exit 0
fi

evaluate "${1:-/tmp/money.txt}"
