#!/usr/bin/env bash
# Evaluate backup sidecar status.txt for alerting. Exit 0 only when the last
# attempt is SUCCESS and fresh. Does not print secrets.
#
#   BACKUP_DIR=/backups MAX_AGE_HOURS=26 ./evaluate-backup-status.sh
#   ./evaluate-backup-status.sh /path/to/status.txt
set -euo pipefail

MAX_AGE_HOURS="${MAX_AGE_HOURS:-26}"
STATUS_FILE="${1:-${BACKUP_DIR:-/backups}/status.txt}"

if [[ ! -s "$STATUS_FILE" ]]; then
  echo "ALERT=MISSING reason=status.txt missing or empty"
  exit 1
fi

status="$(grep '^status=' "$STATUS_FILE" | tail -n 1 | cut -d= -f2- | tr -d '\r' || true)"
taken_at="$(grep '^taken_at=' "$STATUS_FILE" | tail -n 1 | cut -d= -f2- | tr -d '\r' || true)"
filename="$(grep '^filename=' "$STATUS_FILE" | tail -n 1 | cut -d= -f2- | tr -d '\r' || true)"

if [[ "$status" != "SUCCESS" ]]; then
  echo "ALERT=FAILED status=${status:-empty} filename=${filename:-unknown}"
  exit 1
fi

if [[ -z "$taken_at" ]]; then
  echo "ALERT=MISSING reason=taken_at missing"
  exit 1
fi

# taken_at is ISO-8601 UTC from backup.sh.
epoch="$(date -u -d "$taken_at" +%s 2>/dev/null || date -u -j -f '%Y-%m-%dT%H:%M:%SZ' "$taken_at" +%s)"
now="$(date -u +%s)"
age_seconds=$((now - epoch))
max_seconds=$((MAX_AGE_HOURS * 3600))
if [[ "$age_seconds" -gt "$max_seconds" ]]; then
  echo "ALERT=STALE age_seconds=$age_seconds max_seconds=$max_seconds filename=$filename"
  exit 1
fi

echo "ALERT=HEALTHY age_seconds=$age_seconds filename=$filename"
exit 0
